package com.reliableudp;

import java.util.HashMap;
import java.util.Map;

/**
 * 环形缓冲区实现，用于处理TCP风格的序列号
 */
public class CircularBuffer {
    private final int size;               // 缓冲区大小
    private final byte[][] buffer;        // 数据缓冲区
    private final boolean[] isOccupied;   // 标记位图
    private int base;                     // 窗口基序号
    private int nextSeq;                  // 下一个期望的序号
    private final StringBuilder messageBuffer; // 消息缓冲区
    private int availableWindow;          // 当前可用窗口大小

    public CircularBuffer(int size) {
        this.size = size;
        this.buffer = new byte[size][];
        this.isOccupied = new boolean[size];
        this.base = 0;
        this.nextSeq = 0;
        this.messageBuffer = new StringBuilder();
        this.availableWindow = size;
    }

    /**
     * 检查序号是否在接收窗口内
     */
    public boolean isInWindow(int seqNum) {
        // TCP 接收窗口范围是 [rcv_nxt, rcv_nxt + rcv_wnd)
        int diff = seqNum - nextSeq;
        return diff >= 0 && diff < availableWindow;
    }

    /**
     * 检查是否是过期的包（在窗口左侧）
     */
    public boolean isObsolete(int seqNum) {
        return seqNum < nextSeq;
    }

    /**
     * 将数据包放入缓冲区
     * @return 返回处理结果：
     *         1 = 成功放入缓冲区
     *         0 = 重复的包（需要重发ACK）
     *         -1 = 窗口外的包（需要丢弃并发送当前窗口大小）
     */
    public int put(int seqNum, byte[] data) {
        // 检查是否是过期的包
        if (isObsolete(seqNum)) {
            System.out.println("收到重复数据包，序号: " + seqNum + "，期望序号: " + nextSeq + 
                             "，需要重发ACK");
            return 0; // 重复的包，需要重发ACK
        }

        // 检查是否在接收窗口内
        if (!isInWindow(seqNum)) {
            System.out.println("收到窗口外数据包，序号: " + seqNum + 
                             "，当前窗口: [" + nextSeq + " - " + (nextSeq + availableWindow - 1) + "]" +
                             "，需要发送当前窗口大小");
            return -1; // 窗口外的包，需要发送当前窗口大小
        }

        // 放入缓冲区
        int index = seqNum % size;
        if (!isOccupied[index]) {
            buffer[index] = data;
            isOccupied[index] = true;
            System.out.println("缓存数据包，序号: " + seqNum + "，窗口: [" + 
                             nextSeq + " - " + (nextSeq + availableWindow - 1) + "]");
            return 1; // 成功放入缓冲区
        }

        // 数据包已经在缓冲区中
        System.out.println("数据包已在缓冲区中，序号: " + seqNum);
        return 0;
    }

    /**
     * 处理连续的数据包
     */
    public boolean processContiguous() {
        boolean processed = false;
        int processedCount = 0;
        
        // 处理缓冲区内的连续数据
        while (isOccupied[nextSeq % size]) {
            // 处理数据
            messageBuffer.append(new String(buffer[nextSeq % size]));
            
            // 清除缓冲区
            buffer[nextSeq % size] = null;
            isOccupied[nextSeq % size] = false;
            
            nextSeq++;
            processedCount++;
            
            if (nextSeq - base >= size) {
                // 移动窗口基序号
                base = nextSeq;
            }
            
            processed = true;
        }

        if (processedCount > 0) {
            System.out.println("处理了 " + processedCount + " 个数据包，当前窗口: [" + 
                             nextSeq + " - " + (nextSeq + availableWindow - 1) + "]");
        }
        
        return processed;
    }

    /**
     * 获取缓冲区状态信息
     */
    public String getBufferStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Window [").append(nextSeq).append(" - ").append(nextSeq + availableWindow - 1)
              .append("], Available: ").append(availableWindow)
              .append(", Buffer: ");
        
        for (int i = 0; i < size; i++) {
            if (isOccupied[i]) {
                status.append("1");
            } else {
                status.append("0");
            }
        }
        
        return status.toString();
    }

    /**
     * 检查是否需要发送窗口更新
     */
    public boolean needsWindowUpdate() {
        // 当可用空间超过窗口大小的一半时发送更新
        return availableWindow >= size / 2;
    }

    /**
     * 获取并清空消息缓冲区
     */
    public String getAndClearMessage() {
        // 在返回消息前，再次尝试处理所有可能的数据
        while (processContiguous()) {
            // 继续处理直到没有更多连续数据
        }
        
        String message = messageBuffer.toString();
        messageBuffer.setLength(0);
        return message;
    }

    // Getters
    public int getBase() { return base; }
    public int getNextSeq() { return nextSeq; }
    public int getSize() { return size; }
    public int getAvailableWindow() { return availableWindow; }
}
