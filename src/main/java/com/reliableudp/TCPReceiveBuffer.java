package com.reliableudp;

import java.util.function.Consumer;

/**
 * TCP风格的接收缓冲区实现
 * 参考TCP的实现，维护三个区域：
 * 1. 已接收并确认的数据
 * 2. 已接收但未按序的数据
 * 3. 未接收的数据
 */
public class TCPReceiveBuffer {
    private final byte[] buffer;           // 接收缓冲区
    private final boolean[] received;      // 标记每个字节是否已接收
    private final int capacity;            // 缓冲区容量
    private int rcvNxt;                    // 下一个期望收到的字节序号(RCV.NXT)
    private int rcvWnd;                    // 接收窗口大小(RCV.WND)
    private final Consumer<byte[]> dataConsumer; // 数据消费者
    private int baseSeqNum;               // 初始序号

    public TCPReceiveBuffer(int capacity, Consumer<byte[]> consumer, int seq, int ack) {
        this.capacity = capacity;
        this.buffer = new byte[capacity];
        this.received = new boolean[capacity];
        this.rcvNxt = ack;  //
        this.rcvWnd = capacity;  // 接收窗口大小设置为缓冲区容量
        this.dataConsumer = consumer;
        this.baseSeqNum = seq;  //
    }

    /**
     * 设置初始序号，在收到SYN时调用
     */
    public void setInitialSequenceNumber(int isn) {
        this.baseSeqNum = isn;
        this.rcvNxt = isn + 1;  // 设置为对方的ISN + 1，按TCP标准
    }

    /**
     * 检查序号是否在接收窗口内
     * 考虑序号环绕的情况
     */
    private boolean isInWindow(int seqNum) {
        // 计算相对于初始序号的偏移
        long relativeSeqNum = (seqNum - baseSeqNum) & 0xFFFFFFFFL;
        long relativeRcvNxt = (rcvNxt - baseSeqNum) & 0xFFFFFFFFL;
        long windowEnd = (relativeRcvNxt + rcvWnd) & 0xFFFFFFFFL;

        // 检查序号是否在窗口范围内
        if (windowEnd >= relativeRcvNxt) {
            // 窗口没有环绕
            return relativeSeqNum >= relativeRcvNxt && relativeSeqNum < windowEnd;
        } else {
            // 窗口环绕了
            return relativeSeqNum >= relativeRcvNxt || relativeSeqNum < windowEnd;
        }
    }

    /**
     * 接收数据
     * @param seqNum 数据的序号
     * @param data 数据内容
     * @return 1=成功接收, 0=重复数据, -1=窗口外数据
     */
    public synchronized int receive(int seqNum, byte[] data) {
        if (data == null || data.length == 0) {
            return 1; // 空数据包，直接返回成功
        }

        // 1. 检查是否是过期的数据
        long relativeSeqNum = (seqNum - baseSeqNum) & 0xFFFFFFFFL;
        long relativeRcvNxt = (rcvNxt - baseSeqNum) & 0xFFFFFFFFL;
        
        if (relativeSeqNum < relativeRcvNxt) {
            System.out.println("重复数据: seqNum=" + seqNum + "(相对:" + relativeSeqNum + 
                             "), rcvNxt=" + rcvNxt + "(相对:" + relativeRcvNxt + ")");
            return 0; // 重复数据
        }

        // 2. 检查是否在接收窗口内
        if (!isInWindow(seqNum)) {
            System.out.println("窗口外数据: seqNum=" + seqNum + 
                             ", window=[" + rcvNxt + "," + (rcvNxt + rcvWnd - 1) + "]" +
                             ", baseSeqNum=" + baseSeqNum);
            return -1; // 窗口外数据
        }

        // 3. 计算在缓冲区中的相对位置
        int bufferOffset = (int)(relativeSeqNum % capacity);
        
        // 4. 检查是否有足够空间
        if (data.length > rcvWnd) {
            System.out.println("数据太大: length=" + data.length + ", rcvWnd=" + rcvWnd);
            return -1;
        }

        // 5. 将数据复制到缓冲区
        System.out.println("接收数据: seqNum=" + seqNum + "(相对:" + relativeSeqNum + 
                         "), length=" + data.length + ", bufferOffset=" + bufferOffset);
        
        for (int i = 0; i < data.length; i++) {
            int pos = (bufferOffset + i) % capacity;
            buffer[pos] = data[i];
            received[pos] = true;
        }

        // 更新接收窗口大小
        int usedSpace = 0;
        for (boolean r : received) {
            if (r) usedSpace++;
        }
        rcvWnd = capacity - usedSpace;
        System.out.println("更新接收窗口: rcvWnd=" + rcvWnd + ", 已使用空间=" + usedSpace);

        // 6. 处理连续数据
        processContiguousData();

        return 1;
    }

