package com.reliableudp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * TCP状态机实现
 * 处理TCP连接的所有状态和逻辑，不区分客户端和服务端
 */
public class TCPStateMachine {
    // TCP状态
    public enum State {
        CLOSED,         // 初始状态
        LISTEN,         // 服务器等待连接
        SYN_SENT,      // 客户端已发送SYN
        SYN_RECEIVED,  // 服务器已收到SYN并发送SYN+ACK
        ESTABLISHED,   // 连接已建立
        FIN_WAIT_1,    // 主动关闭方发送FIN
        FIN_WAIT_2,    // 主动关闭方收到ACK
        CLOSE_WAIT,    // 被动关闭方收到FIN
        LAST_ACK,      // 被动关闭方发送FIN
        CLOSING,       // 同时关闭
        TIME_WAIT,     // 等待2MSL
        CLOSED_WAIT    // 等待关闭完成
    }

    // 配置参数
    private static final int WINDOW_SIZE = 1024;
    private static final int INITIAL_TIMEOUT = 1000; // 初始RTO为1秒
    private static final int MAX_RETRIES = 5;
    private static final long MSL = 2000; // Maximum Segment Lifetime (2秒)

    // 状态变量
    private State state;
    private final DatagramSocket socket;
    private final InetAddress peerAddress;
    private final int localPort;
    private final int peerPort;
    private final Consumer<byte[]> dataConsumer;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> timeWaitTimer;

    // 接收缓冲区
    private final TCPReceiveBuffer receiveBuffer;
    // 发送缓冲区
    private TCPSendBuffer sendBuffer;

    // 重传相关
    private int RTO = INITIAL_TIMEOUT;
    private static final double ALPHA = 0.125;  // RTT平滑因子
    private static final double BETA = 0.25;   // RTT方差平滑因子
    private double estimatedRTT = 0;
    private double devRTT = 0;

    // 定时器相关
    private final Map<Integer, ScheduledFuture<?>> retransmissionTimers;
    private final Map<Integer, Long> sendTimes;

    /**
     * 创建TCP状态机
     */
    public TCPStateMachine(DatagramSocket socket, InetAddress peerAddress, int peerPort, 
                          Consumer<byte[]> dataConsumer, boolean isServer) {
        this.socket = socket;
        this.peerAddress = peerAddress;
        this.localPort = socket.getLocalPort();
        this.peerPort = peerPort;
        this.dataConsumer = dataConsumer;
        this.state = isServer ? State.LISTEN : State.CLOSED;
        
        // 初始化序列号（可以使用随机数）
        int initialSeqNum = new Random().nextInt(1000);
        this.receiveBuffer = new TCPReceiveBuffer(WINDOW_SIZE, dataConsumer);
        this.sendBuffer = new TCPSendBuffer(initialSeqNum);
        
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.retransmissionTimers = new ConcurrentHashMap<>();
        this.sendTimes = new ConcurrentHashMap<>();
    }

