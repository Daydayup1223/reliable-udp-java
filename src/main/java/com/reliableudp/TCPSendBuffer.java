package com.reliableudp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TCPSendBuffer {
    // TCP协议参数
    private static final int MSS = 1460;           // Maximum Segment Size
    private static final int MAX_WINDOW_SIZE = 65535; // 最大窗口大小
    private static final int INITIAL_WINDOW = 1024;   // 初始窗口大小

    // 发送窗口变量
    private final int iss;          // Initial Send Sequence number
    private int snd_una;           // 最早的未确认字节序号
    private int snd_nxt;           // 下一个待发送字节序号
    private int snd_wnd;           // 发送窗口大小
    private int cwnd;              // 拥塞窗口大小
    private int rwnd;              // 接收方通告窗口大小
    
    // 发送缓冲区
    private final byte[] sendBuffer;        // 环形缓冲区
    private int writeIndex;                 // 写入位置
    private int unackIndex;                 // 未确认数据起始位置
    private int readIndex;                  // 读取位置
    
    // 已发送但未确认的包
    private final Map<Integer, Packet> unackedPackets;

    public TCPSendBuffer() {
        // 初始化序列号（使用时间基准）
        this.iss = getInitialSequenceNumber();
        this.snd_una = this.iss;
        this.snd_nxt = this.iss;
        
        // 初始化窗口
        this.snd_wnd = INITIAL_WINDOW;
        this.cwnd = MSS;           // 初始拥塞窗口为1个MSS
        this.rwnd = INITIAL_WINDOW;
        
        // 初始化缓冲区
        this.sendBuffer = new byte[MAX_WINDOW_SIZE];
        this.writeIndex = 0;
        this.unackIndex = 0;
        this.readIndex = 0;
        
        this.unackedPackets = new ConcurrentHashMap<>();
    }
    
    private static int getInitialSequenceNumber() {
        return (int) ((System.currentTimeMillis() * 1000) & 0x7FFFFFFF);
    }

    public synchronized boolean put(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        int availableSpace = MAX_WINDOW_SIZE - ((writeIndex - readIndex + MAX_WINDOW_SIZE) % MAX_WINDOW_SIZE);
        if (data.length > availableSpace) {
            return false;
        }

        for (byte b : data) {
            sendBuffer[writeIndex] = b;
            writeIndex = (writeIndex + 1) % MAX_WINDOW_SIZE;
        }
        
        return true;
    }

    public synchronized SendData getNextData() {
        int availableWindow = Math.min(cwnd, rwnd);
        int usedWindow = snd_nxt - snd_una;
        
        if (usedWindow >= availableWindow) {
            return null;
        }

        int dataAvailable = (writeIndex - readIndex + MAX_WINDOW_SIZE) % MAX_WINDOW_SIZE;
        if (dataAvailable == 0) {
            return null;
        }

        int len = Math.min(MSS, Math.min(dataAvailable, availableWindow - usedWindow));
        byte[] data = new byte[len];
        
        for (int i = 0; i < len; i++) {
            data[i] = sendBuffer[readIndex];
            readIndex = (readIndex + 1) % MAX_WINDOW_SIZE;
        }

        int seqNum = snd_nxt;
        snd_nxt += len;
        
        return new SendData(seqNum, data);
    }

    public synchronized void handleAck(int ackNum) {
        if (ackNum >= snd_una && ackNum <= snd_nxt) {
            snd_una = ackNum;
            
            Iterator<Map.Entry<Integer, Packet>> it = unackedPackets.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getKey() <= ackNum) {
                    it.remove();
                }
            }
            
            unackIndex = (unackIndex + (ackNum - snd_una)) % MAX_WINDOW_SIZE;
        }
    }

    public void updateReceiveWindow(int windowSize) {
        this.rwnd = windowSize;
    }

    public int getEffectiveWindow() {
        return Math.min(cwnd, rwnd);
    }

    public void addUnackedPacket(Packet packet) {
        unackedPackets.put(packet.getSeqNum(), packet);
    }

    public boolean allDataAcked() {
        return unackedPackets.isEmpty() && readIndex == writeIndex;
    }

    public void clear() {
        unackedPackets.clear();
        readIndex = writeIndex = unackIndex = 0;
        snd_nxt = snd_una;
    }

    public int getSndNxt() {
        return snd_nxt;
    }

    public int getSndUna() {
        return snd_una;
    }

    public void updateSndNxt(int seqNum) {
        if (seqNum > snd_nxt) {
            snd_nxt = seqNum;
        }
    }

    public Packet getUnackedPacket(int seqNum) {
        return unackedPackets.get(seqNum);
    }

    @Override
    public String toString() {
        return String.format(
            "SendBuffer[ISS=%d, SND.UNA=%d, SND.NXT=%d, SND.WND=%d, CWND=%d, RWND=%d, " +
            "UnackedPackets=%d, BufferUsage=%d%%]",
            iss, snd_una, snd_nxt, snd_wnd, cwnd, rwnd,
            unackedPackets.size(),
            ((writeIndex - readIndex + MAX_WINDOW_SIZE) % MAX_WINDOW_SIZE) * 100 / MAX_WINDOW_SIZE
        );
    }

    public static class SendData {
        private final int seqNum;
        private final byte[] data;

        public SendData(int seqNum, byte[] data) {
            this.seqNum = seqNum;
            this.data = data;
        }

        public int getSeqNum() { return seqNum; }
        public byte[] getData() { return data; }
    }
}
