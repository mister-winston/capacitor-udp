# Capacitor UDP

UDP Plugin for Capacitor forked from [unitree-czk/capacitor-udp](https://github.com/unitree-czk/capacitor-udp).
Supports both IPv6 and IPv4, multicast and broadcast!

## Examples

An example project can be found in the 'examples/' folder.

----------------

```typescript
import { UdpSocket } from 'capacitor-udp';

const socket = await UdpSocket.create();

socket.addEventListener('error', console.error);
socket.addEventListener('receive', (event) => {
    console.log('Received a message:', new TextDecoder().decode(event.detail));
    socket.close().catch(console.error);
});

await socket.send('127.0.0.1', 1212, new TextEncoder().encode('Hello'));
```

## Install

This package is not published to NPM and does not come with the built files in Git, so you will need to clone and build it separately:

```bash
$ git clone https://github.com/mister-winston/capacitor-udp.git plugins/capacitor-udp
$ cd plugins/capacitor-udp && npm install && npm run build
$ npm i plugins/capacitor-udp
```
