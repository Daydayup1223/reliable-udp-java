package com.reliableudp;

import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.reliableudp.Packet.FLAG_ACK;
import static com.reliableudp.Packet.FLAG_PSH;
import static com.reliableudp.Packet.TYPE_DATA;

public class TCPSendBuffer {
    // TCP协议参数
    private static final int MSS = 1024;  // Maximum Segment Size
    private static final int INIT_CWND = MSS;
    private static final int INIT_SSTHRESH = 65535;
    private static final int BUFFER_SIZE = 65535;  // 发送缓冲区大小
    private static final double BETA = 0.7;
    private static final int DUPACK_THRESHOLD = 3;  // 快重传阈值

    private final byte[] sendBuffer;      // 发送缓冲区
    private int snd_una;                  // 最老的未确认字节
    private int snd_nxt;                  // 下一个要发送的字节
    private int snd_wnd;                  // 发送窗口 = min(cwnd, rwnd)
    private int cwnd;                     // 拥塞窗口
    private int ssthresh;                 // 慢启动阈值
    private int rwnd;                     // 接收窗口
    private int dupAckCount;              // 重复ACK计数
    private int lastAckedSeq;             // 最后确认的序号
    private int bufferEnd;                // 缓冲区末尾位置

    public void updateSndNxt(int seq) {
        this.snd_nxt = seq;
    }

    public int getSndNxt() {
        return snd_nxt;
    }

    public int getSndUna() {
        return snd_una;
    }

    public int getSndWnd() {
        return Math.min(cwnd, rwnd);
    }

    public void updateRwnd(int rwnd) {
        this.rwnd = rwnd;
        this.snd_wnd = getSndWnd();
    }

    public boolean allDataAcked() {
        return snd_una >= snd_nxt;
    }

    private enum CongestionState {
        SLOW_START,           // 慢启动
        CONGESTION_AVOIDANCE, // 拥塞避免
        FAST_RECOVERY        // 快恢复
    }
    private CongestionState state;

    private final ScheduledExecutorService scheduler;
    private final Consumer<Boolean> packetSender;
    private ScheduledFuture<?> delayedSendTask;
    
    public TCPSendBuffer(Consumer<Boolean> packetSender, int seq) {
        this.sendBuffer = new byte[BUFFER_SIZE];
        this.packetSender = packetSender;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.snd_una = seq;
        this.snd_nxt = seq;
        this.cwnd = INIT_CWND;
        this.ssthresh = INIT_SSTHRESH;
        this.rwnd = 65535;
        this.snd_wnd = getSndWnd();
        this.state = CongestionState.SLOW_START;
        this.dupAckCount = 0;
        this.lastAckedSeq = -1;
        this.bufferEnd = 0;
    }

    public synchronized boolean writeToBuffer(byte[] data, boolean force) {
        if (data == null || data.length == 0) {
            return false;
        }

        // 检查缓冲区空间
        if (snd_nxt - snd_una + data.length > BUFFER_SIZE) {
            return false;
        }

        // 复制数据到缓冲区
        int writePos = snd_nxt % BUFFER_SIZE;
        int firstPart = Math.min(data.length, BUFFER_SIZE - writePos);
        System.arraycopy(data, 0, sendBuffer, writePos, firstPart);
        
        if (firstPart < data.length) {
            // 需要环绕写入
            System.arraycopy(data, firstPart, sendBuffer, 0, data.length - firstPart);
        }

        // 更新缓冲区末尾位置
        bufferEnd = Math.max(bufferEnd, snd_nxt + data.length);

        // Nagle算法：
        // 1. 如果数据大于等于MSS，立即发送
        // 2. 如果是强制发送（比如FIN包），立即发送
        // 3. 如果没有未确认数据，并且累积数据达到阈值，立即发送
        // 4. 否则，延迟发送等待更多数据
        int accumulatedSize = bufferEnd - snd_nxt;
        if (data.length >= MSS || force || 
            (snd_una == snd_nxt && accumulatedSize >= MSS/2)) {
            packetSender.accept(force);
        } else {
            scheduleDelayedSend();
        }

        return true;
    }

    public synchronized SendData getNextData() {
        // 检查发送窗口
        int availableWindow = snd_wnd - (snd_nxt - snd_una);
        if (availableWindow <= 0) {
            return null;
        }

        // 检查实际可用数据量
        int availableData = Math.max(0, bufferEnd - snd_nxt);
        if (availableData == 0) {
            return null;
        }

        // 限制发送大小
        availableWindow = Math.min(availableWindow, MSS);
        availableWindow = Math.min(availableWindow, availableData);

        // 准备发送数据
        byte[] dataToSend = new byte[availableWindow];
        int readPos = snd_nxt % BUFFER_SIZE;
        int firstPart = Math.min(availableWindow, BUFFER_SIZE - readPos);
        System.arraycopy(sendBuffer, readPos, dataToSend, 0, firstPart);
        
        if (firstPart < availableWindow) {
            // 需要环绕读取
            System.arraycopy(sendBuffer, 0, dataToSend, firstPart, availableWindow - firstPart);
        }

        int seqNum = snd_nxt;
        snd_nxt += availableWindow;

        return new SendData(seqNum, dataToSend, false, availableWindow);
    }

