package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;

public class ReliableUDPServer {
    private static final int BUFFER_SIZE = 1024;
    private static final long MSL = 2000; // Maximum Segment Lifetime (2 seconds)
    private static final int INIT_TIMEOUT = 1000; // Initial timeout 1 second
    private static final int MAX_TIMEOUT = 8000; // Maximum timeout 8 seconds
    private static final int MAX_RETRIES = 5;
    private static final int RECEIVE_WINDOW_SIZE = 16; // 接收窗口大小

    private final int port;
    private DatagramSocket socket;
    private volatile boolean running;
    private final Map<String, ConnectionState> connectionStates;
    private final Map<String, CircularBuffer> receiveBuffers;  // 每个客户端的接收缓冲区
    private final ScheduledExecutorService scheduler;

    public ReliableUDPServer(int port) {
        this.port = port;
        this.connectionStates = new ConcurrentHashMap<>();
        this.receiveBuffers = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
    }

    // TCP-like connection states
    public enum State {
        CLOSED,
        LISTEN,
        SYN_RCVD,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK,
        FIN_WAIT1,
        FIN_WAIT2,
        CLOSING,
        TIME_WAIT
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

    private static class ConnectionState {
        State state;
        ScheduledFuture<?> timeWaitTimer;
        RetransmissionTask currentRetransmissionTask;
        long lastActivityTime;

        ConnectionState() {
            this.state = State.CLOSED;
            this.lastActivityTime = System.currentTimeMillis();
        }

        void cancelTimers() {
            if (timeWaitTimer != null) {
                timeWaitTimer.cancel(false);
            }
            if (currentRetransmissionTask != null && currentRetransmissionTask.future != null) {
                currentRetransmissionTask.future.cancel(false);
            }
        }
    }

    public void start() throws IOException {
        socket = new DatagramSocket(port);
        socket.setSoTimeout(100); // 使用较短的超时以便及时处理所有类型的包
        running = true;
        System.out.println("服务器启动在端口: " + port);

        while (running) {
            try {
                byte[] receiveData = new byte[BUFFER_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket.receive(receivePacket);

                Packet packet = Packet.fromBytes(Arrays.copyOf(receivePacket.getData(), receivePacket.getLength()));
                InetAddress clientAddress = receivePacket.getAddress();
                int clientPort = receivePacket.getPort();
                String clientId = clientAddress.getHostAddress() + ":" + clientPort;

                ConnectionState state = connectionStates.computeIfAbsent(clientId, k -> {
                    ConnectionState newState = new ConnectionState();
                    newState.state = State.LISTEN;
                    return newState;
                });
                state.lastActivityTime = System.currentTimeMillis();

                handlePacket(packet, state, clientAddress, clientPort, clientId);

            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                System.err.println("处理数据包时发生错误: " + e.getMessage());
            }
        }
    }

    private void handlePacket(Packet packet, ConnectionState state, InetAddress clientAddress, int clientPort, String clientId) throws IOException {
        switch (state.state) {
            case LISTEN:
                if (packet.getType() == Packet.TYPE_SYN) {
                    System.out.println("收到SYN包，发送SYN-ACK");
                    state.state = State.SYN_RCVD;
                    startRetransmission(new Packet(Packet.TYPE_SYN_ACK, 0, null, false), clientAddress, clientPort, state);
                }
                break;

            case SYN_RCVD:
                if (packet.getType() == Packet.TYPE_ACK) {
                    System.out.println("收到ACK，连接建立");
                    stopRetransmission(state);
                    state.state = State.ESTABLISHED;
                    // 为新连接创建环形缓冲区
                    receiveBuffers.put(clientId, new CircularBuffer(RECEIVE_WINDOW_SIZE));
                }
                break;

            case ESTABLISHED:
                if (packet.getType() == Packet.TYPE_DATA) {
                    handleDataPacket(packet, clientAddress, clientPort);
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    System.out.println("收到FIN包，发送ACK");
                    state.state = State.CLOSE_WAIT;
                    sendPacket(new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false), clientAddress, clientPort);
                    System.out.println("发送FIN包");
                    state.state = State.LAST_ACK;
                    startRetransmission(new Packet(Packet.TYPE_FIN, 0, null, false), clientAddress, clientPort, state);
                }
                break;

            case CLOSE_WAIT:
                // 等待应用层关闭
                break;

            case LAST_ACK:
                if (packet.getType() == Packet.TYPE_ACK) {
                    System.out.println("收到最后的ACK，关闭连接");
                    stopRetransmission(state);
                    state.state = State.CLOSED;
                    cleanup(clientId);
                }
                break;

            case FIN_WAIT1:
                if (packet.getType() == Packet.TYPE_ACK) {
                    stopRetransmission(state);
                    state.state = State.FIN_WAIT2;
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    state.state = State.CLOSING;
                    startRetransmission(new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false), clientAddress, clientPort, state);
                }
                break;

            case FIN_WAIT2:
                if (packet.getType() == Packet.TYPE_FIN) {
                    startRetransmission(new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false), clientAddress, clientPort, state);
                    state.state = State.TIME_WAIT;
                    scheduleTimeWait(clientId);
                }
                break;

