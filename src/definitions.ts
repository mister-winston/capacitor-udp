import { PluginListenerHandle } from '@capacitor/core';

export type Success = Record<string, unknown>;
export type Properties = { name: string; bufferSize: number; reuseExisting?: boolean };
export type SocketInfo = {
  socketId: number;
  bufferSize: number;
  name: string | null;
  paused: boolean;
  localAddress?: string;
  localPort?: number;
};

export interface IUdpPlugin {
  create(options?: { properties?: Partial<Properties> }): Promise<{ socketId: number; ipv4: string; ipv6: string }>;

  update(options: { socketId: number; properties: Partial<Properties> }): Promise<Success>;

  setPaused(options: { socketId: number; paused: boolean }): Promise<Success>;

  bind(options: { socketId: number; address: string; port: number }): Promise<Success>;

  send(options: { socketId: number; address: string; port: number; buffer: string }): Promise<{ bytesSent: number }>;

  closeAllSockets(): Promise<Success>;

  close(options: { socketId: number }): Promise<Success>;

  getInfo(options: { socketId: number }): Promise<SocketInfo>;

  getSockets(): Promise<{
    sockets: SocketInfo[];
  }>;

  joinGroup(options: { socketId: number; address: string }): Promise<Success>;

  leaveGroup(options: { socketId: number; address: string }): Promise<Success>;

  setMulticastTimeToLive(options: { socketId: number; ttl: number }): Promise<Success>;

  setBroadcast(options: { socketId: number; enabled: boolean }): Promise<Success>;

  setMulticastLoopbackMode(options: { socketId: number; enabled: boolean }): Promise<Success>;

  getJoinedGroups(options: { socketId: number }): Promise<{ groups: string[] }>;

  addListener(events: 'receive', functions: (params: { socketId: number; buffer: string }) => void): Promise<PluginListenerHandle>;

  addListener(
    events: 'receiveError',
    functions: (params: {
      socketId: number;
      message: string;
      /** Only available on Android */
      resultCode?: number;
    }) => void
  ): Promise<PluginListenerHandle>;
}
