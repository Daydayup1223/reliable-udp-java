# Reliable UDP Implementation in Java

This project implements a reliable UDP protocol on top of Java's UDP sockets, providing features like:
- Reliable data transmission
- Flow control with sliding window
- Connection establishment and termination
- Packet acknowledgment and retransmission

## Project Structure
```
reliable-udp-java/
├── src/
│   ├── main/java/com/reliableudp/
│   │   ├── Packet.java           # Packet structure and serialization
│   │   ├── ReliableUDPClient.java # Client implementation
│   │   └── ReliableUDPServer.java # Server implementation
│   └── test/java/com/reliableudp/
│       └── ReliableUDPTest.java   # Test cases
├── pom.xml                        # Maven configuration
└── README.md                      # This file
```

## Building and Running
1. Build the project:
```bash
mvn clean compile
```

2. Run the tests:
```bash
mvn test
```

## Features
- Reliable data transmission using acknowledgments
- Flow control using sliding window
- Connection establishment (handshake)
- Connection termination
- Packet retransmission on timeout
- Sequence numbers for ordering

## Usage Example
```java
// Start server
ReliableUDPServer server = new ReliableUDPServer(9876);
new Thread(() -> server.start()).start();

// Create client and send data
ReliableUDPClient client = new ReliableUDPClient("localhost", 9876);
client.sendData("Hello, Reliable UDP!".getBytes());
client.close();
```
