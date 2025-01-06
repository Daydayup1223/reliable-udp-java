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
    private static final int MSS = 1460;           // Maximum Segment Size
    private static final int INITIAL_BUFFER_SIZE = 65535; // 初始缓冲区大小
    private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 最大缓冲区大小
    private static final int INITIAL_WINDOW = 1024;   // 初始窗口大小
    private static final long DELAYED_ACK_TIMEOUT = 200; // 延迟发送超时时间(ms)
    private static final int MIN_PACKET_SIZE = 512;   // Nagle算法的最小包大小

    // TCP拥塞控制相关常量
    private static final int INIT_CWND = MSS;  // 初始拥塞窗口为1个MSS
    private static final int INIT_SSTHRESH = 65535;  // 初始慢启动阈值
    private static final double BETA = 0.5;    // 快恢复时cwnd减少因子
    private static final int DUPACK_THRESHOLD = 3;  // 快重传阈值

    // 发送窗口变量
    private final int iss;          // Initial Send Sequence number
    private int snd_una;           // 最早的未确认字节序号
    private int snd_nxt;           // 下一个待发送字节序号
    private int snd_wnd;           // 发送窗口大小
    private int cwnd;              // 拥塞窗口大小
    private int rwnd;              // 接收方通告窗口大小
    
    // 拥塞控制相关变量
    private int ssthresh = INIT_SSTHRESH; // 慢启动阈值
    private int dupAckCount = 0;          // 重复ACK计数
    private int lastAckedSeq = -1;        // 上一个确认的序号
    private CongestionState congestionState = CongestionState.SLOW_START;

    // 缓冲区管理
    private static class Segment {
        final byte[] data;
        final int offset;
        final int length;
        final long timestamp;
        final boolean force;  // 添加force标志
        Segment next;

        Segment(byte[] data, int offset, int length, boolean force) {
            this.data = Arrays.copyOf(data, length);
            this.offset = offset;
            this.length = length;
            this.timestamp = System.currentTimeMillis();
            this.force = force;
        }
    }

    private Segment head;  // 发送缓冲区头部
    private Segment tail;  // 发送缓冲区尾部
    private long totalBufferedSize; // 总缓冲数据大小
    
    // 已发送但未确认的包
    private final Map<Integer, SendData> unackedPackets;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> delayedSendTask;
    private ScheduledFuture<?> persistTimer;
    
    // Nagle算法相关
    private boolean nagleEnabled = true;
    private Segment lastSegment;  // 最后一个未发送的段

    private final Consumer<Boolean> packetSender;  // 实际发送数据的回调函数，带重传标志

    // 拥塞控制状态
    private enum CongestionState {
        SLOW_START,      // 慢启动
        CONGESTION_AVOIDANCE,  // 拥塞避免
        FAST_RECOVERY    // 快恢复
    }

    // 发送数据的包装类
    public static class SendData {
        private final int seqNum;
        private final byte[] data;
        private final boolean force;

        public SendData(int seqNum, byte[] data, boolean force) {
            this.seqNum = seqNum;
            this.data = data;
            this.force = force;
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
    }

    public TCPSendBuffer(Consumer<Boolean> packetSender) {
        // 初始化序列号
        this.iss = getInitialSequenceNumber();
        this.snd_una = this.iss;
        this.snd_nxt = this.iss;
        
        // 初始化窗口
        this.snd_wnd = INITIAL_WINDOW;
        this.cwnd = INIT_CWND;
        this.rwnd = INITIAL_WINDOW;

        
        this.unackedPackets = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.totalBufferedSize = 0;
        this.packetSender = packetSender;
        
        setupTimers();
    }

    private void setupTimers() {
        // 持久化定时器，用于处理零窗口探测
        persistTimer = scheduler.scheduleWithFixedDelay(
            this::checkWindowProbe,
            1000, 1000, TimeUnit.MILLISECONDS
        );

        // 设置发送进程，定期检查并发送数据
        scheduler.scheduleWithFixedDelay(
            this::processTransmitQueue,
            100,  // 初始延迟100ms
            100,  // 每100ms检查一次发送队列
            TimeUnit.MILLISECONDS
        );
    }

    private void processTransmitQueue() {
        synchronized(this) {
            if (!canSendData()) {
                return;
            }

            // 获取下一个要发送的数据
            SendData nextData = getNextData();
            if (nextData != null) {
                packetSender.accept(nextData.isForce());
            }
        }
    }

    private void scheduleDelayedSend() {
        if (delayedSendTask != null) {
            delayedSendTask.cancel(false);
        }
        delayedSendTask = scheduler.schedule(
            this::flushBufferedData,
            DELAYED_ACK_TIMEOUT,
            TimeUnit.MILLISECONDS
        );
    }

    public int getSnd_wnd() {
        return snd_wnd;
    }

    private void flushBufferedData() {
        synchronized(this) {
            // 先发送最后一个可能合并的小包段
            if (lastSegment != null) {
                writeToBuffer(lastSegment.data, true);
                lastSegment = null;
            }
            
            // 发送其他缓存的段
            while (head != null && canSendData()) {
                Segment current = head;
                head = current.next;
                if (head == null) {
                    tail = null;
                }
                
                writeToBuffer(current.data, true);
                totalBufferedSize -= current.length;
            }
        }
    }

    private void checkWindowProbe() {
        if (getEffectiveWindow() == 0 && !unackedPackets.isEmpty()) {
            // 发送窗口探测包
            probeWindow();
        }
    }

    private void probeWindow() {
        // 发送1字节的探测包
        if (!unackedPackets.isEmpty()) {
            Map.Entry<Integer, SendData> firstUnacked = 
                unackedPackets.entrySet().iterator().next();
            byte[] probeData = new byte[1];
            probeData[0] = firstUnacked.getValue().getData()[0];
            writeToBuffer(probeData, true); // 强制发送探测包
        }
    }

    /**
     * 应用层调用，尝试立即发送数据
     */
    public synchronized SendData getNextData() {
        if (lastSegment != null) {
            Segment segment = lastSegment;
            lastSegment = null;
            int seqNum = snd_nxt;
            snd_nxt += segment.length;
            return new SendData(seqNum, segment.data, segment.force);
        }

        // 检查缓冲区中的数据
        if (head != null) {
            Segment current = head;
            head = head.next;
            if (head == null) {
                tail = null;
            }
            
            totalBufferedSize -= current.length;
            int seqNum = snd_nxt;
            snd_nxt += current.length;
            return new SendData(seqNum, current.data, current.force);
        }

        return null;
    }

    /**
     * 应用层调用，将数据放入发送缓冲区
     * @param data 要发送的数据
     * @param push 是否设置PUSH标志
     */
    public synchronized boolean put(byte[] data, boolean push) {
        if (data == null || data.length == 0) {
            return false;
        }

        // 检查内存压力
        if (!checkMemoryPressure(data.length)) {
            return false;
        }

        // 如果设置了PUSH标志，立即发送所有缓冲数据
        if (push) {
            // 先发送已缓存的数据
            flushBufferedData();
            // 然后发送当前数据
            return writeToBuffer(data, true);
        }

        // 否则应用Nagle算法
        if (nagleEnabled && data.length < MIN_PACKET_SIZE) {
            return tryMergeSegment(data);
        }

        return bufferSegment(data, false);
    }

    /**
     * 默认的put方法，不设置PUSH标志
     */
    public boolean put(byte[] data) {
        return put(data, false);
    }

    private boolean tryMergeSegment(byte[] data) {
        if (lastSegment == null) {
            lastSegment = new Segment(data, 0, data.length, false);
            totalBufferedSize += data.length;
            scheduleDelayedSend();
            return true;
        }
        
        if (lastSegment.length + data.length > MSS) {
            // 当前lastSegment已经不能再合并了，将其加入发送队列
            bufferSegment(lastSegment.data, lastSegment.force);
            lastSegment = new Segment(data, 0, data.length, false);
            totalBufferedSize += data.length;
            scheduleDelayedSend();
            return true;
        }
        
        // 合并数据
        byte[] newData = new byte[lastSegment.length + data.length];
        System.arraycopy(lastSegment.data, 0, newData, 0, lastSegment.length);
        System.arraycopy(data, 0, newData, lastSegment.length, data.length);
        
        // 更新最后一个段
        lastSegment = new Segment(newData, 0, newData.length, lastSegment.force);
        totalBufferedSize += data.length;
        
        // 如果没有设置定时器，设置一个
        scheduleDelayedSend();
        return true;
    }

    private boolean bufferSegment(byte[] data, boolean force) {
        if (checkMemoryPressure(data.length)) {
            return false;
        }

        Segment segment = new Segment(data, 0, data.length, force);
        if (head == null) {
            head = segment;
        } else {
            tail.next = segment;
        }
        tail = segment;
        totalBufferedSize += data.length;
        return true;
    }

    private boolean writeToBuffer(byte[] data, boolean force) {
        synchronized(this) {
            if (!force && !canSendData()) {
                return bufferSegment(data, force);
            }

            Segment segment = new Segment(data, 0, data.length, force);

            // 加入发送队列
            if (head == null) {
                head = segment;
            } else {
                tail.next = segment;
            }
            tail = segment;
            totalBufferedSize += data.length;

            // 如果是force发送，立即触发回调
            if (force) {
                packetSender.accept(true);
            }

            return true;
        }
    }

    public synchronized void handleAck(int ackNum) {
        if (ackNum >= snd_una && ackNum <= snd_nxt) {
            boolean isNewAck = ackNum > lastAckedSeq;
            
            // 更新拥塞窗口
            updateCongestionWindow(isNewAck);
            
            if (isNewAck) {
                // 更新最早未确认序号
                snd_una = ackNum;
                lastAckedSeq = ackNum;
                
                // 清理已确认的包
                Iterator<Map.Entry<Integer, SendData>> it = 
                    unackedPackets.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getKey() <= ackNum) {
                        it.remove();
                    }
                }
                
                // 尝试发送更多数据
                flushBufferedData();
            }
        }
    }

    private void enterSlowStart() {
        cwnd = INIT_CWND;
        ssthresh = INIT_SSTHRESH;
        congestionState = CongestionState.SLOW_START;
        System.out.println("进入慢启动: cwnd=" + cwnd + ", ssthresh=" + ssthresh);
    }

    private void enterCongestionAvoidance() {
        congestionState = CongestionState.CONGESTION_AVOIDANCE;
        System.out.println("进入拥塞避免: cwnd=" + cwnd + ", ssthresh=" + ssthresh);
    }

    private void enterFastRecovery() {
        ssthresh = Math.max((int)(cwnd * BETA), 2 * MSS);
        cwnd = ssthresh + 3 * MSS;  // 快恢复初始窗口
        congestionState = CongestionState.FAST_RECOVERY;
        System.out.println("进入快恢复: cwnd=" + cwnd + ", ssthresh=" + ssthresh);
    }

    private void handleTimeout() {
        ssthresh = Math.max((int)(cwnd * BETA), 2 * MSS);
        cwnd = INIT_CWND;
        dupAckCount = 0;
        congestionState = CongestionState.SLOW_START;
        System.out.println("超时: cwnd=" + cwnd + ", ssthresh=" + ssthresh);
    }

    private void updateCongestionWindow(boolean isNewAck) {
        if (!isNewAck) {
            // 重复ACK
            dupAckCount++;
            if (dupAckCount == DUPACK_THRESHOLD) {
                // 进入快恢复
                enterFastRecovery();
            } else if (congestionState == CongestionState.FAST_RECOVERY) {
                // 快恢复期间收到重复ACK
                cwnd += MSS;
            }
            return;
        }

        // 新ACK
        dupAckCount = 0;

        switch (congestionState) {
            case SLOW_START:
                cwnd += MSS;  // 指数增长
                if (cwnd >= ssthresh) {
                    enterCongestionAvoidance();
                }
                break;

            case CONGESTION_AVOIDANCE:
                // 加性增，每个RTT增加一个MSS
                cwnd += MSS * MSS / cwnd;
                break;

            case FAST_RECOVERY:
                // 退出快恢复
                cwnd = ssthresh;
                congestionState = CongestionState.CONGESTION_AVOIDANCE;
                System.out.println("退出快恢复: cwnd=" + cwnd);
                break;
        }
    }


    private int getEffectiveWindow() {
        // 使用拥塞窗口和接收窗口的最小值
        return Math.min(cwnd, rwnd) - (snd_nxt - snd_una);
    }

    public void setNagleAlgorithm(boolean enabled) {
        this.nagleEnabled = enabled;
        if (!enabled) {
            flushBufferedData();
        }
    }

    public void close() {
        if (persistTimer != null) {
            persistTimer.cancel(false);
        }
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

    public synchronized void updateReceiveWindow(int windowSize) {
        this.rwnd = windowSize;
        // 窗口更新后，尝试发送被阻塞的数据
        flushBufferedData();
    }

    private boolean canSendData() {
        if (unackedPackets.isEmpty()) {
            return true;
        }
        
        int effectiveWindow = getEffectiveWindow();
        return effectiveWindow > 0;
    }

    private int getInitialSequenceNumber() {
        return new Random().nextInt(Integer.MAX_VALUE);
    }

    private boolean checkMemoryPressure(int additionalSize) {
        return totalBufferedSize + additionalSize <= MAX_BUFFER_SIZE;
    }

    public int getSndNxt() {
        return snd_nxt;
    }

    public int getSndUna() {
        return snd_una;
    }

    public void updateSndNxt(int newSndNxt) {
        this.snd_nxt = newSndNxt;
    }

    public boolean allDataAcked() {
        return unackedPackets.isEmpty();
    }

    public void clear() {
        unackedPackets.clear();
        head = tail = lastSegment = null;
        totalBufferedSize = 0;
    }


    @Override
    public String toString() {
        return String.format(
            "SendBuffer[ISS=%d, SND.UNA=%d, SND.NXT=%d, SND.WND=%d, " +
            "CWND=%d, RWND=%d, UnackedPackets=%d, BufferedSize=%d, " +
            "NagleEnabled=%s, CongestionState=%s]",
            iss, snd_una, snd_nxt, snd_wnd, cwnd, rwnd,
            unackedPackets.size(), totalBufferedSize, nagleEnabled,
            congestionState
        );
    }
}
