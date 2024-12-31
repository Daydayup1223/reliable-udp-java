package com.reliableudp;

import java.util.Map;
import java.util.TreeMap;

public class ReceiveBuffer {
    private final TreeMap<Integer, byte[]> buffer;  // 有序存储数据包
    private int expectedSeqNum;                     // 期望的下一个序号
    private int windowSize;                         // 接收窗口大小
    private StringBuilder messageBuffer;            // 完整消息缓冲区

    public ReceiveBuffer(int windowSize) {
        this.buffer = new TreeMap<>();
        this.expectedSeqNum = 0;
        this.windowSize = windowSize;
        this.messageBuffer = new StringBuilder();
    }

    /**
     * 尝试将数据包放入缓冲区
     * @param seqNum 序列号
     * @param data 数据
     * @return true 如果数据包在接收窗口内并成功缓存
     */
    public boolean putPacket(int seqNum, byte[] data) {
        // 检查序号是否在接收窗口内
        if (seqNum < expectedSeqNum || seqNum >= expectedSeqNum + windowSize) {
            return false;
        }

        // 存储数据包
        buffer.put(seqNum, data);
        return true;
    }

    /**
     * 处理缓冲区中的有序数据
     * @return 如果有新的有序数据被处理则返回true
     */
    public boolean processOrderedData() {
        boolean processed = false;
        
        // 处理所有连续的数据包
        while (!buffer.isEmpty() && buffer.firstKey() == expectedSeqNum) {
            byte[] data = buffer.remove(expectedSeqNum);
            messageBuffer.append(new String(data));
            expectedSeqNum++;
            processed = true;
        }
        
        return processed;
    }

    /**
     * 获取并清空消息缓冲区
     * @return 完整的消息
     */
    public String getAndClearMessage() {
        String message = messageBuffer.toString();
        messageBuffer.setLength(0);
        return message;
    }

    /**
     * 获取期望的序号
     */
    public int getExpectedSeqNum() {
        return expectedSeqNum;
    }

    /**
     * 获取缓冲区中最大的连续序号
     */
    public int getHighestContiguousSeqNum() {
        return expectedSeqNum - 1;
    }

    /**
     * 获取接收窗口的右边界
     */
    public int getWindowEnd() {
        return expectedSeqNum + windowSize;
    }

    /**
     * 检查缓冲区是否为空
     */
    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    /**
     * 获取缓冲区中的所有序号
     */
    public String getBufferedSequenceNumbers() {
        return buffer.keySet().toString();
    }
}
