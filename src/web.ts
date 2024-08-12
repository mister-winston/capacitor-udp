import { WebPlugin } from '@capacitor/core';

import type { IUdpPlugin, SocketInfo, Success } from './definitions';

export class UdpPluginWeb extends WebPlugin implements IUdpPlugin {
  create(): Promise<{ socketId: number; ipv4: string; ipv6: string }> {
    throw this.unimplemented('Not implemented on web.');
  }

  update(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  setPaused(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  bind(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  send(): Promise<{ bytesSent: number }> {
    throw this.unimplemented('Not implemented on web.');
  }

  closeAllSockets(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  close(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  getInfo(): Promise<SocketInfo> {
    throw this.unimplemented('Not implemented on web.');
  }

  getSockets(): Promise<{ sockets: SocketInfo[] }> {
    throw this.unimplemented('Not implemented on web.');
  }

  joinGroup(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  leaveGroup(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  setMulticastTimeToLive(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  setBroadcast(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  setMulticastLoopbackMode(): Promise<Success> {
    throw this.unimplemented('Not implemented on web.');
  }

  getJoinedGroups(): Promise<{ groups: string[] }> {
    throw this.unimplemented('Not implemented on web.');
  }
}
