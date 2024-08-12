const udp = require('dgram');

const server = udp.createSocket('udp4');

server.once('error', (error) => {
  console.error(`Error: ${error}`);
  server.close();
});

server.once('close', () => {
  console.log('Socket is closed!');
});

server.on('message', (msg, info) => {
  console.log('Received %d bytes from %s:%d\n%s', msg.length, info.address, info.port, msg.toString());

  server.send(msg, info.port, 'localhost', (error) => {
    if (error) {
      console.error(error);
    } else {
      console.log('Data sent:', msg.toString());
    }
  });
});

server.on('listening', function () {
  const { port, family, address } = server.address();

  console.log(`Server is listening on: ${address}:${port} (${family})`);
  console.log('');
});

server.bind(1212);
