import { Capacitor, PluginListenerHandle } from '@capacitor/core';

import UdpPlugin from './plugin';

import type { IUdpPlugin, Properties, SocketInfo, Success } from './definitions';

// TODO: Look into registering it regularly
// Types for Electron
declare global {
  interface Window {
    CapacitorCustomPlatform?: {
      name: 'electron';
      plugins: {
        UdpPlugin: IUdpPlugin;
      };
    };
  }
}

const DesktopPlugin = window.CapacitorCustomPlatform?.plugins?.UdpPlugin;
export const Udp = DesktopPlugin ?? UdpPlugin;

type Events = {
  receive: CustomEvent<ArrayBuffer>;
  error: CustomEvent<{ message: string; resultCode?: number }>;
};

interface TypedEventListenerObject<E extends Event> extends EventListenerObject {
  handleEvent(object: E): void;
}

class TypedEventTarget extends EventTarget {
  addEventListener<E extends keyof Events>(
    type: E,
    callback: ((event: Events[E]) => void) | TypedEventListenerObject<Events[E]> | null,
    options?: AddEventListenerOptions | boolean
  ): void {
    return super.addEventListener(
      type,
      (event) => {
        const evt = event as Events[E];

        if (typeof callback === 'function') {
          callback(evt);
        } else if (typeof callback === 'object') {
          callback?.handleEvent(evt);
        }
      },
      options
    );
  }

  dispatchEvent<E extends keyof Events>(event: Events[E]): boolean {
    return super.dispatchEvent(event);
  }

  removeEventListener<E extends keyof Events>(
    type: E,
    callback: EventListenerOrEventListenerObject | null,
    options?: EventListenerOptions | boolean
  ): void {
    return super.removeEventListener(type, callback, options);
  }
}

class UdpSocket extends TypedEventTarget {
  // @ts-expect-error: we do some shady stuff, but this is initialized in the constructor
  private socket: Awaited<ReturnType<IUdpPlugin['create']>>;

  private listenerHandles: PluginListenerHandle[] = [];

  /** The local IPv4 address */
  get ipv4Address() {
    return this.socket.ipv4;
  }

  /** The local IPv6 address */
  get ipv6Address() {
    return this.socket.ipv6;
  }

  private constructor(properties?: Partial<Properties>) {
    super();

    // @ts-expect-error: can't return a Promise from a constructor in TS, but JS is fine with it
    return this.init(properties).then(() => this);
  }

  /** Update the name and buffer size of the socket */
  update(properties: Partial<Properties>): Promise<Success> {
    return Udp.update({ socketId: this.socket.socketId, properties });
  }

  /** Stop the socket from handing data (not always available) */
  setPaused(paused: boolean): Promise<Success> {
    return Udp.setPaused({ socketId: this.socket.socketId, paused });
  }

  /** Bind to an interface and port */
  bind(address: string, port: number): Promise<Success> {
    return Udp.bind({ socketId: this.socket.socketId, address, port });
  }

  /** Send data to a UDP server */
  async send(
    address: string,
    port: number,
    buffer: string | ArrayBuffer,
    {
      disableAutoFullSend = false,
      maxLoopCount = null,
    }: {
      /**
       * By default the socket attempts to write the entire buffer (even if the OS only sent out a subset) by calling `send` in a loop.
       *  Disabling this may cause partial data to be sent */
      disableAutoFullSend?: boolean;
      /**
       * Max amount of loops for trying to send the full buffer
       *
       * `null` means 2x the buffer byte size
       */
      maxLoopCount?: number | null;
    } = {}
  ): Promise<{ bytesSent: number }> {
    const send = (encodedBuffer: string) =>
      Udp.send({
        socketId: this.socket.socketId,
        buffer: encodedBuffer,
        address,
        port,
      });

    const encodedBuffer = typeof buffer === 'string' ? buffer : UdpSocket.bufferToString(buffer);

    if (disableAutoFullSend) {
      return send(encodedBuffer);
    }

    const decodedBuffer = typeof buffer === 'string' ? UdpSocket.stringToBuffer(buffer) : buffer;
    const max = typeof maxLoopCount === 'number' ? maxLoopCount : 2 * decodedBuffer.byteLength;

    let bytesSent = 0;
    let loopIndex = 0;

    while (bytesSent < decodedBuffer.byteLength) {
      if (loopIndex > max) {
        throw new Error(`Send loop maximum exceeded (${loopIndex})`);
      }

      const slice = decodedBuffer.slice(bytesSent);
      const result = await send(UdpSocket.bufferToString(slice));

      bytesSent += result.bytesSent;
      loopIndex += 1;
    }

    return { bytesSent };
  }

