package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ReliableUDPClient {
    private static final int BUFFER_SIZE = 1024;
    private static final long MSL = 2000; // Maximum Segment Lifetime (2 seconds)
    private static final int INIT_TIMEOUT = 1000; // Initial timeout 1 second
    private static final int MAX_TIMEOUT = 8000; // Maximum timeout 8 seconds
    private static final int MAX_RETRIES = 12;      // 最大重传次数
    private static final int RETRANSMISSION_TIMEOUT = 1000; // 1秒超时

    // RTT 相关常量
    private static final int INITIAL_RTO = 1000;    // 初始 RTO 为 1秒
    private static final int MIN_RTO = 200;         // 最小 RTO 为 200ms
    private static final int MAX_RTO = 60000;       // 最大 RTO 为 60秒
    private static final double ALPHA = 0.125;      // SRTT 更新权重
    private static final double BETA = 0.25;        // RTTVAR 更新权重

    private final String serverHost;
    private final int serverPort;
    private DatagramSocket socket;
    private InetAddress serverAddress;
    private State state;
    private final ScheduledExecutorService scheduler;
    private RetransmissionTask currentRetransmissionTask;
    private ScheduledFuture<?> timeWaitTimer;
    private volatile boolean running;
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    private int nextSeqNum;

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

    private class RetransmissionTask {
        private final Packet packet;
        private volatile ScheduledFuture<?> future;
        private volatile int retries;
        private final long sendTime;
        private volatile boolean isRetransmission;
        private volatile boolean isCancelled;

        public RetransmissionTask(Packet packet) {
            this.packet = packet;
            this.retries = 0;
            this.sendTime = System.currentTimeMillis();
            this.isRetransmission = false;
            this.isCancelled = false;
        }

        public synchronized void cancel() {
            if (!isCancelled) {
                isCancelled = true;
                if (future != null) {
                    future.cancel(false);
                }
            }
        }

        public synchronized void scheduleRetransmission() {
            if (isCancelled) {
                return;
            }

            // 使用当前的 RTO 值
            long timeout = calculateRTO();
            
            // 指数退避
            if (isRetransmission) {
                timeout *= (1 << Math.min(retries, 5));
            }
            
            // 确保超时在合理范围内
            timeout = Math.min(Math.max(timeout, MIN_RTO), MAX_RTO);
            
            System.out.println("计划重传数据包，序号: " + packet.getSeqNum() + 
                             "，超时: " + timeout + "ms，重试次数: " + retries);
            
            future = scheduler.schedule(() -> {
                if (!isCancelled && retries < MAX_RETRIES) {
                    try {
                        synchronized (this) {
                            if (!isCancelled) {
                                retries++;
                                isRetransmission = true;
                                sendPacket(packet, serverAddress, serverPort);
                                System.out.println("重传数据包，序号: " + packet.getSeqNum() + 
                                                 "，第 " + retries + " 次重试");
                                scheduleRetransmission();
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if (!isCancelled) {
                    System.out.println("数据包重传次数超过限制，序号: " + packet.getSeqNum());
                    connectionLost();
                }
            }, timeout, TimeUnit.MILLISECONDS);
        }
    }

    // RTT 估计值
    private double estimatedRTT = INITIAL_RTO;  // SRTT (Smoothed RTT)
    private double devRTT = INITIAL_RTO / 2.0;  // RTTVAR (RTT Variation)
    private final Map<Integer, RetransmissionTask> pendingPackets = new ConcurrentHashMap<>();

    public ReliableUDPClient(String serverHost, int serverPort) throws UnknownHostException {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverAddress = InetAddress.getByName(serverHost);
        this.state = State.CLOSED;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.nextSeqNum = 0;
    }

    public void connect() throws IOException {
        if (state != State.CLOSED) {
            throw new IOException("已经连接或正在连接");
        }

        // 初始化连接
        socket = new DatagramSocket();
        socket.setSoTimeout(100);  // 设置超时以便及时处理所有类型的包
        running = true;
        state = State.SYN_SENT;

        // 启动接收线程
        startReceiveThread();

        // 发送SYN包
        System.out.println("发送SYN包");
        Packet packet = new Packet(Packet.TYPE_SYN, 0, null, false);
        sendWithRetransmission(packet);

        // 等待连接建立
        try {
            long startTime = System.currentTimeMillis();
            while (state != State.ESTABLISHED && System.currentTimeMillis() - startTime < 5000) {
                Thread.sleep(100);
                if (!running) {
                    throw new IOException("连接失败");
                }
            }
            if (state != State.ESTABLISHED) {
                cleanup();
                throw new IOException("连接超时");
            }
            System.out.println("连接已建立");
        } catch (InterruptedException e) {
            cleanup();
            Thread.currentThread().interrupt();
            throw new IOException("连接被中断");
        }
    }

    private void startReceiveThread() {
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
                    Packet ackPacket = new Packet(Packet.TYPE_ACK, 0, null, false);
                    sendWithRetransmission(ackPacket);
                    state = State.ESTABLISHED;
                    System.out.println("连接已建立");
                }
                break;

            case ESTABLISHED:
                if (packet.getType() == Packet.TYPE_ACK) {
                    handleAck(packet);
                } else if (packet.getType() == Packet.TYPE_WINDOW_UPDATE) {
                    handleWindowUpdate(packet);
                }
                break;

            case FIN_WAIT1:
                if (packet.getType() == Packet.TYPE_ACK) {
                    System.out.println("收到FIN的ACK");
                    RetransmissionTask task = pendingPackets.remove(packet.getSeqNum());
                    if (task != null) {
                        task.cancel();
                    }
                    state = State.FIN_WAIT2;
                } else if (packet.getType() == Packet.TYPE_FIN) {
                    state = State.CLOSING;
                    Packet ackPacket = new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false);
                    sendWithRetransmission(ackPacket);
                }
                break;

            case FIN_WAIT2:
                if (packet.getType() == Packet.TYPE_FIN) {
                    System.out.println("收到FIN，发送ACK");
                    Packet ackPacket = new Packet(Packet.TYPE_ACK, packet.getSeqNum(), null, false);
                    sendWithRetransmission(ackPacket);
                    state = State.TIME_WAIT;
                    scheduleTimeWait();
                }
                break;

            case CLOSING:
                if (packet.getType() == Packet.TYPE_ACK) {
                    RetransmissionTask task = pendingPackets.remove(packet.getSeqNum());
                    if (task != null) {
                        task.cancel();
                    }
                    state = State.TIME_WAIT;
                    scheduleTimeWait();
                }
                break;

            case LAST_ACK:
                if (packet.getType() == Packet.TYPE_ACK) {
                    System.out.println("收到最后的ACK，关闭连接");
                    RetransmissionTask task = pendingPackets.remove(packet.getSeqNum());
                    if (task != null) {
                        task.cancel();
                    }
                    state = State.CLOSED;
                    cleanup();
                }
                break;
        }
    }

    private void handleAck(Packet packet) {
        int ackNum = packet.getSeqNum();
        
        // 找出所有需要取消的重传任务
        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, RetransmissionTask> entry : pendingPackets.entrySet()) {
            if (entry.getKey() <= ackNum) {
                toRemove.add(entry.getKey());
            }
        }
        
        // 取消并移除重传任务
        for (Integer seqNum : toRemove) {
            RetransmissionTask task = pendingPackets.remove(seqNum);
            if (task != null) {
                task.cancel();
                
                // 更新 RTT 估计值（只对非重传包）
                if (!task.isRetransmission) {
                    updateRTT(seqNum, task.sendTime);
                }
                
                System.out.println("确认数据包，序号: " + seqNum + "，当前 RTO: " + 
                                 calculateRTO() + "ms");
            }
        }
        
        // 更新接收窗口大小
        updateReceiverWindow(packet.getWindowSize());
    }

    private void handleWindowUpdate(Packet packet) {
        updateWindow(packet.getWindowSize());
        synchronized (windowLock) {
            windowUpdateReceived = true;
            windowLock.notifyAll();
        }
        System.out.println("收到窗口更新，新窗口大小: " + packet.getWindowSize());
    }

    private void updateWindow(int newWindow) {
        if (newWindow >= 0) {
            synchronized (windowLock) {
                receiverWindow = newWindow;
                if (receiverWindow > 0) {
                    windowLock.notifyAll();
                }
            }
        }
    }

    private void waitForWindow() throws InterruptedException {
        synchronized (windowLock) {
            while (receiverWindow <= 0 && !windowUpdateReceived) {
                System.out.println("等待接收窗口...");
                windowLock.wait(calculateRTO());
            }
            windowUpdateReceived = false;
        }
    }

    /**
     * 发送数据包并设置重传定时器
     */
    private void sendWithRetransmission(Packet packet) throws IOException {
        // 发送数据包
        sendPacket(packet, serverAddress, serverPort);
        System.out.println("发送数据包，序号: " + packet.getSeqNum());

        // 创建并保存重传任务
        RetransmissionTask task = new RetransmissionTask(packet);
        pendingPackets.put(packet.getSeqNum(), task);
        task.scheduleRetransmission();
    }

    /**
     * 更新 RTT 估计值（Karn/Partridge 算法）
     */
    private void updateRTT(int seqNum, long sendTime) {
        RetransmissionTask task = pendingPackets.get(seqNum);
        if (task != null && !task.isRetransmission) {
            // 只对非重传包更新 RTT
            long sampleRTT = System.currentTimeMillis() - sendTime;
            
            // 更新 SRTT: SRTT = (1-α)SRTT + α×SampleRTT
            estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
            
            // 更新 RTTVAR: RTTVAR = (1-β)RTTVAR + β×|SampleRTT-SRTT|
            devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
            
            System.out.println("更新 RTT - 样本: " + sampleRTT + "ms, SRTT: " + 
                             String.format("%.2f", estimatedRTT) + "ms, RTTVAR: " + 
                             String.format("%.2f", devRTT) + "ms");
        }
    }

    /**
     * 计算 RTO (Retransmission Timeout)
     * RTO = SRTT + 4×RTTVAR
     */
    private long calculateRTO() {
        return (long)(estimatedRTT + 4 * devRTT);
    }

    /**
     * 发送数据
     */
    public void send(String message) throws IOException, InterruptedException {
        if (state != State.ESTABLISHED) {
            throw new IOException("连接未建立");
        }

        byte[] data = message.getBytes();
        Packet packet = new Packet(Packet.TYPE_DATA, nextSeqNum++, data, true);
        sendWithRetransmission(packet);
    }

    /**
     * 发送乱序数据
     */
    public void sendOutOfOrder(String[] messages, int[] delays) throws IOException, InterruptedException {
        if (messages.length != delays.length) {
            throw new IllegalArgumentException("消息数量必须等于延迟数量");
        }

        // 创建发送任务
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            final int index = i;
            final byte[] data = messages[i].getBytes();
            boolean isLast = (i == messages.length - 1);

            futures.add(scheduler.schedule(() -> {
                try {
                    Packet packet = new Packet(Packet.TYPE_DATA, index, data, isLast);
                    sendWithRetransmission(packet);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, delays[i], TimeUnit.MILLISECONDS));
        }

        // 等待所有任务完成
        for (ScheduledFuture<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    public void disconnect() throws IOException {
        if (state == State.ESTABLISHED) {
            System.out.println("发送FIN包");
            state = State.FIN_WAIT1;
            Packet packet = new Packet(Packet.TYPE_FIN, 0, null, false);
            sendWithRetransmission(packet);
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
        for (RetransmissionTask task : pendingPackets.values()) {
            task.cancel();
        }
        pendingPackets.clear();
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

    private volatile int receiverWindow = 16;  // 初始接收窗口大小
    private final Object windowLock = new Object();
    private volatile boolean windowUpdateReceived = false;

    /**
     * 更新接收窗口大小
     */
    private synchronized void updateReceiverWindow(int newSize) {
        synchronized (windowLock) {
            if (newSize != receiverWindow) {
                System.out.println("更新接收窗口大小: " + newSize);
                receiverWindow = newSize;
                windowUpdateReceived = true;
                windowLock.notifyAll();
            }
        }
    }

    private void connectionLost() {
        System.out.println("检测到连接丢失");
        cleanup();
        // 通知上层应用连接已断开
    }
}
