package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;

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

    private volatile int receiverWindow = 16;  // 初始接收窗口大小
    private final Object windowLock = new Object();
    private volatile boolean windowUpdateReceived = false;

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
        startRetransmission(new Packet(Packet.TYPE_SYN, 0, null, false), serverAddress, serverPort);

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
                    stopRetransmission();
                    try {
                        sendPacket(new Packet(Packet.TYPE_ACK, 0, null, false), serverAddress, serverPort);
                        state = State.ESTABLISHED;
                        System.out.println("连接已建立");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
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

    private void handleAck(Packet packet) {
        stopRetransmission();
        updateWindow(packet.getWindowSize());
        System.out.println("收到ACK，序号: " + packet.getSeqNum() + "，窗口大小: " + packet.getWindowSize());
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
            while (receiverWindow <= 0) {
                System.out.println("等待接收窗口...");
                windowLock.wait(1000);  // 最多等待1秒
                if (!running) {
                    throw new InterruptedException("连接已关闭");
                }
            }
        }
    }

    public void send(String message) throws IOException, InterruptedException {
        if (state != State.ESTABLISHED) {
            throw new IOException("连接未建立");
        }

        byte[] data = message.getBytes();
        waitForWindow();
        synchronized (windowLock) {
            if (receiverWindow > 0) {
                receiverWindow--;
                Packet packet = new Packet(Packet.TYPE_DATA, nextSeqNum++, data, true);
                sendPacket(packet, serverAddress, serverPort);
                System.out.println("发送数据包，序号: " + (nextSeqNum - 1) + "，剩余窗口: " + receiverWindow);
            }
        }
    }

    /**
     * 模拟乱序发送数据包
     * @param messages 要发送的消息列表
     * @param delays 每个消息的延迟时间（毫秒）
     * @throws IOException 如果发送失败
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
                    // 等待有可用窗口
                    waitForWindow();
                    
                    // 发送数据包
                    synchronized (windowLock) {
                        if (receiverWindow > 0) {
                            receiverWindow--;
                            Packet packet = new Packet(Packet.TYPE_DATA, index, data, isLast);
                            sendPacket(packet, serverAddress, serverPort);
                            System.out.println("发送数据包，序号: " + index + "，剩余窗口: " + receiverWindow);
                        }
                    }
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
