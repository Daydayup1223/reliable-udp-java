package com.reliableudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.reliableudp.TCPConnectionManager.BaseConnection;
import com.reliableudp.TCPConnectionManager.RequestSock;
import com.reliableudp.TCPConnectionManager.TCPConnection;
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

    private int RTO = 1000;  // 初始重传超时时间为1秒

    // 重传相关
    private static final double ALPHA = 0.125;  // RTT平滑因子
    private static final double BETA = 0.25;   // RTT方差平滑因子
    private double estimatedRTT = 0;
    private double devRTT = 0;

    /**
     * 处理接收到的数据包
     */
    public synchronized void handlePacket(Packet packet, BaseConnection connection) {
        try {
            System.out.println("\n");
            System.out.println("================================");

            // 对于三次握手阶段的包（SYN, SYN-ACK, 第一个ACK），使用RequestSock处理
            if (connection instanceof RequestSock) {
                RequestSock requestSock = (TCPConnectionManager.RequestSock)connection;
                switch (requestSock.getState()) {
                    case LISTEN:
                        handleListenState(requestSock, packet);
                        break;
                    case SYN_SENT:
                        handleSynSentState(requestSock, packet);
                        break;
                    case SYN_RECEIVED:
                        handleSynReceivedState(requestSock, packet);
                        break;
                    default:
                        System.err.println("未知状态: " + requestSock.getState());
                }
                return;
            }

            // 对于已建立连接的包，使用TCPConnection处理
            TCPConnection tcpConnection = (TCPConnection)connection;
            System.out.println("接收数据包前状态: " + tcpConnection.getState() + ", 收到数据包: " + packet);
            System.out.println("接收数据包前的缓冲区状态:");
            System.out.println("  " + tcpConnection.getSendBuffer());
            System.out.println("  " + tcpConnection.getReceiveBuffer());

            // 首先处理ACK，因为在任何状态下都可能收到ACK
            if (packet.isACK()) {
                handleAck(tcpConnection, packet);
            }

            // 然后根据当前状态处理其他类型的包
            State state = tcpConnection.getState();
            switch (state) {
                case ESTABLISHED:
                    handleEstablishedState(tcpConnection, packet);
                    break;
                case FIN_WAIT_1:
                    handleFinWait1State(tcpConnection, packet);
                    break;
                case FIN_WAIT_2:
                    handleFinWait2State(tcpConnection, packet);
                    break;
                case CLOSE_WAIT:
                    handleCloseWaitState(tcpConnection, packet);
                    break;
                case LAST_ACK:
                    handleLastAckState(tcpConnection, packet);
                    break;
                case CLOSING:
                    handleClosingState(tcpConnection, packet);
                    break;
                case TIME_WAIT:
                    handleTimeWaitState(tcpConnection, packet);
                    break;
                default:
                    System.err.println("未知状态: " + state);
            }

        } catch (Exception e) {
            System.err.println("处理数据包时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 发送SYN-ACK包
     */
    private void sendSynAck(TCPConnectionManager.RequestSock requestSock, Packet packet) {
        // 创建并发送SYN-ACK包
        Packet synAckPacket = Packet.createSynAck(
            requestSock.getSourcePort(),
            requestSock.getPeerPort(),
            requestSock.getSndSeq(),
            requestSock.getRcvSeq(),
            WINDOW_SIZE
        );
        // 发送SYN-ACK包
        try {
            byte[] data = synAckPacket.toBytes();
            DatagramPacket datagramPacket = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName(requestSock.getPeerAddress()),
                requestSock.getPeerPort()
            );
            requestSock.getSocket().send(datagramPacket);
        } catch (IOException e) {
            System.err.println("发送SYN-ACK包失败: " + e.getMessage());
        }
    }

    /**
     * 发送数据
     */
    public void send(TCPConnection connection, byte[] data, boolean push) throws IOException {
        if (connection.getState() != State.ESTABLISHED) {
            throw new IOException("Connection not established");
        }
        TCPSendBuffer sendBuffer = connection.getSendBuffer();
        sendBuffer.writeToBuffer(data, push);
    }

    /**
     * 关闭连接
     */
    public void close(TCPConnection connection) throws IOException {
        if (connection.getState() == State.ESTABLISHED) {
            sendFin(connection);
            connection.setState(State.FIN_WAIT_1);
        }
    }

    /**
     * 发送数据包，并处理重传
     * @param packet 要发送的数据包
     * @param isRetransmission 是否是重传
     */
    private void sendWithRetransmission(TCPConnection connection, Packet packet, boolean isRetransmission) {

        try {
            // 序列化并发送数据包
            byte[] data = packet.toBytes();
            DatagramPacket datagramPacket = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName(connection.getPeerAddress()),
                connection.getPeerPort()
            );
            connection.getSocket().send(datagramPacket);

            // 如果不是重传，则启动重传定时器
            if (!isRetransmission) {
                startRetransmissionTimer(connection, packet);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 发送确认包
     */
    private void sendAck(TCPConnection connection, int ackNum) {
        TCPSendBuffer sendBuffer = connection.getSendBuffer();
        TCPReceiveBuffer receiveBuffer = connection.getReceiveBuffer();
        Packet ack = Packet.createAck(
            connection.getSourcePort(),
            connection.getPeerPort(),
            sendBuffer.getSndNxt(),
            ackNum,
            receiveBuffer.getRcvWnd()
        );
        sendWithRetransmission(connection, ack, true);
    }

    /**
     * 发送SYN包
     */
    private void sendSyn(RequestSock requestSock) throws IOException {
        // 创建并发送SYN包
        Packet synAckPacket = Packet.createSyn(
            requestSock.getSourcePort(),
            requestSock.getPeerPort(),
            requestSock.getSndSeq(),
            WINDOW_SIZE
        );

        // 发送ACK包
        try {
            byte[] data = synAckPacket.toBytes();
            DatagramPacket datagramPacket = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName(requestSock.getPeerAddress()),
                requestSock.getPeerPort()
            );
            requestSock.getSocket().send(datagramPacket);
        } catch (IOException e) {
            System.err.println("发送SYN包失败: " + e.getMessage());
        }
    }

    public void sendData(TCPConnection connection, Boolean force) {
        if (connection.getState() == State.ESTABLISHED) {
            TCPSendBuffer sendBuffer = connection.getSendBuffer();
            TCPReceiveBuffer receiveBuffer = connection.getReceiveBuffer();
            SendData nextData = sendBuffer.getNextData();
            if (nextData != null) {
                Packet dataPacket = Packet.createData(
                    connection.getSourcePort(),
                    connection.getPeerPort(),
                    nextData.getSeqNum(),
                    receiveBuffer.getRcvNxt(),
                    nextData.getData(),
                    sendBuffer.getSnd_wnd(),
                    force || nextData.isForce()  // 使用force参数或SendData中的force标志
                );
                sendWithRetransmission(connection, dataPacket, false);
            }
        }
    }

    /**
     * 发送FIN包
     */
    private void sendFin(TCPConnection connection) {
        TCPSendBuffer sendBuffer = connection.getSendBuffer();
        TCPReceiveBuffer receiveBuffer = connection.getReceiveBuffer();
        Packet fin = Packet.createFin(
            connection.getSourcePort(),
            connection.getPeerPort(),
            sendBuffer.getSndNxt(),
            receiveBuffer.getRcvNxt(),
            receiveBuffer.getRcvWnd()
        );
        sendBuffer.updateSndNxt(fin.getSeqNum() + 1);
        sendWithRetransmission(connection, fin, false);
        connection.setState(State.FIN_WAIT_1);
    }

    /**
     * 处理收到的数据包
     */
    private void handleAck(TCPConnection connection, Packet packet) {
        if (packet.isACK()) {
            System.out.println("收到ACK包: ackNum=" + packet.getAckNum() + ", windowSize=" + packet.getWindowSize());

            TCPSendBuffer sendBuffer = connection.getSendBuffer();
            TCPReceiveBuffer receiveBuffer = connection.getReceiveBuffer();
            // 更新发送缓冲区的确认状态
            sendBuffer.handleAck(packet.getAckNum());

            // 更新对方的接收窗口大小
            sendBuffer.updateReceiveWindow(packet.getWindowSize());

            // 如果所有数据都已确认，取消重传定时器
            if (sendBuffer.allDataAcked()) {
                System.out.println("所有数据已确认，取消重传定时器");
                cancelRetransmissionTimer(connection);
            } else {
                // 取消已确认数据的重传定时器
                cancelRetransmissionTimer(connection, packet.getAckNum() - 1);
            }

            // 更新RTT和RTO
            Long sendTime = connection.getSendTimes().remove(packet.getAckNum() - 1);
            if (sendTime != null) {
                long sampleRTT = System.currentTimeMillis() - sendTime;
                if (estimatedRTT == 0) {
                    estimatedRTT = sampleRTT;
                    devRTT = sampleRTT / 2;
                } else {
                    estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
                    devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
                }
                RTO = (int)(estimatedRTT + 4 * devRTT);
                System.out.println(
                    "更新RTO: sampleRTT=" + sampleRTT + "ms, estimatedRTT=" + estimatedRTT + "ms, devRTT=" + devRTT
                        + "ms, newRTO=" + RTO + "ms");
            }
        }
    }


    /**
     * 处理LISTEN状态下收到的数据包
     */
    private void handleListenState(RequestSock connection, Packet packet) throws IOException {
        if (packet.isSYN()) {
            // 收到SYN包，进入SYN_RECEIVED状态
            connection.setState(State.SYN_RECEIVED);

            // 更新接收序号
            connection.setRcvSeq(packet.getSeqNum() + 1);

            // 发送SYN+ACK包
            sendSynAck(connection, packet);
        }

    }

    /**
     * 处理SYN_SENT状态下收到的数据包
     */
    private void handleSynSentState(RequestSock requestSock, Packet packet) throws IOException {
        if (packet.isSYN() && packet.isACK()) {
            // 收到SYN+ACK包，进入ESTABLISHED状态
            requestSock.setState(State.ESTABLISHED);

            //更新接收序号和发送序号
            requestSock.setRcvSeq(packet.getSeqNum() + 1);
            requestSock.setSndSeq(requestSock.getSndSeq() + 1);

            // 创建并发送ACK包
            Packet synAckPacket = Packet.createAck(
                requestSock.getSourcePort(),
                requestSock.getPeerPort(),
                requestSock.getSndSeq(),
                requestSock.getRcvSeq(),
                WINDOW_SIZE
            );

            // 发送ACK包
            try {
                byte[] data = synAckPacket.toBytes();
                DatagramPacket datagramPacket = new DatagramPacket(
                    data,
                    data.length,
                    InetAddress.getByName(requestSock.getPeerAddress()),
                    requestSock.getPeerPort()
                );
                requestSock.getSocket().send(datagramPacket);
            } catch (IOException e) {
                System.err.println("发送SYN-ACK包失败: " + e.getMessage());
            }
        }
    }

    /**
     * 处理SYN_RECEIVED状态下收到的数据包
     */
    private void handleSynReceivedState(RequestSock connection, Packet packet) throws IOException {
        if (packet.isACK()) {
            // 收到ACK，进入ESTABLISHED状态
            connection.setState(State.ESTABLISHED);
            connection.setRcvSeq(packet.getSeqNum() + 1);
        } else if (packet.isSYN()) {
            // 收到重复的SYN，重发SYN+ACK
            sendSynAck(connection, packet);
        }
    }

    /**
     * 处理ESTABLISHED状态下收到的数据包
     */
    private void handleEstablishedState(TCPConnection connection, Packet packet) throws IOException {
        if (packet.isFIN()) {
            // 收到FIN包，进入CLOSE_WAIT状态
            connection.setState(State.CLOSE_WAIT);

            // 发送ACK确认FIN
            sendAck(connection, packet.getSeqNum() + 1);
        } else if (packet.getType() == Packet.TYPE_DATA) {
            // 处理数据包
            TCPReceiveBuffer receiveBuffer = connection.getReceiveBuffer();
            int result = receiveBuffer.receive(packet.getSeqNum(), packet.getData());

            // 发送ACK
            if (result >= 0) {  // 成功接收或重复数据
                sendAck(connection, receiveBuffer.getRcvNxt());
            }
        }
    }

    /**
     * 处理FIN_WAIT1状态下收到的数据包
     */
    private void handleFinWait1State(TCPConnection connection, Packet packet) throws IOException {
        TCPSendBuffer sendBuffer = connection.getSendBuffer();
        if (packet.isACK() && packet.getAckNum() == sendBuffer.getSndNxt()) {
            // 收到ACK，进入FIN_WAIT2状态
            connection.setState(State.FIN_WAIT_2);
        } else if (packet.isFIN()) {
            // 收到FIN，进入CLOSING状态
            connection.setState(State.CLOSING);

            // 发送ACK确认FIN
            sendAck(connection, packet.getSeqNum() + 1);
        }
    }

    /**
     * 处理FIN_WAIT2状态下收到的数据包
     */
    private void handleFinWait2State(TCPConnection connection, Packet packet) throws IOException {
        if (packet.isFIN()) {
            // 收到FIN，进入TIME_WAIT状态
            connection.setState(State.TIME_WAIT);

            // 发送ACK确认FIN
            sendAck(connection, packet.getSeqNum() + 1);

            // 启动TIME_WAIT定时器
            startTimeWaitTimer(connection);
        }
    }

    /**
     * 处理CLOSE_WAIT状态下收到的数据包
     */
    private void handleCloseWaitState(TCPConnection connection, Packet packet) throws IOException {
        // CLOSE_WAIT状态主要等待应用层关闭连接
        // 可以处理重复的FIN包
        if (packet.isFIN()) {
            // 重发ACK
            sendAck(connection, packet.getSeqNum() + 1);
        }
    }

    /**
     * 处理LAST_ACK状态下收到的数据包
     */
    private void handleLastAckState(TCPConnection connection, Packet packet) throws IOException {
        TCPSendBuffer sendBuffer = connection.getSendBuffer();
        if (packet.isACK() && packet.getAckNum() == sendBuffer.getSndNxt()) {
            // 收到最后的ACK，进入CLOSED状态
            connection.setState(State.CLOSED);
        }
    }

    /**
     * 处理CLOSING状态下收到的数据包
     */
    private void handleClosingState(TCPConnection connection, Packet packet) throws IOException {
        TCPSendBuffer sendBuffer = connection.getSendBuffer();
        if (packet.isACK() && packet.getAckNum() == sendBuffer.getSndNxt()) {
            // 收到ACK，进入TIME_WAIT状态
            connection.setState(State.TIME_WAIT);

            // 启动TIME_WAIT定时器
            startTimeWaitTimer(connection);
        }
    }

    /**
     * 处理TIME_WAIT状态下收到的数据包
     */
    private void handleTimeWaitState(TCPConnection connection, Packet packet) throws IOException {
        if (packet.isFIN()) {
            // 收到重复的FIN，重发ACK
            sendAck(connection, packet.getSeqNum() + 1);

            // 重置TIME_WAIT定时器
            startTimeWaitTimer(connection);
        }
    }

    /**
     * 启动TIME_WAIT定时器
     */
    private void startTimeWaitTimer(TCPConnection connection) {
        ScheduledFuture<?> timeWaitTimer = connection.getTimeWaitTimer();
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
        }

        connection.setTimeWaitTimer(connection.getScheduler().schedule(() -> {
            connection.setState(State.CLOSED);
            cleanup(connection);
        }, 2 * MSL, TimeUnit.MILLISECONDS));
    }

    /**
     * 清理资源
     */
    private void cleanup(TCPConnection connection) {
        // 取消所有定时器
        Map<Integer, ScheduledFuture<?>> retransmissionTimers = connection.getRetransmissionTimers();
        for (ScheduledFuture<?> timer : retransmissionTimers.values()) {
            timer.cancel(false);
        }
        retransmissionTimers.clear();

        ScheduledFuture<?> timeWaitTimer = connection.getTimeWaitTimer();
        if (timeWaitTimer != null) {
            timeWaitTimer.cancel(false);
            connection.setTimeWaitTimer(null);
        }

        // 清理数据结构
        connection.getSendTimes().clear();
    }

    /**
     * 处理收到的数据
     */
    public void handleData(byte[] data) {
        // 这里可以添加数据处理逻辑
        System.out.println("收到数据: " + new String(data));
    }

    /**
     * 发起连接
     */
    public void connect(RequestSock connection) throws IOException {
        if (connection.getState() == State.CLOSED) {
            connection.setState(State.SYN_SENT);
            sendSyn(connection);
        }
    }

    /**
     * 检查连接状态
     */
    public boolean isConnected(TCPConnection connection) {
        return connection.getState() == State.ESTABLISHED;
    }

    /**
     * 设置重传定时器
     */
    private void startRetransmissionTimer(TCPConnection connection, Packet packet) {
        // 取消已有的定时器（如果存在）
        cancelRetransmissionTimer(connection, packet.getSeqNum());

        // 创建新的重传任务
        ScheduledExecutorService scheduler = connection.getScheduler();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            try {
                synchronized (this) {
                    System.out.println("开始重传数据包: seqNum=" + packet.getSeqNum() + ", RTO=" + RTO + "ms");
                    byte[] data = packet.toBytes();
                    DatagramSocket socket = connection.getSocket();
                    socket.send(new java.net.DatagramPacket(
                        data, data.length, InetAddress.getByName(connection.getPeerAddress()), connection.getPeerPort()
                    ));
                    // 指数退避：下次超时时间加倍
                    RTO *= 2;
                    System.out.println("重传完成，新的RTO=" + RTO + "ms");
                    startRetransmissionTimer(connection, packet);
                }
            } catch (Exception e) {
                System.err.println("重传数据包失败: " + e.getMessage());
                e.printStackTrace();
            }
        }, RTO, TimeUnit.MILLISECONDS);

        System.out.println("设置重传定时器: seqNum=" + packet.getSeqNum() + ", RTO=" + RTO + "ms");

        connection.getRetransmissionTimers().put(packet.getSeqNum(), timer);
    }

    /**
     * 取消重传定时器
     */
    private void cancelRetransmissionTimer(TCPConnection connection) {
        Map<Integer, ScheduledFuture<?>> retransmissionTimers = connection.getRetransmissionTimers();
        System.out.println("取消所有重传定时器，数量: " + retransmissionTimers.size());
        for (ScheduledFuture<?> timer : retransmissionTimers.values()) {
            timer.cancel(false);
        }
        retransmissionTimers.clear();
    }

    /**
     * 取消重传定时器
     */
    private void cancelRetransmissionTimer(TCPConnection connection, int seqNum) {
        Map<Integer, ScheduledFuture<?>> retransmissionTimers = connection.getRetransmissionTimers();
        ScheduledFuture<?> timer = retransmissionTimers.remove(seqNum);
        if (timer != null) {
            System.out.println("取消重传定时器: seqNum=" + seqNum);
            timer.cancel(false);
        }
    }
}