    /**
     * 处理连续的数据
     */
    private synchronized void processContiguousData() {
        // 计算相对位置
        long relativeRcvNxt = (rcvNxt - baseSeqNum) & 0xFFFFFFFFL;
        int bufferOffset = (int)(relativeRcvNxt % capacity);

        while (received[bufferOffset]) {
            // 寻找连续数据的结束位置
            int contiguousEnd = bufferOffset;
            while (received[contiguousEnd % capacity]) {
                contiguousEnd++;
                if (contiguousEnd - bufferOffset >= capacity) {
                    break;
                }
            }

            // 计算连续数据长度
            int length = contiguousEnd - bufferOffset;
            if (length == 0) {
                break;
            }

            // 提取连续数据
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                int pos = (bufferOffset + i) % capacity;
                data[i] = buffer[pos];
                received[pos] = false; // 清除标记
            }

            // 更新接收窗口
            rcvNxt = (int)((baseSeqNum + relativeRcvNxt + length) & 0xFFFFFFFFL);
            
            // 计算已使用的缓冲区空间
            int usedSpace = 0;
            for (boolean r : received) {
                if (r) usedSpace++;
            }
            // 更新接收窗口大小为剩余可用空间
            rcvWnd = capacity - usedSpace;

            System.out.println("处理连续数据: length=" + length + 
                             ", 新的rcvNxt=" + rcvNxt + 
                             "(相对:" + ((rcvNxt - baseSeqNum) & 0xFFFFFFFFL) + ")" +
                             ", 新的rcvWnd=" + rcvWnd +
                             ", 已使用空间=" + usedSpace);

            // 传递数据给消费者
            if (dataConsumer != null) {
                dataConsumer.accept(data);
            }

            // 更新下一轮循环的起始位置
            relativeRcvNxt = (rcvNxt - baseSeqNum) & 0xFFFFFFFFL;
            bufferOffset = (int)(relativeRcvNxt % capacity);
        }
    }

    /**
     * 重置接收缓冲区
     */
    public synchronized void reset() {
        rcvNxt = 0;
        rcvWnd = capacity;
        java.util.Arrays.fill(received, false);
        java.util.Arrays.fill(buffer, (byte)0);
    }

    /**
     * 获取下一个期望的序号
     */
    public int getRcvNxt() {
        return rcvNxt;
    }

    /**
     * 获取当前接收窗口大小
     */
    public int getRcvWnd() {
        return rcvWnd;
    }

    /**
     * 获取缓冲区状态信息
     */
    public String getStatus() {
        StringBuilder status = new StringBuilder();
        status.append("接收缓冲区状态:\n");
        status.append("Base ISN = ").append(baseSeqNum).append("\n");
        status.append("RCV.NXT = ").append(rcvNxt).append("\n");
        status.append("RCV.WND = ").append(rcvWnd).append("\n");

        // 统计已接收数据的区间
        status.append("已接收数据区间: ");
        boolean inInterval = false;
        int startPos = -1;
        
        for (int i = 0; i < capacity; i++) {
            if (received[i] && !inInterval) {
                inInterval = true;
                startPos = i;
            } else if (!received[i] && inInterval) {
                inInterval = false;
                status.append("[").append(baseSeqNum + startPos).append("-")
                      .append(baseSeqNum + i - 1).append("] ");
            }
        }
        
        if (inInterval) {
            status.append("[").append(baseSeqNum + startPos).append("-")
                  .append(baseSeqNum + capacity - 1).append("]");
        }

        return status.toString();
    }

    /**
     * 获取接收缓冲区状态的字符串表示
     */
    @Override
    public String toString() {
        int usedSpace = 0;
        for (boolean r : received) {
            if (r) usedSpace++;
        }
        return String.format(
            "ReceiveBuffer[rcvNxt=%d, rcvWnd=%d, bufferedData=%d]",
            rcvNxt,
            capacity - usedSpace,
            usedSpace
        );
    }
}
