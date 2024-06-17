export * from './definitions';
export * from './web';

export const UdpPluginUtils = {
  bufferToString(buffer: ArrayBuffer): string {
    const charCodes = new Uint8Array(buffer);

    return btoa(String.fromCharCode.apply(null, [...charCodes]));
  },
  stringToBuffer(base64String: string): ArrayBuffer {
    const str = atob(base64String);
    const buf = new ArrayBuffer(str.length);
    const bufView = new Uint8Array(buf);

    for (let i = 0, strLen = str.length; i < strLen; i++) {
      bufView[i] = str.charCodeAt(i);
    }

    return buf;
  },
};
