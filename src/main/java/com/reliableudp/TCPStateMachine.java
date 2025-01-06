package com.reliableudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.reliableudp.TCPSendBuffer.SendData;

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
    private static final int INITIAL_TIMEOUT = 2000;  // 初始RTO设为2秒
    private static final int MAX_RETRIES = 5;
    private static final long MSL = 2000; // Maximum Segment Lifetime (2秒)
    private static final int MAX_DELAY = 3000;       // 最大延迟3秒

    // 状态变量
    private final DatagramSocket socket;
    private final InetAddress peerAddress;
    private final int peerPort;
    private final int localPort;
    private final TCPSendBuffer sendBuffer;
    private final TCPReceiveBuffer receiveBuffer;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, ScheduledFuture<?>> retransmissionTimers;
    private final Map<Integer, Long> sendTimes;
    private State state;
    private int RTO = 1000;  // 初始重传超时时间为1秒
    private ScheduledFuture<?> timeWaitTimer;

    // 重传相关
    private static final double ALPHA = 0.125;  // RTT平滑因子
    private static final double BETA = 0.25;   // RTT方差平滑因子
    private double estimatedRTT = 0;
    private double devRTT = 0;

    /**
     * 创建TCP状态机
     */
    public TCPStateMachine(DatagramSocket socket, InetAddress peerAddress, int peerPort, int localPort) {
        this.socket = socket;
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.localPort = localPort;
        
        // 初始化接收缓冲区
        this.receiveBuffer = new TCPReceiveBuffer(1024, this::handleData);  // 添加窗口大小和数据处理回调
        
        // 初始化发送缓冲区
        this.sendBuffer = new TCPSendBuffer(this::sendData);
        
        // 初始化状态和定时器
        this.state = State.CLOSED;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.retransmissionTimers = new ConcurrentHashMap<>();
        this.sendTimes = new ConcurrentHashMap<>();
    }


    public void setState(State state) {
        this.state = state;
    }

    /**
     * 处理接收到的数据包
     */
    public synchronized void handlePacket(Packet packet) {
        try {
            System.out.println("\n");
            System.out.println("================================");
            System.out.println("接收数据包前状态: " + state + ", 收到数据包: " + packet);
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

        System.out.println("接收数据包后状态: " + state + ", 收到数据包: " + packet);
        System.out.println("接收数据包后的缓冲区状态:");
        System.out.println("  " + sendBuffer);
        System.out.println("  " + receiveBuffer);
        System.out.println("================================");
        System.out.println("\n");
    }

    /**
     * 发送数据
     */
    public void send(byte[] data, boolean push) throws IOException {
        if (state != State.ESTABLISHED) {
            throw new IOException("Connection not established");
        }
        sendBuffer.writeToBuffer(data, push);
    }

    /**
     * 关闭连接
     */
    public void close() throws IOException {
        if (state == State.ESTABLISHED) {
            sendFin();
            state = State.FIN_WAIT_1;
        }
    }


    /**
     * 发送数据包，并处理重传
     * @param packet 要发送的数据包
     * @param isRetransmission 是否是重传
     */
    private void sendWithRetransmission(Packet packet, boolean isRetransmission) {

        try {
            // 序列化并发送数据包
            byte[] data = packet.toBytes();
            DatagramPacket datagramPacket = new DatagramPacket(
                data, 
                data.length, 
                peerAddress, 
                peerPort
            );
            socket.send(datagramPacket);

            // 如果不是重传，则启动重传定时器
            if (!isRetransmission) {
                startRetransmissionTimer(packet);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 发送确认包
     */
    private void sendAck(int ackNum) {
        Packet ack = Packet.createAck(
            localPort,
            peerPort,
            sendBuffer.getSndNxt(),
            ackNum,
            receiveBuffer.getRcvWnd()
        );
        sendWithRetransmission(ack, true);
    }

    /**
     * 发送SYN包
     */
    private void sendSyn() throws IOException {
        if (state == State.CLOSED) {
            Packet synPacket = Packet.createSyn(localPort, peerPort, sendBuffer.getSndUna(), receiveBuffer.getRcvWnd());
            sendBuffer.updateSndNxt(synPacket.getSeqNum() + 1);
            sendWithRetransmission(synPacket, false);
            state = State.SYN_SENT;
        }
    }

    private void sendData(Boolean force) {
        if (state == State.ESTABLISHED) {
            SendData nextData = sendBuffer.getNextData();
            if (nextData != null) {
                Packet dataPacket = Packet.createData(
                    localPort, 
                    peerPort, 
                    nextData.getSeqNum(), 
                    receiveBuffer.getRcvNxt(),
                    nextData.getData(),
                    sendBuffer.getSnd_wnd(), 
                    force || nextData.isForce()  // 使用force参数或SendData中的force标志
                );
                sendWithRetransmission(dataPacket, false);
            }
        }
    }

    /**
     * 发送FIN包
     */
    private void sendFin() {
        Packet fin = Packet.createFin(
            localPort,
            peerPort,
            sendBuffer.getSndNxt(),
            receiveBuffer.getRcvNxt(),
            receiveBuffer.getRcvWnd()
        );
        sendBuffer.updateSndNxt(fin.getSeqNum() + 1);
        sendWithRetransmission(fin, false);
        state = State.FIN_WAIT_1;
    }

    /**
     * 发送SYN-ACK包
     */
    private void sendSynAck(Packet packet) {
        Packet synAck = Packet.createSynAck(
            localPort,
            peerPort,
            sendBuffer.getSndNxt(),
            packet.getSeqNum() + 1,
            receiveBuffer.getRcvWnd()
        );
        sendBuffer.updateSndNxt(synAck.getSeqNum() + 1);
        sendWithRetransmission(synAck, false);
    }

    /**
     * 处理收到的ACK包
     */
    private void handleAck(Packet packet) {
        if (packet.isACK()) {
            System.out.println("收到ACK包: ackNum=" + packet.getAckNum() + ", windowSize=" + packet.getWindowSize());
            
            // 更新发送缓冲区的确认状态
            sendBuffer.handleAck(packet.getAckNum());
            
            // 更新对方的接收窗口大小
            sendBuffer.updateReceiveWindow(packet.getWindowSize());

            // 如果所有数据都已确认，取消重传定时器
            if (sendBuffer.allDataAcked()) {
                System.out.println("所有数据已确认，取消重传定时器");
                cancelRetransmissionTimer();
            } else {
                // 取消已确认数据的重传定时器
                cancelRetransmissionTimer(packet.getAckNum() - 1);
            }
            
            // 更新RTT和RTO
            Long sendTime = sendTimes.remove(packet.getAckNum() - 1);
            if (sendTime != null) {
                long sampleRTT = System.currentTimeMillis() - sendTime;
                if (estimatedRTT == 0) {
                    estimatedRTT = sampleRTT;
                    devRTT = sampleRTT / 2;
                } else {
                    estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
                    devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
                }
                RTO = (int) (estimatedRTT + 4 * devRTT);
                System.out.println("更新RTO: sampleRTT=" + sampleRTT + "ms, estimatedRTT=" + estimatedRTT + "ms, devRTT=" + devRTT + "ms, newRTO=" + RTO + "ms");
            }
        }
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
            sendSynAck(packet);
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
            sendSynAck(packet);
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
     *

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
     * 处理收到的数据
     */
    private void handleData(byte[] data) {
        // 这里可以添加数据处理逻辑
        System.out.println("收到数据: " + new String(data));
    }

    /**
     * 发起连接
     */
    public void connect() throws IOException {
        if (state == State.CLOSED) {
            sendSyn();
        }
    }

    /**
     * 检查连接状态
     */
    public boolean isConnected() {
        return state == State.ESTABLISHED;
    }

    /**
     * 设置重传定时器
     */
    private void startRetransmissionTimer(Packet packet) {
        // 取消已有的定时器（如果存在）
        cancelRetransmissionTimer(packet.getSeqNum());

        // 创建新的重传任务
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            try {
                synchronized (this) {
                    System.out.println("开始重传数据包: seqNum=" + packet.getSeqNum() + ", RTO=" + RTO + "ms");
                    byte[] data = packet.toBytes();
                    socket.send(new java.net.DatagramPacket(
                        data, data.length, peerAddress, peerPort
                    ));
                    // 指数退避：下次超时时间加倍
                    RTO *= 2;
                    System.out.println("重传完成，新的RTO=" + RTO + "ms");
                    startRetransmissionTimer(packet);
                }
            } catch (Exception e) {
                System.err.println("重传数据包失败: " + e.getMessage());
                e.printStackTrace();
            }
        }, RTO, TimeUnit.MILLISECONDS);

        System.out.println("设置重传定时器: seqNum=" + packet.getSeqNum() + ", RTO=" + RTO + "ms");
        retransmissionTimers.put(packet.getSeqNum(), timer);
    }

    /**
     * 取消重传定时器
     */
    private void cancelRetransmissionTimer() {
        System.out.println("取消所有重传定时器，数量: " + retransmissionTimers.size());
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
            System.out.println("取消重传定时器: seqNum=" + seqNum);
            timer.cancel(false);
        }
    }
}