    /**
     * 处理接收到的数据包
     */
    public synchronized void handlePacket(Packet packet) {
        try {
            System.out.println("当前状态: " + state + ", 收到数据包: " + packet);
            System.out.println("接收数据包前的缓冲区状态:");
            System.out.println("  " + sendBuffer);
            System.out.println("  " + receiveBuffer);

            // 首先处理ACK，因为在任何状态下都可能收到ACK
            if (packet.isACK()) {
                handleAck(packet);
            }

            // 然后根据当前状态处理其他类型的包
            switch (state) {
                case CLOSED:
                    handleClosedState(packet);
                    break;
                case LISTEN:
                    handleListenState(packet);
                    break;
                case SYN_SENT:
                    handleSynSentState(packet);
                    break;
                case SYN_RECEIVED:
                    handleSynReceivedState(packet);
                    break;
                case ESTABLISHED:
                    handleEstablishedState(packet);
                    break;
                case FIN_WAIT_1:
                    handleFinWait1State(packet);
                    break;
                case FIN_WAIT_2:
                    handleFinWait2State(packet);
                    break;
                case CLOSE_WAIT:
                    handleCloseWaitState(packet);
                    break;
                case LAST_ACK:
                    handleLastAckState(packet);
                    break;
                case CLOSING:
                    handleClosingState(packet);
                    break;
                case TIME_WAIT:
                    handleTimeWaitState(packet);
                    break;
            }
        } catch (IOException e) {
            System.err.println("处理数据包时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 发送数据
     */
    public void send(byte[] data) throws IOException {
        if (state != State.ESTABLISHED) {
            throw new IOException("连接未建立");
        }

        // 将数据添加到发送缓冲区
        if (!sendBuffer.put(data)) {
            throw new IOException("Send buffer full");
        }
        

        // 尝试发送数据
        trySendData();
    }

    /**
     * 尝试发送数据
     */
    private void trySendData() throws IOException {
        while (true) {
            TCPSendBuffer.SendData sendData = sendBuffer.getNextData();
            if (sendData == null) {
                break;
            }

            // 创建数据包
            Packet dataPacket = Packet.createData(
                localPort,
                peerPort,
                sendData.getSeqNum(),
                receiveBuffer.getRcvNxt(),  // 添加当前期望的序号
                sendData.getData(),
                WINDOW_SIZE,
                receiveBuffer.getRcvWnd()
            );

            // 发送数据包并设置重传
            sendWithRetransmission(dataPacket);
        }
    }

    /**
     * 处理收到的ACK包
     */
    private void handleAck(Packet packet) {
        if (packet.isACK()) {
            // 更新发送缓冲区的确认状态
            sendBuffer.handleAck(packet.getAckNum());
            
            // 更新对方的接收窗口大小
            sendBuffer.updatePeerWindow(packet.getWindowSize());

            // 如果所有数据都已确认，取消重传定时器
            if (sendBuffer.allDataAcked()) {
                cancelRetransmissionTimer();
            }
        }
    }

    /**
     * 发送数据包并设置重传
     */
    private void sendWithRetransmission(Packet packet) throws IOException {
        // 记录发送时间
        sendTimes.put(packet.getSeqNum(), System.currentTimeMillis());
        
        // 保存未确认的包用于重传
        sendBuffer.addUnackedPacket(packet);
        
        // 设置重传定时器
        setRetransmissionTimer(packet.getSeqNum());
        
        // 发送数据包
        sendPacket(packet);

        // 如果是SYN或SYN-ACK包，sndNxt
        if (packet.isSYN()) {
            sendBuffer.updateSndNxt(packet.getSeqNum() + 1);  // SYN占用一个序号
        }
        // 如果是数据包，更新sndNxt
        else if (packet.getData() != null && packet.getData().length > 0) {
            sendBuffer.updateSndNxt(packet.getSeqNum() + packet.getData().length);
        }
        // 如果是FIN包，更新sndNxt
        else if (packet.isFIN()) {
            sendBuffer.updateSndNxt(packet.getSeqNum() + 1);  // FIN占用一个序号
        }

        // 打印缓冲区状态
        System.out.println("发送数据包后的缓冲区状态:");
        System.out.println("  " + sendBuffer);
        System.out.println("  " + receiveBuffer);
    }

    /**
     * 直接发送ACK包，不需要重传
     */
    private void sendAck(int ackNum) throws IOException {
        Packet ack = Packet.createACK(
            localPort,
            peerPort,
            sendBuffer.getSndNxt(),
            ackNum,
            receiveBuffer.getRcvWnd()
        );
        sendPacket(ack);
    }

    /**
     * 发送SYN包
     */
    private void sendSyn() throws IOException {
        // 发送SYN包
        Packet syn = Packet.createSYN(
            localPort,
            peerPort,
            sendBuffer.getSndNxt(),
            receiveBuffer.getRcvWnd()
        );
        sendWithRetransmission(syn);
    }

    /**
     * 发送FIN包
     */
    private void sendFin() throws IOException {
        // 发送FIN包
        Packet fin = Packet.createFIN(
            localPort,
            peerPort,
            sendBuffer.getSndNxt(),
            receiveBuffer.getRcvNxt(),  // 添加确认号
            receiveBuffer.getRcvWnd()
        );
        sendWithRetransmission(fin);
    }

    /**
     * 发送数据包
     */
    private void sendPacket(Packet packet) throws IOException {
        byte[] data = packet.toBytes();
        socket.send(new java.net.DatagramPacket(
            data, data.length, peerAddress, peerPort
        ));
    }

    /**
     * 处理CLOSED状态下收到的数据包
     */
    private void handleClosedState(Packet packet) throws IOException {
        // CLOSED状态只处理主动打开的情况
        if (packet.isSYN()) {
            // 作为客户端，发起连接
            sendSyn();
        }
    }

    /**
     * 处理LISTEN状态下收到的数据包
     */
    private void handleListenState(Packet packet) throws IOException {
        if (packet.isSYN()) {
            // 收到SYN包，进入SYN_RECEIVED状态
            state = State.SYN_RECEIVED;
            
            //更新接收缓存区
            receiveBuffer.setInitialSequenceNumber(packet.getSeqNum());

            // 发送SYN+ACK包
            Packet synAck = Packet.createSYNACK(
                localPort,
                peerPort,
                sendBuffer.getSndNxt(),  // 使用新的初始序列号
                packet.getSeqNum() + 1,  // 确认号应该是收到的序列号加1
                receiveBuffer.getRcvWnd()
            );
            sendWithRetransmission(synAck);
        }

    }

    /**
     * 处理SYN_SENT状态下收到的数据包
     */
    private void handleSynSentState(Packet packet) throws IOException {
        if (packet.isSYN() && packet.isACK()) {
            // 收到SYN+ACK包，进入ESTABLISHED状态
            state = State.ESTABLISHED;

            // 设置接收缓冲区的初始序列号
            receiveBuffer.setInitialSequenceNumber(packet.getSeqNum());

            // 发送ACK包
            sendAck(packet.getSeqNum() + 1);
        }
    }

    /**
     * 处理SYN_RECEIVED状态下收到的数据包
     */
    private void handleSynReceivedState(Packet packet) throws IOException {
        if (packet.isACK()) {
            // 收到ACK，进入ESTABLISHED状态
            state = State.ESTABLISHED;

            // 取消SYN-ACK包的重传
            cancelRetransmissionTimer(sendBuffer.getSndUna());
        } else if (packet.isSYN()) {
            // 收到重复的SYN，重发SYN+ACK
            Packet synAck = Packet.createSYNACK(
                localPort,
                peerPort,
                sendBuffer.getSndUna(),  // 使用初始序列号
                packet.getSeqNum() + 1,  // 确认号应该是收到的序列号加1
                receiveBuffer.getRcvWnd()
            );
            sendWithRetransmission(synAck);
        }
    }

    /**
     * 处理ESTABLISHED状态下收到的数据包
     */
    private void handleEstablishedState(Packet packet) throws IOException {
        if (packet.isFIN()) {
            // 收到FIN包，进入CLOSE_WAIT状态
            state = State.CLOSE_WAIT;

            // 发送ACK确认FIN
            sendAck(packet.getSeqNum() + 1);
        } else if (packet.getType() == Packet.TYPE_DATA) {
            // 处理数据包
            int result = receiveBuffer.receive(packet.getSeqNum(), packet.getData());

            // 发送ACK
            if (result >= 0) {  // 成功接收或重复数据
                sendAck(receiveBuffer.getRcvNxt());
            }
        }
    }

    /**
     * 处理FIN_WAIT1状态下收到的数据包
     */
    private void handleFinWait1State(Packet packet) throws IOException {
        if (packet.isACK() && packet.getAckNum() == sendBuffer.getSndNxt()) {
            // 收到ACK，进入FIN_WAIT2状态
            state = State.FIN_WAIT_2;
        } else if (packet.isFIN()) {
            // 收到FIN，进入CLOSING状态
            state = State.CLOSING;

            // 发送ACK确认FIN
            sendAck(packet.getSeqNum() + 1);
        }
    }

    /**
     * 处理FIN_WAIT2状态下收到的数据包
     */
    private void handleFinWait2State(Packet packet) throws IOException {
        if (packet.isFIN()) {
            // 收到FIN，进入TIME_WAIT状态
            state = State.TIME_WAIT;

            // 发送ACK确认FIN
            sendAck(packet.getSeqNum() + 1);

            // 启动TIME_WAIT定时器
            startTimeWaitTimer();
        }
    }

    /**
     * 处理CLOSE_WAIT状态下收到的数据包
     */
    private void handleCloseWaitState(Packet packet) throws IOException {
        // CLOSE_WAIT状态主要等待应用层关闭连接
        // 可以处理重复的FIN包
        if (packet.isFIN()) {
            // 重发ACK
            sendAck(packet.getSeqNum() + 1);
        }
    }

    /**
     * 处理LAST_ACK状态下收到的数据包
     */
    private void handleLastAckState(Packet packet) throws IOException {
        if (packet.isACK() && packet.getAckNum() == sendBuffer.getSndNxt()) {
            // 收到最后的ACK，进入CLOSED状态
            state = State.CLOSED;
        }
    }

    /**
     * 处理CLOSING状态下收到的数据包
     */
    private void handleClosingState(Packet packet) throws IOException {
        if (packet.isACK() && packet.getAckNum() == sendBuffer.getSndNxt()) {
            // 收到ACK，进入TIME_WAIT状态
            state = State.TIME_WAIT;

            // 启动TIME_WAIT定时器
            startTimeWaitTimer();
        }
    }

    /**
     * 处理TIME_WAIT状态下收到的数据包
     */
    private void handleTimeWaitState(Packet packet) throws IOException {
        if (packet.isFIN()) {
            // 收到重复的FIN，重发ACK
            sendAck(packet.getSeqNum() + 1);

            // 重置TIME_WAIT定时器
            startTimeWaitTimer();
        }
    }

    /**
     * 启动TIME_WAIT定时器
     */
    private void startTimeWaitTimer() {
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }

        timeWaitTimer = scheduler.schedule(() -> {
            state = State.CLOSED;
            cleanup();
        }, 2 * MSL, TimeUnit.MILLISECONDS);
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        // 取消所有定时器
        for (ScheduledFuture<?> timer : retransmissionTimers.values()) {
            timer.cancel(false);
        }
        retransmissionTimers.clear();

        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
            timeWaitTimer = null;
        }

        // 清理数据结构
        sendBuffer.clear();
        sendTimes.clear();
    }

    /**
     * 获取当前状态
     */
    public State getState() {
        return state;
    }

    /**
     * 获取发送缓冲区
     */
    public TCPSendBuffer getSendBuffer() {
        return sendBuffer;
    }

    /**
     * 获取接收缓冲区
     */
    public TCPReceiveBuffer getReceiveBuffer() {
        return receiveBuffer;
    }

    /**
     * 主动建立连接
     */
    public void connect() throws IOException {
        if (state != State.CLOSED) {
            throw new IOException("Connection already exists");
        }

        // 发送SYN包
        sendSyn();
        
        // 更新状态
        state = State.SYN_SENT;
    }

    /**
     * 主动关闭连接
     */
    public void disconnect() throws IOException {
        if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) {
            throw new IOException("状态错误: " + state);
        }

        // 发送FIN包
        sendFin();

        // 更新状态
        state = (state == State.ESTABLISHED) ? State.FIN_WAIT_1 : State.LAST_ACK;
    }

