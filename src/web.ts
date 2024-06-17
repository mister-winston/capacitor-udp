import { registerWebPlugin, WebPlugin } from '@capacitor/core';

export class UdpPluginWeb extends WebPlugin {
  constructor() {
    super({
      name: 'UdpPlugin',
      platforms: ['web'],
    });
  }
}

const UdpPlugin = new UdpPluginWeb();

registerWebPlugin(UdpPlugin);

export { UdpPlugin };
