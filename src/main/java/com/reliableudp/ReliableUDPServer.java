package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.*;
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
        int sendNextSeqNum;  // 下一个要发送的序列号
        int recvExpectedSeqNum;  // 期望收到的序列号
        int sendUnackedSeqNum;  // 最早未确认的序列号
        private static final long MSL = 2000; // 2 seconds
        private final Map<Integer, Packet> unackedPackets;  // 未确认的数据包
        private final TreeMap<Integer, byte[]> receiveBuffer;  // 接收缓冲区，用于存储乱序到达的数据包
        private static final int RECEIVE_WINDOW_SIZE = 1024;  // 接收窗口大小
        private int receiveWindowSize;

        ConnectionState() {
            this.state = State.LISTEN;  // 服务器端初始状态为LISTEN
            this.lastActivityTime = System.currentTimeMillis();
            this.sendNextSeqNum = (int) (Math.random() * Integer.MAX_VALUE);  // 随机初始序列号
            this.sendUnackedSeqNum = this.sendNextSeqNum;
            this.unackedPackets = new ConcurrentHashMap<>();
            this.receiveBuffer = new TreeMap<>();
            this.receiveWindowSize = RECEIVE_WINDOW_SIZE;
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

        public synchronized void addToReceiveBuffer(int seqNum, byte[] data) {
            // 只有在接收窗口内的数据包才会被缓存
            if (seqNum >= recvExpectedSeqNum && 
                seqNum < recvExpectedSeqNum + receiveWindowSize) {
                receiveBuffer.put(seqNum, data);
            }
        }

        public synchronized List<byte[]> processReceiveBuffer() {
            List<byte[]> orderedData = new ArrayList<>();
            
            // 尝试按序处理缓冲区中的数据
            while (!receiveBuffer.isEmpty()) {
                Map.Entry<Integer, byte[]> firstEntry = receiveBuffer.firstEntry();
                if (firstEntry.getKey() == recvExpectedSeqNum) {
                    // 找到期望的序列号
                    orderedData.add(firstEntry.getValue());
                    receiveBuffer.remove(firstEntry.getKey());
                    recvExpectedSeqNum += firstEntry.getValue().length;
                } else {
                    // 遇到空隙，停止处理
                    break;
                }
            }
            
            return orderedData;
        }

        public synchronized int getReceiveWindowSize() {
            // 计算当前可用的接收窗口大小
            int bufferedDataSize = 0;
            for (byte[] data : receiveBuffer.values()) {
                bufferedDataSize += data.length;
            }
            return Math.max(0, receiveWindowSize - bufferedDataSize);
        }

        public synchronized Map<Integer, byte[]> getReceiveBuffer() {
            return receiveBuffer;
        }

        public void updateLastActivityTime() {
            this.lastActivityTime = System.currentTimeMillis();
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
        System.out.println("Server started on port " + port);
        System.out.println("================================================");

        try {
            socket.setSoTimeout(100); // 使用较短的超时以便及时处理所有类型的包
            running = true;

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

                    String clientKey = datagramPacket.getAddress().getHostAddress() + ":" + datagramPacket.getPort();
                    ConnectionState state = connectionStates.computeIfAbsent(clientKey, k -> new ConnectionState());
                    handlePacket(packet, clientKey, state, datagramPacket.getAddress(), datagramPacket.getPort());

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

    private void handlePacket(Packet packet, String clientKey, ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        state.updateLastActivityTime();

        switch (state.getState()) {
            case LISTEN:
                if (packet.isSYN() && !packet.isACK()) {
                    handleSYN(packet, state, clientAddress, clientPort);
                }
                break;

            case SYN_RCVD:
                if (packet.isACK() && !packet.isSYN() && packet.getAckNum() == state.sendNextSeqNum) {
                    state.setState(State.ESTABLISHED);
                    System.out.println("Connection established with " + clientKey);
                    System.out.println("================================================");
                }
                break;

            case ESTABLISHED:
                if (packet.getType() == Packet.TYPE_FIN) {
                    handleFIN(packet, state, clientAddress, clientPort);
                } else if (packet.getType() == Packet.TYPE_DATA) {
                    handleData(packet, state, clientAddress, clientPort);
                }
                break;

            case LAST_ACK:
                if (packet.isACK()) {
                    state.setState(State.CLOSED);
                    System.out.println("Connection closed with " + clientKey);
                    connectionStates.remove(clientKey);
                }
                break;

            case FIN_WAIT1:
                if (packet.isACK()) {
                    state.setState(State.FIN_WAIT2);
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    state.setState(State.CLOSING);
                    sendACK(packet, state, clientAddress, clientPort);
                }
                break;

            case FIN_WAIT2:
                if (packet.getType() == Packet.TYPE_FIN) {
                    sendACK(packet, state, clientAddress, clientPort);
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
        if (state.getState() != State.LISTEN) {
            return;
        }

        // 处理 SYN 包
        int clientSeqNum = packet.getSeqNum();
        int serverInitSeqNum = (int) (Math.random() * Integer.MAX_VALUE);
        
        state.sendNextSeqNum = serverInitSeqNum + 1;
        state.recvExpectedSeqNum = clientSeqNum + 1;
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

    private void handleFIN(Packet packet, ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        // 发送ACK
        sendACK(packet, state, clientAddress, clientPort);

        // 发送FIN
        Packet finPacket = Packet.createFIN(
            port,
            clientPort,
            state.sendNextSeqNum,
            state.recvExpectedSeqNum,
            WINDOW_SIZE
        );
        sendPacket(finPacket, clientAddress, clientPort);
        state.setState(State.LAST_ACK);
    }

    private void handleData(Packet packet, ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        if (packet.getSeqNum() == state.recvExpectedSeqNum) {
            // 按序到达的数据包
            int dataLength = packet.getData() != null ? packet.getData().length : 0;
            state.recvExpectedSeqNum += dataLength;  // 更新为数据长度
            sendACK(packet, state, clientAddress, clientPort);
            
            // 处理数据
            if (packet.getData() != null) {
                String message = new String(packet.getData());
                System.out.println("Received in order: " + message);
                printReceiveBufferStatus(state);
            }
        } else if (packet.getSeqNum() > state.recvExpectedSeqNum) {
            // 乱序到达的数据包，缓存它，但不更新 recvExpectedSeqNum
            if (packet.getData() != null) {
                state.addToReceiveBuffer(packet.getSeqNum(), packet.getData());
                System.out.println("Buffered out-of-order packet with seqNum: " + packet.getSeqNum());
                System.out.println("Expected seqNum: " + state.recvExpectedSeqNum);
                printReceiveBufferStatus(state);
            }
            // 重发上一个 ACK，表明我们还在等待 recvExpectedSeqNum
            Packet ackPacket = Packet.createData(
                port,
                clientPort,
                state.sendNextSeqNum,
                state.recvExpectedSeqNum,  // 使用当前期望的序列号
                null,
                state.getReceiveWindowSize()
            );
            sendPacket(ackPacket, clientAddress, clientPort);
        } else {
            // 收到重复的数据包，重发 ACK
            sendACK(packet, state, clientAddress, clientPort);
            printReceiveBufferStatus(state);
        }

        // 尝试处理缓冲区中的数据包
        processReceiveBuffer(state, clientAddress, clientPort);
    }

    private void printReceiveBufferStatus(ConnectionState state) {
        System.out.println("\n接收窗口状态：");
        System.out.println("----------------------------------------");
        System.out.println("RCV.NXT = " + state.recvExpectedSeqNum);
        
        // 打印缓存内容
        System.out.println("缓存区内容：");
        Map<Integer, byte[]> buffer = state.getReceiveBuffer();
        if (buffer.isEmpty()) {
            System.out.println("  [空]");
        } else {
            // 计算区间
            TreeMap<Integer, byte[]> sortedBuffer = new TreeMap<>(buffer);
            int start = -1, end = -1;
            int lastSeq = -1;
            int lastLength = 0;
            
            for (Map.Entry<Integer, byte[]> entry : sortedBuffer.entrySet()) {
                int currentSeq = entry.getKey();
                byte[] data = entry.getValue();
                
                if (start == -1) {
                    // 第一个区间的开始
                    start = currentSeq;
                    lastSeq = currentSeq;
                    lastLength = data.length;
                } else if (currentSeq == lastSeq + lastLength) {
                    // 连续的序号
                    lastSeq = currentSeq;
                    lastLength = data.length;
                } else {
                    // 遇到不连续的序号，输出当前区间
                    end = lastSeq + lastLength - 1;
                    System.out.printf("  区间[%d-%d]: ", start, end);
                    printDataInRange(sortedBuffer, start, end);
                    
                    // 开始新的区间
                    start = currentSeq;
                    lastSeq = currentSeq;
                    lastLength = data.length;
                }
            }
            
            // 输出最后一个区间
            if (start != -1) {
                end = lastSeq + lastLength - 1;
                System.out.printf("  区间[%d-%d]: ", start, end);
                printDataInRange(sortedBuffer, start, end);
            }
        }
        System.out.println("----------------------------------------");
    }

    private void printDataInRange(TreeMap<Integer, byte[]> buffer, int start, int end) {
        StringBuilder content = new StringBuilder();
        int currentSeq = start;
        
        while (currentSeq <= end) {
            byte[] data = buffer.get(currentSeq);
            if (data != null) {
                if (content.length() > 0) {
                    content.append(" | ");
                }
                content.append(new String(data));
                currentSeq += data.length;
            } else {
                currentSeq++;
            }
        }
        
        System.out.println(content.toString());
    }

    private void processReceiveBuffer(ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        List<byte[]> orderedData = state.processReceiveBuffer();
        if (!orderedData.isEmpty()) {
            System.out.println("Processing ordered data from buffer:");
            System.out.println("----------------------------------------");
        }
        for (byte[] data : orderedData) {
            String message = new String(data);
            System.out.println("Processed from buffer: " + message);
            
            // 发送回显响应
            String response = "Server received: " + message;
            sendData(response.getBytes(), state, clientAddress, clientPort);
        }
        if (!orderedData.isEmpty()) {
            System.out.println("----------------------------------------");
            printReceiveBufferStatus(state);
        }
    }

    private void sendACK(Packet receivedPacket, ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        // 计算ACK号：序列号 + 数据长度
        int dataLength = receivedPacket.getData() != null ? receivedPacket.getData().length : 0;
        if (receivedPacket.isSYN() || receivedPacket.isFIN()) {
            dataLength = 1;  // SYN和FIN包占用一个序列号
        }

        // 如果是乱序数据包，使用期望的序列号作为 ACK
        int ackNum = receivedPacket.getSeqNum() < state.recvExpectedSeqNum ? 
                    state.recvExpectedSeqNum : 
                    receivedPacket.getSeqNum() + dataLength;

        Packet ackPacket = Packet.createData(
            port,
            clientPort,
            state.sendNextSeqNum,
            ackNum,
            null,
            state.getReceiveWindowSize()
        );
        sendPacket(ackPacket, clientAddress, clientPort);
    }

    private void sendPacket(Packet packet, InetAddress address, int port) throws IOException {
        byte[] data = packet.toBytes();
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, address, port);
        socket.send(datagramPacket);
    }

    private void sendData(byte[] data, ConnectionState state, InetAddress clientAddress, int clientPort) throws IOException {
        Packet dataPacket = Packet.createData(
            port,
            clientPort,
            state.sendNextSeqNum,  // 使用我们自己的序列号
            state.recvExpectedSeqNum,  // ACK number
            data,
            state.getReceiveWindowSize()  // 通告当前可用的接收窗口大小
        );
        sendPacket(dataPacket, clientAddress, clientPort);
        state.sendNextSeqNum += data.length;
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