  /** Close the socket. After this has been called the socket can no longer be used */
  async close(): Promise<Success> {
    await Promise.all(this.listenerHandles.map((handle) => handle.remove()));

    return Udp.close({ socketId: this.socket.socketId });
  }

  /** Get info general socket info (only available once `bind` has been called) */
  getInfo(): Promise<SocketInfo> {
    return Udp.getInfo({ socketId: this.socket.socketId });
  }

  /** Join a multicast group */
  joinGroup(address: string): Promise<Success> {
    return Udp.joinGroup({ socketId: this.socket.socketId, address });
  }

  /** Leave a multicast group */
  leaveGroup(address: string): Promise<Success> {
    return Udp.leaveGroup({ socketId: this.socket.socketId, address });
  }

  setMulticastTimeToLive(ttl: number): Promise<Success> {
    return Udp.setMulticastTimeToLive({ socketId: this.socket.socketId, ttl });
  }

  /** Enables broadcast mode */
  setBroadcast(enabled: boolean): Promise<Success> {
    return Udp.setBroadcast({ socketId: this.socket.socketId, enabled });
  }

  setMulticastLoopbackMode(enabled: boolean): Promise<Success> {
    return Udp.setMulticastLoopbackMode({ socketId: this.socket.socketId, enabled });
  }

  getJoinedGroups(): Promise<{ groups: string[] }> {
    return Udp.getJoinedGroups({ socketId: this.socket.socketId });
  }

  private async init(properties?: Partial<Properties>) {
    this.socket = await Udp.create({ properties });

    const promises = [
      Udp.addListener('receive', ({ socketId, buffer }) => {
        if (socketId === this.socket.socketId) {
          const event = new CustomEvent<Events['receive']['detail']>('receive', { detail: UdpSocket.stringToBuffer(buffer) });

          this.dispatchEvent(event);
        }
      }),

      Udp.addListener('receiveError', ({ socketId, message, resultCode }) => {
        if (socketId === this.socket.socketId) {
          const event = new CustomEvent<Events['error']['detail']>('error', { detail: { message, resultCode } });

          this.dispatchEvent(event);
        }
      }),
    ];

    await Promise.all(promises);
  }

  /** Create a new instance of this class (do not use the constructor) */
  static async create(properties?: Partial<Properties>): Promise<UdpSocket> {
    return new UdpSocket(properties);
  }

  /** Determine if the platform supports UDP */
  static isAvailable() {
    return Capacitor.isPluginAvailable('UdpPlugin') || !!window.CapacitorCustomPlatform?.plugins?.UdpPlugin;
  }

  /** Convert an ArrayBuffer to a base64 string */
  static bufferToString(buffer: ArrayBuffer): string {
    const charCodes = new Uint8Array(buffer);

    return btoa(String.fromCharCode.apply(null, [...charCodes]));
  }

  /** Convert a base64 string to an ArrayBuffer */
  static stringToBuffer(base64String: string): ArrayBuffer {
    const str = atob(base64String);
    const buf = new ArrayBuffer(str.length);
    const bufView = new Uint8Array(buf);

    for (let i = 0, strLen = str.length; i < strLen; i++) {
      bufView[i] = str.charCodeAt(i);
    }

    return buf;
  }
}

export default UdpSocket;
