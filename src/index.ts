import UdpPlugin from './plugin';
import UdpSocket from './socket';

export * from './definitions';

// Backwards compatibility
const UdpPluginUtils = {
  bufferToString: UdpSocket.bufferToString,
  stringToBuffer: UdpSocket.stringToBuffer,
};

export { UdpPlugin, UdpSocket, UdpPluginUtils };