    public synchronized void handleAck(int ackNum) {
        if (ackNum >= snd_una) {
            // 更新已确认数据位置
            snd_una = ackNum;

            // 处理拥塞控制
            boolean isNewAck = ackNum > lastAckedSeq;
            if (isNewAck) {
                handleNewAck(ackNum);
            } else {
                handleDupAck();
            }
            lastAckedSeq = ackNum;
        }
    }

    private void scheduleDelayedSend() {
        if (delayedSendTask != null && !delayedSendTask.isDone()) {
            return;
        }
        delayedSendTask = scheduler.schedule(() -> {
            synchronized (TCPSendBuffer.this) {
                packetSender.accept(false);
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized void updateSndUna(int ack) {
        if (ack > snd_una) {
            snd_una = ack;
        }
    }

    public synchronized void updateReceiveWindow(int windowSize) {
        this.rwnd = windowSize;
    }

    public synchronized int getSnd_wnd() {
        return Math.min(cwnd, rwnd) - (snd_nxt - snd_una);
    }

    public void close() {
        if (delayedSendTask != null) {
            delayedSendTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void handleTimeout() {
        // 超时重传
        ssthresh = Math.max((int)(cwnd * BETA), 2 * MSS);
        cwnd = INIT_CWND;
        state = CongestionState.SLOW_START;
        dupAckCount = 0;
        
        // 重传数据
        retransmitLostPacket();
        
        System.out.println("超时重传：cwnd=" + cwnd + ", ssthresh=" + ssthresh);
    }

    private void handleNewAck(int ackNum) {
        snd_una = ackNum;
        dupAckCount = 0;

        switch (state) {
            case SLOW_START:
                // 慢启动：指数增长
                cwnd += MSS;
                if (cwnd >= ssthresh) {
                    state = CongestionState.CONGESTION_AVOIDANCE;
                    System.out.println("进入拥塞避免：cwnd=" + cwnd + ", ssthresh=" + ssthresh);
                }
                break;

            case CONGESTION_AVOIDANCE:
                // 拥塞避免：线性增长，每个RTT增加一个MSS
                cwnd += MSS * MSS / cwnd;
                break;

            case FAST_RECOVERY:
                // 退出快恢复
                cwnd = ssthresh;
                state = CongestionState.CONGESTION_AVOIDANCE;
                System.out.println("退出快恢复：cwnd=" + cwnd);
                break;
        }
        // 更新发送窗口
        snd_wnd = getSndWnd();
    }

    private void handleDupAck() {
        dupAckCount++;
        if (dupAckCount == DUPACK_THRESHOLD) {
            // 进入快恢复
            ssthresh = Math.max((int)(cwnd * BETA), 2 * MSS);
            cwnd = ssthresh + 3 * MSS;  // 快恢复初始窗口
            state = CongestionState.FAST_RECOVERY;
            
            // 快重传
            retransmitLostPacket();
            
            System.out.println("进入快恢复：cwnd=" + cwnd + ", ssthresh=" + ssthresh);
        } else if (state == CongestionState.FAST_RECOVERY) {
            // 快恢复期间收到重复ACK
            cwnd += MSS;
        }
    }

    private void retransmitLostPacket() {
        // 重传snd_una处的数据包
        byte[] data = findDataToRetransmit();
        if (data != null) {
            packetSender.accept(true);  // force=true表示需要立即重传
        }
    }

    private byte[] findDataToRetransmit() {
        // 在实际实现中，需要维护未确认数据的副本
        // 这里简化处理，返回null
        return null;
    }

    // 发送数据的包装类
    public static class SendData {
        private final int seqNum;
        private final byte[] data;
        private final boolean force;
        private final int length;  // 实际数据长度

        public SendData(int seqNum, byte[] data, boolean force, int length) {
            this.seqNum = seqNum;
            this.data = data;
            this.force = force;
            this.length = length;
        }

        public int getSeqNum() {
            return seqNum;
        }

        public byte[] getData() {
            return data;
        }

        public boolean isForce() {
            return force;
        }

        public int getLength() {
            return length;
        }
    }
    @Override
    public String toString() {
        return String.format(
            "SendBuffer[SND.UNA=%d, SND.NXT=%d, SND.WND=%d, " +
            "CWND=%d, RWND=%d, State=%s]",
            snd_una, snd_nxt, snd_wnd, cwnd, rwnd, state
        );
    }
}
