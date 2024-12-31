package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

public class ReliableUDPClient {
    private static final int BUFFER_SIZE = 1024;
    private static final long MSL = 2000; // Maximum Segment Lifetime (2 seconds)
    private static final int INIT_TIMEOUT = 1000; // Initial timeout 1 second
    private static final int MAX_TIMEOUT = 8000; // Maximum timeout 8 seconds
    private static final int MAX_RETRIES = 5;

    private final String serverHost;
    private final int serverPort;
    private DatagramSocket socket;
    private InetAddress serverAddress;
    private State state;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private RetransmissionTask currentRetransmissionTask;
    private ScheduledFuture<?> timeWaitTimer;
    private volatile boolean running;
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    private int nextSeqNum = 0;

    // TCP-like connection states
    public enum State {
        CLOSED,
        SYN_SENT,
        ESTABLISHED,
        FIN_WAIT1,
        FIN_WAIT2,
        CLOSING,
        TIME_WAIT,
        CLOSE_WAIT,
        LAST_ACK
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

    public ReliableUDPClient(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.state = State.CLOSED;
    }

    public void connect() throws IOException {
        if (state != State.CLOSED) {
            throw new IllegalStateException("Client must be in CLOSED state to connect");
        }

        socket = new DatagramSocket();
        socket.setSoTimeout(100);
        serverAddress = InetAddress.getByName(serverHost);
        running = true;

        // 发送SYN
        System.out.println("发送SYN包");
        state = State.SYN_SENT;
        startRetransmission(new Packet(Packet.TYPE_SYN, 0, null, false), serverAddress, serverPort);

        // 启动接收线程
        new Thread(this::receiveLoop).start();
    }

    private void receiveLoop() {
        while (running) {
            try {
                byte[] receiveData = new byte[BUFFER_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket.receive(receivePacket);

                Packet packet = Packet.fromBytes(Arrays.copyOf(receivePacket.getData(), receivePacket.getLength()));
                handlePacket(packet);

            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running) {
                    System.err.println("接收数据时发生错误: " + e.getMessage());
                }
            }
        }
    }

    private void handlePacket(Packet packet) throws IOException {
        switch (state) {
            case SYN_SENT:
                if (packet.getType() == Packet.TYPE_SYN_ACK) {
                    System.out.println("收到SYN-ACK，发送ACK");
                    stopRetransmission();
                    state = State.ESTABLISHED;
                    sendPacket(new Packet(Packet.TYPE_ACK, 0, null, false), serverAddress, serverPort);
                }
                break;

            case ESTABLISHED:
                if (packet.getType() == Packet.TYPE_ACK) {
                    System.out.println("收到ACK: " + packet.getSeqNum());
                    if (currentRetransmissionTask != null && 
                        currentRetransmissionTask.packet.getSeqNum() == packet.getSeqNum()) {
                        stopRetransmission();
                    }
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    System.out.println("收到FIN，发送ACK");
                    state = State.CLOSE_WAIT;
                    sendPacket(new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false), serverAddress, serverPort);
                    state = State.LAST_ACK;
                    startRetransmission(new Packet(Packet.TYPE_FIN, 0, null, false), serverAddress, serverPort);
                }
                break;

            case FIN_WAIT1:
                if (packet.getType() == Packet.TYPE_ACK) {
                    System.out.println("收到FIN的ACK");
                    stopRetransmission();
                    state = State.FIN_WAIT2;
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    state = State.CLOSING;
                    startRetransmission(new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false), serverAddress, serverPort);
                }
                break;

            case FIN_WAIT2:
                if (packet.getType() == Packet.TYPE_FIN) {
                    System.out.println("收到FIN，发送ACK");
                    startRetransmission(new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false), serverAddress, serverPort);
                    state = State.TIME_WAIT;
                    scheduleTimeWait();
                }
                break;

            case CLOSING:
                if (packet.getType() == Packet.TYPE_ACK) {
                    stopRetransmission();
                    state = State.TIME_WAIT;
                    scheduleTimeWait();
                }
                break;

            case LAST_ACK:
                if (packet.getType() == Packet.TYPE_ACK) {
                    System.out.println("收到最后的ACK，关闭连接");
                    stopRetransmission();
                    state = State.CLOSED;
                    cleanup();
                }
                break;
        }
    }

    public void send(String message) throws IOException {
        if (state != State.ESTABLISHED) {
            throw new IOException("连接未建立");
        }

        byte[] data = message.getBytes();
        Packet packet = new Packet(Packet.TYPE_DATA, nextSeqNum, data, true);
        System.out.println("发送数据包，序号: " + nextSeqNum);
        startRetransmission(packet, serverAddress, serverPort);
        nextSeqNum++;
    }

    private void startRetransmission(Packet packet, InetAddress address, int port) throws IOException {
        stopRetransmission();
        
        RetransmissionTask task = new RetransmissionTask(packet, address, port);
        currentRetransmissionTask = task;
        
        // 首次发送
        sendPacket(packet, address, port);
        
        // 设置重传定时器
        task.future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (task.retries < MAX_RETRIES) {
                    System.out.println("重传数据包，序号: " + packet.getSeqNum() + ", 重试次数: " + (task.retries + 1));
                    sendPacket(packet, address, port);
                    task.retries++;
                    task.incrementTimeout();
                } else {
                    System.out.println("重传次数超过限制，关闭连接");
                    stopRetransmission();
                    cleanup();
                }
            } catch (IOException e) {
                System.err.println("重传失败: " + e.getMessage());
            }
        }, task.currentTimeout, task.currentTimeout, TimeUnit.MILLISECONDS);
    }

    private void stopRetransmission() {
        if (currentRetransmissionTask != null && currentRetransmissionTask.future != null) {
            currentRetransmissionTask.future.cancel(false);
            currentRetransmissionTask = null;
        }
    }

    public void disconnect() throws IOException {
        if (state == State.ESTABLISHED) {
            System.out.println("发送FIN包");
            state = State.FIN_WAIT1;
            startRetransmission(new Packet(Packet.TYPE_FIN, 0, null, false), serverAddress, serverPort);
        }
    }

    private void scheduleTimeWait() {
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }
        
        timeWaitTimer = scheduler.schedule(() -> {
            System.out.println("TIME_WAIT超时，关闭连接");
            cleanup();
            return null;
        }, 2 * MSL, TimeUnit.MILLISECONDS);
    }

    private void cleanup() {
        running = false;
        stopRetransmission();
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        state = State.CLOSED;
    }

    private void sendPacket(Packet packet, InetAddress address, int port) throws IOException {
        byte[] data = packet.toBytes();
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, address, port);
        socket.send(datagramPacket);
    }

    public void close() {
        running = false;
        scheduler.shutdown();
        cleanup();
    }

    public State getState() {
        return state;
    }
}
