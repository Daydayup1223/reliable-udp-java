package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.Random;
import java.util.concurrent.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

public class ReliableUDPClient {
    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final int serverPort;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;
    private State state;
    private int nextSeqNum;
    private int expectedSeqNum;
    private static final int WINDOW_SIZE = 5;
    private static final int INITIAL_TIMEOUT = 1000; // 初始RTO为1秒
    private static final int MAX_RETRIES = 5;
    private ScheduledFuture<?> timeWaitTimer;
    private static final long MSL = 2000; // 2 seconds
    private int localPort;
    
    // RTT和RTO相关的变量
    private double estimatedRTT = 0;    // 预估的RTT
    private double devRTT = 0;          // RTT偏差
    private int RTO = INITIAL_TIMEOUT;   // 当前的超时时间
    private static final double ALPHA = 0.125;  // RTT平滑因子
    private static final double BETA = 0.25;    // 偏差平滑因子
    private Map<Integer, Long> sendTimes;       // 记录发送时间
    private Map<Integer, ScheduledFuture<?>> retransmissionTimers; // 重传定时器

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

    public ReliableUDPClient(String serverHost, int serverPort) throws IOException {
        this.socket = new DatagramSocket();
        this.serverAddress = InetAddress.getByName(serverHost);
        this.serverPort = serverPort;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.state = State.CLOSED;
        this.localPort = socket.getLocalPort();
        this.sendTimes = new HashMap<>();
        this.retransmissionTimers = new HashMap<>();
    }

    // 更新RTT和RTO
    private void updateRTT(int seqNum) {
        Long sendTime = sendTimes.remove(seqNum);
        if (sendTime != null) {
            double sampleRTT = System.currentTimeMillis() - sendTime;
            
            if (estimatedRTT == 0) {
                // 第一次测量
                estimatedRTT = sampleRTT;
                devRTT = sampleRTT / 2;
            } else {
                // 更新估计RTT和偏差
                devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
                estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
            }
            
            // 计算新的RTO (至少100ms)
            RTO = (int) Math.max(100, estimatedRTT + 4 * devRTT);
            System.out.println("Updated RTT: estimated=" + estimatedRTT + "ms, deviation=" + devRTT + "ms, RTO=" + RTO + "ms");
        }
    }