    /**
     * 设置重传定时器
     */
    private void setRetransmissionTimer(int seqNum) {
        // 取消已有的定时器（如果存在）
        cancelRetransmissionTimer(seqNum);

        // 创建新的重传任务
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            try {
                synchronized (this) {
                    Packet packet = sendBuffer.getUnackedPacket(seqNum);
                    if (packet != null) {
                        System.out.println("重传数据包: seqNum=" + seqNum);
                        sendPacket(packet);
                        // 指数退避：下次超时时间加倍
                        RTO *= 2;
                        // 重新设置定时器
                        setRetransmissionTimer(seqNum);
                    }
                }
            } catch (IOException e) {
                System.err.println("重传数据包失败: " + e.getMessage());
                e.printStackTrace();
            }
        }, RTO, TimeUnit.MILLISECONDS);

        retransmissionTimers.put(seqNum, timer);
    }

    /**
     * 取消重传定时器
     */
    private void cancelRetransmissionTimer() {
        for (ScheduledFuture<?> timer : retransmissionTimers.values()) {
            timer.cancel(false);
        }
        retransmissionTimers.clear();
    }

    /**
     * 取消重传定时器
     */
    private void cancelRetransmissionTimer(int seqNum) {
        ScheduledFuture<?> timer = retransmissionTimers.remove(seqNum);
        if (timer != null) {
            timer.cancel(false);
        }
    }
}
