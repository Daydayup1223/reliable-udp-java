package com.reliableudp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TCPSendBuffer {
    // 发送窗口大小（默认20个字节）
    private int sndWnd = 20;
    // 已确认的序号（最后一个已确认的序号）
    private int sndUna;
    // 下一个待发送的序号
    private int sndNxt;
    // 接收方的窗口大小
    private int peerWnd;
    // 缓存所有已发送但未确认的数据
    private final Map<Integer, byte[]> unackedData;
    // 缓存待发送的数据
    private final TreeMap<Integer, byte[]> pendingData;
    // 缓存已发送但未确认的包
    private final Map<Integer, Packet> unackedPackets;

    public TCPSendBuffer(int initialSequenceNumber) {
        this.sndUna = initialSequenceNumber;
        this.sndNxt = initialSequenceNumber;
        this.sndWnd = 1024;  // 发送窗口初始化为1024字节
        this.peerWnd = 1024; // 初始假设对方接收窗口为1024字节
        this.unackedData = new ConcurrentHashMap<>();
        this.pendingData = new TreeMap<>();
        this.unackedPackets = new ConcurrentHashMap<>();
    }

    /**
     * 添加待发送的数据
     * @return true如果数据被成功添加到缓冲区
     */
    public synchronized boolean put(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        // 检查是否超出发送窗口
        if (sndNxt + data.length - sndUna > sndWnd) {
            return false;
        }

        // 将数据添加到待发送队列
        pendingData.put(sndNxt, data);
        return true;
    }

    /**
     * 获取下一个可以发送的数据包
     * @return 返回可发送的数据，如果没有可发送的数据则返回null
     */
    public synchronized SendData getNextData() {
        // 检查是否有数据可以发送
        if (pendingData.isEmpty()) {
            return null;
        }

        // 检查是否在发送窗口内
        Map.Entry<Integer, byte[]> firstEntry = pendingData.firstEntry();
        if (firstEntry == null) {
            return null;
        }

        int seqNum = firstEntry.getKey();
        // 检查是否在对方接收窗口内
        if (seqNum >= sndUna + peerWnd) {
            return null;
        }

        byte[] data = firstEntry.getValue();
        // 从待发送队列移除，加入未确认队列
        pendingData.remove(seqNum);
        unackedData.put(seqNum, data);


        return new SendData(seqNum, data);
    }

    /**
     * 处理收到的ACK
     * @param ackNum 确认号（表示序号小于ackNum的数据都已被确认）
     */
    public synchronized void handleAck(int ackNum) {
        // sndUna: 它指向的是已发送但未收到确认的第一个字节的序列号 发送报文时更新
        // sndUxt: 它指向未发送但可发送范围的第一个字节的序列号 收到ACK报文时更新
        if (ackNum >= sndUna && ackNum <= sndNxt) {
            sndUna = ackNum;

            // 移除已确认的数据
            Iterator<Map.Entry<Integer, byte[]>> it = unackedData.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, byte[]> entry = it.next();
                if (entry.getKey() + entry.getValue().length <= ackNum) {
                    it.remove();
                }
            }

            // 移除已确认的包
            Iterator<Map.Entry<Integer, Packet>> packetIt = unackedPackets.entrySet().iterator();
            while (packetIt.hasNext()) {
                Map.Entry<Integer, Packet> entry = packetIt.next();
                if (entry.getKey() < ackNum) {
                    packetIt.remove();
                }
            }
        } else {
            System.out.println("收到无效的ACK: " + ackNum + ", sndUna=" + sndUna + ", sndNxt=" + sndNxt);
        }
    }

    /**
     * 更新对方的接收窗口大小
     */
    public synchronized void updatePeerWindow(int windowSize) {
        this.peerWnd = windowSize;
    }

    /**
     * 获取可用窗口大小
     */
    public synchronized int getAvailableWindow() {
        return Math.min(sndWnd, peerWnd) - (sndNxt - sndUna);
    }

    /**
     * 获取已发送但未确认的数据
     */
    public synchronized Map<Integer, byte[]> getUnackedData() {
        return new HashMap<>(unackedData);
    }

    /**
     * 获取发送窗口大小
     */
    public int getSndWnd() {
        return sndWnd;
    }

    /**
     * 设置发送窗口大小
     */
    public void setSndWnd(int sndWnd) {
        this.sndWnd = sndWnd;
    }

    /**
     * 获取下一个发送序号
     */
    public int getSndNxt() {
        return sndNxt;
    }

    /**
     * 更新下一个发送序号
     */
    public synchronized void updateSndNxt(int seqNum) {
        // 只有当新序号大于当前序号时才更新
        if (seqNum > sndNxt) {
            sndNxt = seqNum;
        }
    }

    public synchronized void updateSndNnd(int seqNum) {
        if (seqNum > sndUna) {
            sndUna = seqNum;
        }
    }

    /**
     * 获取已确认的序号
     */
    public int getSndUna() {
        return sndUna;
    }

    /**
     * 添加未确认的包
     */
    public void addUnackedPacket(Packet packet) {
        // 只保存包，不更新序号
        unackedPackets.put(packet.getSeqNum(), packet);
    }

    /**
     * 获取未确认的包
     */
    public Packet getUnackedPacket(int seqNum) {
        return unackedPackets.get(seqNum);
    }

    /**
     * 清理缓冲区
     */
    public void clear() {
        unackedData.clear();
        pendingData.clear();
        unackedPackets.clear();
    }

    /**
     * 检查是否所有数据都已确认
     */
    public synchronized boolean allDataAcked() {
        return unackedData.isEmpty() && unackedPackets.isEmpty();
    }

    /**
     * 获取发送缓冲区状态的字符串表示
     */
    @Override
    public String toString() {
        return String.format(
            "SendBuffer[sndUna=%d, sndNxt=%d, sndWnd=%d, peerWnd=%d, unackedData=%d, pendingData=%d]",
            sndUna,
            sndNxt,
            sndWnd,
            peerWnd,
            unackedData.size(),
            pendingData.size()
        );
    }

    /**
     * 数据发送结构
     */
    public static class SendData {
        private final int seqNum;
        private final byte[] data;

        public SendData(int seqNum, byte[] data) {
            this.seqNum = seqNum;
            this.data = data;
        }

        public int getSeqNum() {
            return seqNum;
        }

        public byte[] getData() {
            return data;
        }
    }
}
