package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;

public class ReliableUDPServer {
    private static final int BUFFER_SIZE = 1024;
    private static final int WINDOW_SIZE = 1024;
    private static final long MSL = 2000; // Maximum Segment Lifetime (2 seconds)
    private static final int INIT_TIMEOUT = 1000; // Initial timeout 1 second
    private static final int MAX_TIMEOUT = 8000; // Maximum timeout 8 seconds
    private static final int MAX_RETRIES = 5;

    private final int port;
    private final DatagramSocket socket;
    private volatile boolean running;
    private final Map<String, ConnectionState> connectionStates;
    private final Map<String, CircularBuffer> receiveBuffers;
    private final ScheduledExecutorService scheduler;

    public ReliableUDPServer(int port) throws SocketException {
        this.port = port;
        this.socket = new DatagramSocket(port);
        this.running = false;
        this.connectionStates = new ConcurrentHashMap<>();
        this.receiveBuffers = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
    }

    // TCP-like connection states
    public enum State {
        CLOSED,         // 初始状态
        LISTEN,         // 服务器监听状态
        SYN_RCVD,      // 服务器收到SYN后的状态
        SYN_SENT,      // 客户端发送SYN后的状态
        ESTABLISHED,    // 连接建立状态
        FIN_WAIT1,     // 主动关闭方发送FIN后的状态
        FIN_WAIT2,     // 主动关闭方收到ACK后的状态
        CLOSING,       // 双方同时关闭时的状态
        TIME_WAIT,     // 等待2MSL的状态
        CLOSE_WAIT,    // 被动关闭方收到FIN后的状态
        LAST_ACK       // 被动关闭方发送FIN后的状态
    }

    private class ConnectionState {
        State state;
        ScheduledFuture<?> timeWaitTimer;
        RetransmissionTask currentRetransmissionTask;
        long lastActivityTime;
        int nextSeqNum;
        int expectedSeqNum;
        private static final long MSL = 2000; // 2 seconds

        ConnectionState() {
            this.state = State.LISTEN;  // 服务器端初始状态为LISTEN
            this.lastActivityTime = System.currentTimeMillis();
        }

        public synchronized State getState() {
            return state;
        }

        public synchronized void setState(State state) {
            this.state = state;
            this.lastActivityTime = System.currentTimeMillis();
            
            // 如果进入TIME_WAIT状态，启动2MSL定时器
            if (state == State.TIME_WAIT) {
                if (timeWaitTimer != null) {
                    timeWaitTimer.cancel(false);
                }
                timeWaitTimer = scheduler.schedule(() -> {
                    synchronized (ConnectionState.this) {
                        if (getState() == State.TIME_WAIT) {
                            setState(State.CLOSED);
                        }
                    }
                }, 2 * MSL, TimeUnit.MILLISECONDS);
            }
        }

        public void cancelTimers() {
            if (timeWaitTimer != null) {
                timeWaitTimer.cancel(false);
            }
            if (currentRetransmissionTask != null && currentRetransmissionTask.future != null) {
                currentRetransmissionTask.future.cancel(false);
            }
        }

        public int getNextSeqNum() {
            return nextSeqNum;
        }

        public void setNextSeqNum(int nextSeqNum) {
            this.nextSeqNum = nextSeqNum;
        }

        public int getExpectedSeqNum() {
            return expectedSeqNum;
        }

        public void setExpectedSeqNum(int expectedSeqNum) {
            this.expectedSeqNum = expectedSeqNum;
        }
    }

    private static class RetransmissionTask {
        private final Packet packet;
        private final InetAddress address;
        private final int port;
        private int retries;
        private int currentTimeout;
        private ScheduledFuture<?> future;

        RetransmissionTask(Packet packet, InetAddress address, int port) {
            this.packet = packet;
            this.address = address;
            this.port = port;
            this.retries = 0;
            this.currentTimeout = INIT_TIMEOUT;
        }

        void incrementTimeout() {
            currentTimeout = Math.min(currentTimeout * 2, MAX_TIMEOUT);
        }
    }