    // 发送数据包并设置重传定时器
    private void sendWithRTO(Packet packet) throws IOException {
        int seqNum = packet.getSeqNum();
        sendTimes.put(seqNum, System.currentTimeMillis());
        
        // 取消已有的重传定时器（如果存在）
        cancelRetransmissionTimer(seqNum);

        // 发送数据包
        send(packet);

        // 设置新的重传定时器
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            try {
                // 只有在还没收到ACK时才重传
                if (sendTimes.containsKey(seqNum)) {
                    System.out.println("Packet timeout, retransmitting seq=" + seqNum);
                    // 超时重传，使用指数退避
                    RTO *= 2; // 指数退避
                    sendWithRTO(packet);
                }
            } catch (IOException e) {
                System.err.println("重传错误: " + e.getMessage());
                e.printStackTrace();
            }
        }, RTO, TimeUnit.MILLISECONDS);
        
        retransmissionTimers.put(seqNum, timer);
    }

    // 取消重传定时器
    private void cancelRetransmissionTimer(int seqNum) {
        ScheduledFuture<?> timer = retransmissionTimers.remove(seqNum);
        if (timer != null) {
            timer.cancel(false);
        }
        // 同时清除发送时间记录
        sendTimes.remove(seqNum);
    }

    public void connect() throws IOException {
        if (state != State.CLOSED) {
            throw new IllegalStateException("Client must be in CLOSED state to connect");
        }

        // 生成初始序列号
        Random random = new Random();
        nextSeqNum = random.nextInt(10000);

        // 发送 SYN
        Packet synPacket = Packet.createSYN(
            socket.getLocalPort(),
            serverPort,
            nextSeqNum,
            WINDOW_SIZE
        );
        sendWithRTO(synPacket);
        state = State.SYN_SENT;

        // 等待连接建立
        waitForConnection();
    }

    private void waitForConnection() throws IOException {
        while (state != State.ESTABLISHED && state != State.CLOSED) {
            try {
                byte[] receiveData = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket.setSoTimeout(INITIAL_TIMEOUT);
                socket.receive(receivePacket);

                // 只使用实际接收到的数据
                byte[] actualData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
                Packet packet = Packet.fromBytes(actualData);
                if (packet != null) {
                    handlePacket(packet);
                }
            } catch (SocketTimeoutException e) {
                // 超时继续等待
            }
        }
    }

    private void handlePacket(Packet packet) throws IOException {
        // 处理确认
        if (packet.getFlags() == Packet.FLAG_ACK) {
            int ackNum = packet.getAckNum();
            // 更新RTT
            Long sendTime = sendTimes.get(ackNum);
            if (sendTime != null) {
                updateRTT(ackNum);
                // 立即取消重传定时器
                cancelRetransmissionTimer(ackNum);
                System.out.println("收到ACK " + ackNum + "，取消重传定时器");
            }
        }

        switch (state) {
            case SYN_SENT:
                if (packet.getType() == Packet.TYPE_SYN_ACK) {
                    handleConnectionResponse(packet);
                }
                break;

            case ESTABLISHED:
                if (packet.getType() == Packet.TYPE_FIN) {
                    // 收到FIN，进入CLOSE_WAIT状态
                    state = State.CLOSE_WAIT;
                    sendACK(packet);
                } else if (packet.getType() == Packet.TYPE_DATA && packet.getFlags() == Packet.FLAG_ACK) {
                    // 处理数据确认
                    updateRTT(packet.getAckNum());
                }
                break;

            case FIN_WAIT1:
                if (packet.getType() == Packet.TYPE_ACK) {
                    state = State.FIN_WAIT2;
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    // 同时关闭的情况
                    state = State.CLOSING;
                    sendACK(packet);
                }
                break;

            case FIN_WAIT2:
                if (packet.getType() == Packet.TYPE_FIN) {
                    sendACK(packet);
                    startTimeWaitTimer();
                }
                break;

            case CLOSING:
                if (packet.getType() == Packet.TYPE_ACK) {
                    startTimeWaitTimer();
                }
                break;

            case LAST_ACK:
                if (packet.getType() == Packet.TYPE_ACK) {
                    state = State.CLOSED;
                    cleanup();
                }
                break;
        }
    }

    private void handleConnectionResponse(Packet packet) throws IOException {
        if (packet.getType() == Packet.TYPE_SYN_ACK) {
            expectedSeqNum = packet.getNextSeqNum();
            nextSeqNum++;

            // 发送 ACK
            Packet ackPacket = Packet.createData(
                socket.getLocalPort(),
                serverPort,
                nextSeqNum,
                expectedSeqNum,
                null,
                WINDOW_SIZE
            );
            sendWithRTO(ackPacket);
            state = State.ESTABLISHED;
        }
    }

    private void handleData(Packet packet) throws IOException {
        if (packet.getSeqNum() == expectedSeqNum) {
            expectedSeqNum++;
            sendACK(packet);
            
            if (packet.getData() != null) {
                String message = new String(packet.getData());
                System.out.println("Received: " + message);
            }
        } else {
            sendACK(packet);
        }
    }

    public void send(byte[] data) throws IOException {
        if (state != State.ESTABLISHED) {
            throw new IllegalStateException("Connection not established");
        }

        Packet packet = Packet.createData(
            socket.getLocalPort(),
            serverPort,
            nextSeqNum,
            expectedSeqNum,
            data,
            WINDOW_SIZE
        );
        sendWithRTO(packet);
        nextSeqNum++;
    }

    public void disconnect() throws IOException {
        if (state == State.ESTABLISHED) {
            sendFIN();
            waitForDisconnection();
        }
    }

    private void sendFIN() throws IOException {
        Packet finPacket = Packet.createFIN(
            localPort,
            serverPort,
            nextSeqNum,
            expectedSeqNum,
            WINDOW_SIZE
        );
        sendWithRTO(finPacket);
        state = State.FIN_WAIT1;
    }

    private void waitForDisconnection() throws IOException {
        while (state != State.CLOSED) {
            try {
                byte[] receiveData = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket.setSoTimeout(INITIAL_TIMEOUT);
                socket.receive(receivePacket);

                // 只使用实际接收到的数据
                byte[] actualData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
                Packet packet = Packet.fromBytes(actualData);
                if (packet != null) {
                    handlePacket(packet);
                }
            } catch (SocketTimeoutException e) {
                // 超时继续等待
            }
        }
    }

    private void sendACK(Packet receivedPacket) throws IOException {
        Packet ackPacket = Packet.createData(
            socket.getLocalPort(),
            serverPort,
            nextSeqNum,
            receivedPacket.getSeqNum() + 1,
            null,
            WINDOW_SIZE
        );
        sendWithRTO(ackPacket);
    }

    private void startTimeWaitTimer() {
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }
        timeWaitTimer = scheduler.schedule(() -> {
            state = State.CLOSED;
            cleanup();
        }, 2 * MSL, TimeUnit.MILLISECONDS);
    }

    private void send(Packet packet) throws IOException {
        byte[] sendData = packet.toBytes();
        DatagramPacket datagramPacket = new DatagramPacket(
            sendData,
            sendData.length,
            serverAddress,
            serverPort
        );
        socket.send(datagramPacket);
    }

    private void cleanup() {
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }
        scheduler.shutdown();
        socket.close();
    }
}
