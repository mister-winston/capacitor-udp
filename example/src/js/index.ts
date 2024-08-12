import { UdpSocket } from 'capacitor-udp';

if (!UdpSocket.isAvailable()) {
  throw new Error('Not available');
}

(async () => {
  const socket = await UdpSocket.create();

  socket.addEventListener('error', (event) => {
    console.error(event.detail);
  });
  socket.addEventListener('receive', (event) => {
    console.log('Received a message:', new TextDecoder().decode(event.detail));
    socket.close().catch(console.error);
  });

  await socket.bind('0.0.0.0', 0);
  await socket.send('127.0.0.1', 1212, new TextEncoder().encode('Hello'));
})().catch(console.error);
