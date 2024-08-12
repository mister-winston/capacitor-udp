import { RemoteInfo, Socket, SocketType, createSocket } from 'dgram';
import { networkInterfaces } from 'os';
import { EventEmitter } from 'events';

import type { IUdpPlugin, Properties } from '../../src/definitions';

type Callback = (err?: Error, message?: { data: Buffer; info: RemoteInfo }) => void;

class UDPSocket {
  private name: string;
  private bufferSize: number;
  private socket: Socket;
  private socketType: SocketType;
  private memberships: Set<string>;
  private cb: Callback;

  get properties(): Properties {
    return {
      bufferSize: this.bufferSize,
      name: this.name,
    };
  }

  constructor(cb: Callback, properties?: Partial<Properties>) {
    this.bufferSize = properties?.bufferSize ?? 4096;
    this.name = properties?.name ?? '';
    this.memberships = new Set();
    this.cb = cb;

    // Assume IPv4
    this.socketType = 'udp4';
    this.socket = this.createSocket(this.socketType);
  }

  getAddress() {
    return this.socket.address();
  }

  setProperties(properties: Partial<Properties>) {
    if ('name' in properties && typeof properties.name === 'string') {
      this.name = properties.name;
    }

    if ('bufferSize' in properties && typeof properties.bufferSize === 'number') {
      this.bufferSize = properties.bufferSize;
      this.socket.setRecvBufferSize(this.bufferSize);
      this.socket.setSendBufferSize(this.bufferSize);
    }
  }

  bind(address: string, port: number) {
    const addressIsIpv6 = address.includes(':');

    if (this.socketType === 'udp4' && addressIsIpv6) {
      this.createSocket('udp6');
    } else if (this.socketType === 'udp6' && !addressIsIpv6) {
      this.createSocket('udp4');
    }

    return new Promise<void>((resolve) => {
      this.socket.bind(port, address, () => resolve());
    });
  }

  async send(address: string, port: number, buffer: Buffer) {
    const innerSend = (data: Buffer) =>
      new Promise<number>((resolve, reject) => {
        this.socket.send(data, port, address, (err, bytes) => {
          if (err) {
            reject(err);
          } else {
            resolve(bytes);
          }
        });
      });

    let sent = 0;
    while (sent < buffer.length) {
      sent += await innerSend(buffer.subarray(sent));
    }

    return sent;
  }

  close() {
    return new Promise<void>((resolve) => {
      this.socket.close(() => resolve());
    });
  }

  setBroadcast(enabled: boolean) {
    this.socket.setBroadcast(enabled);
  }

  addMembership(address: string) {
    this.socket.addMembership(address);
    this.memberships.add(address);
  }

  removeMembership(address: string) {
    this.socket.dropMembership(address);
    this.memberships.delete(address);
  }

  getMemberships() {
    return [...this.memberships];
  }

  setMulticastTTL(ttl: number) {
    this.socket.setMulticastTTL(ttl);
  }

  setMulticastLoopbackMode(enabled: boolean) {
    this.socket.setMulticastLoopback(enabled);
  }

  private createSocket(type: SocketType) {
    const originalSocket = this.socket;

    this.socketType = type;
    this.socket = createSocket({
      recvBufferSize: this.bufferSize,
      sendBufferSize: this.bufferSize,
      type: this.socketType,
    });

    this.socket.addListener('error', (err) => this.cb(err));
    this.socket.addListener('message', (data, info) => this.cb(undefined, { data, info }));

    originalSocket?.close();

    return this.socket;
  }

  static getFirstIpAddresses(): { ipv4: string; ipv6: string } {
    const interfaces = networkInterfaces();
    const entries = Object.values(interfaces)
      .flat()
      .filter((entry): entry is NonNullable<typeof entry> => !!entry);

    return {
      ipv4:
        entries.find((entry) => !entry.internal && entry.family === 'IPv4')?.address ??
        entries.find((entry) => entry.internal && entry.family === 'IPv4')?.address ??
        '127.0.0.1',
      ipv6:
        entries.find((entry) => !entry.internal && entry.family === 'IPv6')?.address ??
        entries.find((entry) => entry.internal && entry.family === 'IPv6')?.address ??
        '::1',
    };
  }
}

