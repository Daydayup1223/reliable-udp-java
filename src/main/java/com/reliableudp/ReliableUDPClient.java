package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.Random;
import java.util.concurrent.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

public class ReliableUDPClient {
    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final int serverPort;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;
    private State state;
    private int sendNextSeqNum;  // 下一个要发送的序列号
    private int recvExpectedSeqNum;  // 期望收到的序列号
    private int sendUnackedSeqNum;  // 最早未确认的序列号
    private static final int WINDOW_SIZE = 5;
    private static final int INITIAL_TIMEOUT = 1000;
    private static final int MAX_RETRIES = 5;
    private ScheduledFuture<?> timeWaitTimer;
    private static final long MSL = 2000;
    private int localPort;
    private double estimatedRTT = 0;
    private double devRTT = 0;
    private int RTO = INITIAL_TIMEOUT;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;
    private Map<Integer, Long> sendTimes;
    private Map<Integer, ScheduledFuture<?>> retransmissionTimers;
    private Map<Integer, Packet> unackedPackets;  // 未确认的数据包
    private final TreeMap<Integer, byte[]> receiveBuffer;  // 接收缓冲区
    private static final int RECEIVE_WINDOW_SIZE = 1024;  // 接收窗口大小

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
        this.unackedPackets = new ConcurrentHashMap<>();
        this.receiveBuffer = new TreeMap<>();
        this.sendNextSeqNum = (int) (Math.random() * Integer.MAX_VALUE);  // 随机初始序列号
        this.sendUnackedSeqNum = this.sendNextSeqNum;
        this.recvExpectedSeqNum = 0;
    }

    private synchronized void addToReceiveBuffer(int seqNum, byte[] data) {
        // 只有在接收窗口内的数据包才会被缓存
        if (seqNum >= recvExpectedSeqNum && 
            seqNum < recvExpectedSeqNum + RECEIVE_WINDOW_SIZE) {
            receiveBuffer.put(seqNum, data);
        }
    }

    private synchronized List<byte[]> processReceiveBuffer() {
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

    private synchronized int getReceiveWindowSize() {
        // 计算当前可用的接收窗口大小
        int bufferedDataSize = 0;
        for (byte[] data : receiveBuffer.values()) {
            bufferedDataSize += data.length;
        }
        return Math.max(0, RECEIVE_WINDOW_SIZE - bufferedDataSize);
    }

    private void handleData(Packet packet) throws IOException {
        // 处理接收到的数据
        if (packet.getData() != null) {
            // 检查序列号是否在接收窗口范围内
            if (packet.getSeqNum() >= recvExpectedSeqNum && 
                packet.getSeqNum() < recvExpectedSeqNum + RECEIVE_WINDOW_SIZE) {
                
                // 将数据添加到接收缓冲区
                addToReceiveBuffer(packet.getSeqNum(), packet.getData());
                
                // 处理缓冲区中的有序数据
                List<byte[]> orderedData = processReceiveBuffer();
                for (byte[] data : orderedData) {
                    String message = new String(data);
                    System.out.println("Received in order: " + message);
                }
            }
        }

        // 处理ACK
        if (packet.isACK()) {
            int ackNum = packet.getAckNum();
            // 确认数据包
            for (Iterator<Map.Entry<Integer, Packet>> it = unackedPackets.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Integer, Packet> entry = it.next();
                if (entry.getKey() < ackNum) {
                    it.remove();
                    // 取消对应的重传定时器
                    cancelRetransmissionTimer(entry.getKey());
                }
            }
            // 更新RTT
            updateRTT(ackNum);
        }

        // 发送ACK（可以捎带数据）
        sendACK(packet);
    }

    public void send(byte[] data) throws IOException {
        if (state != State.ESTABLISHED) {
            throw new IOException("Connection not established");
        }

        Packet dataPacket = Packet.createData(
            localPort,
            serverPort,
            sendNextSeqNum,
            recvExpectedSeqNum,  // 捎带ACK
            data,
            getReceiveWindowSize()  // 通告当前可用的接收窗口大小
        );

        // 更新发送序列号
        sendNextSeqNum += data.length;
        
        // 记录未确认的包
        unackedPackets.put(sendNextSeqNum, dataPacket);
        
        // 发送数据包
        sendWithRTO(dataPacket);
    }

    // 乱序发送数据
    public void sendOutOfOrder(byte[][] dataChunks, long[] delays) throws IOException {
        if (state != State.ESTABLISHED) {
            throw new IOException("Connection not established");
        }

        if (dataChunks.length != delays.length) {
            throw new IllegalArgumentException("Data chunks and delays arrays must have the same length");
        }

        // 创建发送线程
        Thread[] sendThreads = new Thread[dataChunks.length];
        for (int i = 0; i < dataChunks.length; i++) {
            final int index = i;
            final byte[] data = dataChunks[index];
            final long delay = delays[index];

            sendThreads[i] = new Thread(() -> {
                try {
                    // 等待指定的延迟时间
                    Thread.sleep(delay);

                    // 创建数据包
                    Packet dataPacket = Packet.createData(
                        localPort,
                        serverPort,
                        sendNextSeqNum + calculateOffset(index, dataChunks),
                        recvExpectedSeqNum,
                        data,
                        getReceiveWindowSize()
                    );

                    // 记录未确认的包
                    synchronized (this) {
                        unackedPackets.put(sendNextSeqNum + calculateOffset(index, dataChunks), dataPacket);
                    }

                    // 发送数据包
                    sendWithRTO(dataPacket);
                    System.out.println("Sent chunk " + index + " with delay " + delay + "ms");

                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        // 启动所有发送线程
        for (Thread thread : sendThreads) {
            thread.start();
        }

        // 等待所有线程完成
        for (Thread thread : sendThreads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while sending out of order packets", e);
            }
        }

        // 更新发送序列号
        int totalLength = 0;
        for (byte[] chunk : dataChunks) {
            totalLength += chunk.length;
        }
        sendNextSeqNum += totalLength;
    }

    // 计算每个数据块的序列号偏移
    private int calculateOffset(int index, byte[][] dataChunks) {
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset += dataChunks[i].length;
        }
        return offset;
    }

    private void sendACK(Packet receivedPacket) throws IOException {
        // 计算ACK号：序列号 + 数据长度
        int dataLength = receivedPacket.getData() != null ? receivedPacket.getData().length : 0;
        if (receivedPacket.isSYN() || receivedPacket.isFIN()) {
            dataLength = 1;
        }

        Packet ackPacket = Packet.createData(
            localPort,
            serverPort,
            sendNextSeqNum,  // 使用我们自己的序列号
            receivedPacket.getSeqNum() + dataLength,  // 确认收到的数据
            null,
            getReceiveWindowSize()  // 通告当前可用的接收窗口大小
        );
        send(ackPacket);
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
            throw new IllegalStateException("Already connected or connecting");
        }

        // 发送SYN
        state = State.SYN_SENT;
        Packet synPacket = Packet.createSYN(
            socket.getLocalPort(),
            serverPort,
            sendNextSeqNum,
            WINDOW_SIZE
        );
        sendWithRTO(synPacket);

        // 等待连接建立
        waitForConnection();
    }

    private void waitForConnection() throws IOException {
        long startTime = System.currentTimeMillis();
        long timeout = 5000; // 5秒超时

        while (state != State.ESTABLISHED && System.currentTimeMillis() - startTime < timeout) {
            try {
                byte[] receiveData = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket.setSoTimeout(1000); // 1秒超时
                socket.receive(receivePacket);

                byte[] actualData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
                Packet packet = Packet.fromBytes(actualData);
                if (packet != null) {
                    handlePacket(packet);
                }
            } catch (SocketTimeoutException e) {
                // 超时继续等待
                continue;
            }
        }

        if (state != State.ESTABLISHED) {
            state = State.CLOSED;
            throw new IOException("Connection establishment failed");
        }
    }

    private void handlePacket(Packet packet) throws IOException {
        System.out.println("Client received packet: type=" + packet.getType() + 
                         ", flags=" + packet.getFlags() + 
                         ", seqNum=" + packet.getSeqNum() + 
                         ", ackNum=" + packet.getAckNum());

        switch (state) {
            case SYN_SENT:
                if (packet.isSYN() && packet.isACK()) {
                    handleConnectionResponse(packet);
                }
                break;

            case ESTABLISHED:
                if (packet.getType() == Packet.TYPE_FIN) {
                    handleFIN(packet);
                } else if (packet.getType() == Packet.TYPE_DATA) {
                    handleData(packet);
                }
                break;

            case FIN_WAIT1:
                if (packet.isACK()) {
                    state = State.FIN_WAIT2;
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    state = State.CLOSING;
                    sendACK(packet);
                }
                break;

            case FIN_WAIT2:
                if (packet.getType() == Packet.TYPE_FIN) {
                    sendACK(packet);
                    state = State.TIME_WAIT;
                    startTimeWaitTimer();
                }
                break;

            case CLOSING:
                if (packet.isACK()) {
                    state = State.TIME_WAIT;
                    startTimeWaitTimer();
                }
                break;
        }
    }

    private void handleConnectionResponse(Packet packet) throws IOException {
        if (packet.isSYN() && packet.isACK()) {
            recvExpectedSeqNum = packet.getSeqNum() + 1;
            sendNextSeqNum++;

            // 发送 ACK
            Packet ackPacket = Packet.createData(
                socket.getLocalPort(),
                serverPort,
                sendNextSeqNum,
                recvExpectedSeqNum,
                null,
                WINDOW_SIZE
            );
            send(ackPacket);
            state = State.ESTABLISHED;
            System.out.println("Connection established");
        }
    }

    private void handleFIN(Packet packet) throws IOException {
        switch (state) {
            case ESTABLISHED:
                // 收到FIN，进入CLOSE_WAIT状态
                state = State.CLOSE_WAIT;
                // 发送ACK
                Packet ackPacket = Packet.createData(
                    socket.getLocalPort(),
                    serverPort,
                    sendNextSeqNum,
                    recvExpectedSeqNum,
                    null,
                    WINDOW_SIZE
                );
                send(ackPacket);
                break;
        }
    }

    private void sendFIN() throws IOException {
        Packet finPacket = Packet.createFIN(
            localPort,
            serverPort,
            sendNextSeqNum,
            recvExpectedSeqNum,
            WINDOW_SIZE
        );
        sendWithRTO(finPacket);
        state = State.FIN_WAIT1;
    }

    public void disconnect() throws IOException {
        if (state == State.ESTABLISHED) {
            sendFIN();
            waitForDisconnection();
        }
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

    private void startTimeWaitTimer() {
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }
        timeWaitTimer = scheduler.schedule(() -> {
            state = State.CLOSED;
            cleanup();
        }, 2 * MSL, TimeUnit.MILLISECONDS);
    }

    private void cleanup() {
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }
        scheduler.shutdown();
        socket.close();
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
}
