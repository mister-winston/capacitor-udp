import { registerPlugin } from '@capacitor/core';

import type { IUdpPlugin } from './definitions';

const UdpPlugin = registerPlugin<IUdpPlugin>('UdpPlugin', {
  web: () => import('./web').then((m) => new m.UdpPluginWeb()),
});

export default UdpPlugin;