    public void start() throws IOException {
        try {
            socket.setSoTimeout(100); // 使用较短的超时以便及时处理所有类型的包
            running = true;
            System.out.println("Server started on port " + port);

            while (running) {
                try {
                    // 接收数据包
                    byte[] buffer = new byte[BUFFER_SIZE];
                    DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagramPacket);

                    // 解析数据包
                    Packet packet = Packet.fromBytes(Arrays.copyOf(buffer, datagramPacket.getLength()));
                    if (packet == null) {
                        System.err.println("Failed to parse packet, length=" + datagramPacket.getLength());
                        continue;
                    }

                    System.out.println("收到数据包：type=" + packet.getType() + ", from=" + 
                                     datagramPacket.getAddress() + ":" + datagramPacket.getPort());

                    handlePacket(packet, datagramPacket.getAddress(), datagramPacket.getPort());

                } catch (SocketTimeoutException e) {
                    // 正常超时，继续循环
                    continue;
                } catch (IOException e) {
                    if (running) {
                        System.err.println("接收数据包时发生错误: " + e.getMessage());
                        e.printStackTrace();
                    }
                } catch (Exception e) {
                    if (running) {
                        System.err.println("处理数据包时发生错误: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("服务器启动失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    private void handlePacket(Packet packet, InetAddress clientAddress, int clientPort) throws IOException {
        String clientKey = clientAddress.getHostAddress() + ":" + clientPort;
        ConnectionState state = connectionStates.computeIfAbsent(clientKey, k -> new ConnectionState());

        switch (state.getState()) {
            case LISTEN:
                if (packet.getType() == Packet.TYPE_SYN) {
                    handleSYN(packet, state, clientAddress, clientPort);
                }
                break;

            case SYN_RCVD:
                if (packet.isACK() && !packet.isSYN() && packet.getAckNum() == state.getNextSeqNum()) {
                    state.setState(State.ESTABLISHED);
                    System.out.println("Connection established with " + clientKey);
                }
                break;

            case ESTABLISHED:
                if (packet.getType() == Packet.TYPE_FIN) {
                    // 收到FIN，进入CLOSE_WAIT状态
                    state.setState(State.CLOSE_WAIT);
                    // 发送ACK
                    sendACK(packet, clientAddress, clientPort);
                    // 发送FIN
                    sendFIN(clientAddress, clientPort);
                    state.setState(State.LAST_ACK);
                } else if (packet.getType() == Packet.TYPE_DATA) {
                    handleData(packet, state, clientAddress, clientPort);
                }
                break;

            case CLOSE_WAIT:
                // 等待应用层关闭
                break;

            case LAST_ACK:
                if (packet.isACK()) {
                    state.setState(State.CLOSED);
                    cleanup(clientKey);
                }
                break;

            case FIN_WAIT1:
                if (packet.isACK()) {
                    state.setState(State.FIN_WAIT2);
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    state.setState(State.CLOSING);
                    sendACK(packet, clientAddress, clientPort);
                }
                break;

            case FIN_WAIT2:
                if (packet.getType() == Packet.TYPE_FIN) {
                    sendACK(packet, clientAddress, clientPort);
                    state.setState(State.TIME_WAIT);
                }
                break;

            case CLOSING:
                if (packet.isACK()) {
                    state.setState(State.TIME_WAIT);
                }
                break;

            case TIME_WAIT:
                // 在ConnectionState中已经处理了2MSL定时器
                break;
        }
    }

    private void handleSYN(Packet packet, ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        int clientSeqNum = packet.getSeqNum();
        int serverInitSeqNum = (int) (Math.random() * Integer.MAX_VALUE);
        
        state.setNextSeqNum(serverInitSeqNum + 1);
        state.setExpectedSeqNum(clientSeqNum + 1);
        state.setState(State.SYN_RCVD);

        // 发送 SYN-ACK
        Packet synAckPacket = Packet.createSYNACK(
            port,
            clientPort,
            serverInitSeqNum,
            clientSeqNum + 1,
            WINDOW_SIZE
        );
        sendPacket(synAckPacket, clientAddress, clientPort);
    }

    private void handleData(Packet packet, ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        if (packet.getSeqNum() == state.getExpectedSeqNum()) {
            // 按序到达的数据包
            int dataLength = packet.getData() != null ? packet.getData().length : 0;
            state.setExpectedSeqNum(state.getExpectedSeqNum() + dataLength);  // 更新为数据长度
            sendACK(packet, clientAddress, clientPort);
            
            // 处理数据
            if (packet.getData() != null) {
                String message = new String(packet.getData());
                System.out.println("Received: " + message);
            }
        } else {
            // 乱序到达的数据包，重发上一个ACK
            sendACK(packet, clientAddress, clientPort);
        }
    }

    private void sendACK(Packet receivedPacket, InetAddress clientAddress, int clientPort) throws IOException {
        // 计算正确的ACK号：序列号 + 数据长度
        int dataLength = receivedPacket.getData() != null ? receivedPacket.getData().length : 0;
        if (receivedPacket.isSYN() || receivedPacket.isFIN()) {
            dataLength = 1;  // SYN和FIN包占用一个序列号
        }
        int ackNum = receivedPacket.getSeqNum() + dataLength;

        Packet ackPacket = Packet.createData(
            port,
            clientPort,
            receivedPacket.getAckNum(),  // 使用收到的ACK号作为序列号
            ackNum,  // 使用计算出的ACK号
            null,
            WINDOW_SIZE
        );
        sendPacket(ackPacket, clientAddress, clientPort);
    }

    private void sendFIN(InetAddress clientAddress, int clientPort) throws IOException {
        Packet finPacket = Packet.createFIN(
            port,
            clientPort,
            0,  // 序列号在实际实现中应该是当前的序列号
            0,  // ACK number
            WINDOW_SIZE
        );
        sendPacket(finPacket, clientAddress, clientPort);
    }

    private void sendPacket(Packet packet, InetAddress address, int port) throws IOException {
        byte[] data = packet.toBytes();
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, address, port);
        socket.send(datagramPacket);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        for (String clientId : connectionStates.keySet()) {
            cleanup(clientId);
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    private void cleanup(String clientId) {
        ConnectionState state = connectionStates.remove(clientId);
        if (state != null) {
            state.cancelTimers();
        }
        receiveBuffers.remove(clientId);  // 清理接收缓冲区
    }
}