            case CLOSING:
                if (packet.getType() == Packet.TYPE_ACK) {
                    stopRetransmission(state);
                    state.state = State.TIME_WAIT;
                    scheduleTimeWait(clientId);
                }
                break;

            case TIME_WAIT:
                // 等待2MSL后关闭
                break;
        }
    }

    /**
     * 处理数据包
     */
    private void handleDataPacket(Packet packet, InetAddress clientAddress, int clientPort) throws IOException {
        String clientId = clientAddress.getHostAddress() + ":" + clientPort;
        CircularBuffer buffer = receiveBuffers.get(clientId);
        if (buffer == null) {
            System.out.println("错误：未找到客户端的接收缓冲区");
            return;
        }

        int seqNum = packet.getSeqNum();
        int result = buffer.put(seqNum, packet.getData());
        
        switch (result) {
            case 1: // 成功放入缓冲区
                // 处理连续的数据包
                buffer.processContiguous();
                
                // 发送ACK
                sendAck(buffer.getNextSeq() - 1, buffer.getAvailableWindow(), clientAddress, clientPort);
                
                // 如果需要，发送窗口更新
                if (buffer.needsWindowUpdate()) {
                    System.out.println("发送窗口更新");
                    sendAck(buffer.getNextSeq() - 1, buffer.getAvailableWindow(), clientAddress, clientPort);
                }
                
                // 如果是最后一个包，打印完整消息
                if (packet.isLast()) {
                    String message = buffer.getAndClearMessage();
                    System.out.println("收到完整消息: " + message);
                }
                break;
                
            case 0: // 重复的包，需要重发ACK
                sendAck(buffer.getNextSeq() - 1, buffer.getAvailableWindow(), clientAddress, clientPort);
                break;
                
            case -1: // 窗口外的包，发送当前窗口大小
                sendAck(buffer.getNextSeq() - 1, buffer.getAvailableWindow(), clientAddress, clientPort);
                break;
        }
    }

    private void startRetransmission(Packet packet, InetAddress address, int port, ConnectionState state) throws IOException {
        stopRetransmission(state);
        
        RetransmissionTask task = new RetransmissionTask(packet, address, port);
        state.currentRetransmissionTask = task;
        
        // 首次发送
        sendPacket(packet, address, port);
        
        // 设置重传定时器
        task.future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (task.retries < MAX_RETRIES) {
                    System.out.println("重传控制包: " + packet.getType() + ", 重试次数: " + (task.retries + 1));
                    sendPacket(packet, address, port);
                    task.retries++;
                    task.incrementTimeout();
                } else {
                    System.out.println("重传次数超过限制，关闭连接");
                    stopRetransmission(state);
                    cleanup(address.getHostAddress() + ":" + port);
                }
            } catch (IOException e) {
                System.err.println("重传失败: " + e.getMessage());
            }
        }, task.currentTimeout, task.currentTimeout, TimeUnit.MILLISECONDS);
    }

    private void stopRetransmission(ConnectionState state) {
        if (state.currentRetransmissionTask != null && state.currentRetransmissionTask.future != null) {
            state.currentRetransmissionTask.future.cancel(false);
            state.currentRetransmissionTask = null;
        }
    }

    private void scheduleTimeWait(String clientId) {
        ConnectionState state = connectionStates.get(clientId);
        if (state != null && state.timeWaitTimer == null) {
            state.timeWaitTimer = scheduler.schedule(() -> {
                System.out.println("TIME_WAIT超时，关闭连接");
                cleanup(clientId);
                return null;
            }, 2 * MSL, TimeUnit.MILLISECONDS);
        }
    }

    private void cleanup(String clientId) {
        ConnectionState state = connectionStates.remove(clientId);
        if (state != null) {
            state.cancelTimers();
        }
        receiveBuffers.remove(clientId);  // 清理接收缓冲区
    }

    private void sendPacket(Packet packet, InetAddress address, int port) throws IOException {
        byte[] data = packet.toBytes();
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, address, port);
        socket.send(datagramPacket);
    }

    private void sendAck(int seqNum, int windowSize, InetAddress address, int port) throws IOException {
        Packet packet = new Packet(Packet.TYPE_ACK, seqNum, null, false, windowSize);
        sendPacket(packet, address, port);
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

    public static void main(String[] args) throws IOException {
        ReliableUDPServer server = new ReliableUDPServer(9876);
        server.start();
    }
}