const handleEvent: Callback = function (this: { socketId: number; plugin: UdpPlugin }, error, message) {
  if (error) {
    this.plugin.emit('receiveError', { socketId: this.socketId, message: error.message || error.toString() });
  } else if (message) {
    this.plugin.emit('receive', { socketId: this.socketId, buffer: message.data.toString('base64') });
  }
};

class UdpPlugin extends EventEmitter implements Omit<IUdpPlugin, 'addListener'> {
  private sockets = new Map<number, UDPSocket>();
  private socketIndex = 0;

  async create(options?: { properties?: Properties }): Promise<{ socketId: number; ipv4: string; ipv6: string }> {
    const socketId = this.socketIndex;

    this.sockets.set(socketId, new UDPSocket(handleEvent.bind({ socketId, plugin: this }), options?.properties));
    this.socketIndex += 1;

    return {
      ...UDPSocket.getFirstIpAddresses(),
      socketId,
    };
  }

  async update(options: { socketId: number; properties: Properties }) {
    const socket = this.getSocketById(options.socketId);

    socket.setProperties(options.properties);

    return {};
  }

  async setPaused(_options: { socketId: number; paused: boolean }) {
    throw new Error('Method not implemented.');

    return {};
  }

  async bind(options: { socketId: number; address: string; port: number }) {
    const socket = this.getSocketById(options.socketId);

    await socket.bind(options.address, options.port);

    return {};
  }

  async send(options: { socketId: number; address: string; port: number; buffer: string }) {
    const socket = this.getSocketById(options.socketId);

    const bytesSent = await socket.send(options.address, options.port, Buffer.from(options.buffer, 'base64'));

    return { bytesSent };
  }

  async closeAllSockets() {
    for (const [socketId, socket] of this.sockets.entries()) {
      await socket.close();
      this.sockets.delete(socketId);
    }

    return {};
  }

  async close(options: { socketId: number }) {
    const socket = this.getSocketById(options.socketId);

    await socket.close();

    return {};
  }

  async getInfo(options: {
    socketId: number;
  }): Promise<{ socketId: number; bufferSize: number; name: string | null; paused: boolean; localAddress?: string; localPort?: number }> {
    const socket = this.getSocketById(options.socketId);

    return this.getSocketInfo(options.socketId, socket);
  }

  getSockets(): Promise<{
    sockets: { socketId: number; bufferSize: number; name: string; paused: boolean; localAddress?: string; localPort?: number }[];
  }> {
    const sockets = [...this.sockets.entries()].map(([socketId, socket]) => this.getSocketInfo(socketId, socket));

    return Promise.resolve({ sockets });
  }

  async setBroadcast(options: { socketId: number; enabled: boolean }) {
    const socket = this.getSocketById(options.socketId);

    socket.setBroadcast(options.enabled);

    return {};
  }

  async joinGroup(options: { socketId: number; address: string }) {
    const socket = this.getSocketById(options.socketId);

    socket.addMembership(options.address);

    return {};
  }

  async leaveGroup(options: { socketId: number; address: string }) {
    const socket = this.getSocketById(options.socketId);

    socket.removeMembership(options.address);

    return {};
  }

  getJoinedGroups(options: { socketId: number }): Promise<{ groups: string[] }> {
    const socket = this.getSocketById(options.socketId);
    const groups = socket.getMemberships();

    return Promise.resolve({ groups });
  }

  async setMulticastTimeToLive(options: { socketId: number; ttl: number }) {
    const socket = this.getSocketById(options.socketId);

    socket.setMulticastTTL(options.ttl);

    return {};
  }

  async setMulticastLoopbackMode(options: { socketId: number; enabled: boolean }) {
    const socket = this.getSocketById(options.socketId);

    socket.setMulticastLoopbackMode(options.enabled);

    return {};
  }

  private getSocketById(id: number) {
    const socket = this.sockets.get(id);

    if (!socket) {
      throw new Error(`No socket with ID ${id} found`);
    }

    return socket;
  }

  private getSocketInfo(socketId: number, socket: UDPSocket) {
    const { name, bufferSize } = socket.properties;
    const address = socket.getAddress();

    return {
      localAddress: address.address,
      localPort: address.port,
      paused: false,
      bufferSize,
      socketId,
      name,
    };
  }
}

export default { UdpPlugin };
