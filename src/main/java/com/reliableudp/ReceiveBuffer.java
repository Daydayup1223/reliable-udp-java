package com.reliableudp;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public class ReceiveBuffer {
    // TCP协议参数
    private static final int MAX_WINDOW_SIZE = 65535;  // 最大接收窗口
    private static final int INITIAL_WINDOW = 1024;    // 初始接收窗口
    private static final int MSS = 1460;              // Maximum Segment Size

    // 接收窗口变量
    private final int irs;              // Initial Receive Sequence number
    private int rcv_nxt;               // 期望收到的下一个字节序号
    private int rcv_wnd;               // 接收窗口大小
    private int rcv_up;                // 紧急指针
    
    // 接收缓冲区
    private final byte[] receiveBuffer;    // 环形缓冲区
    private int writeIndex;               // 写入位置
    private int readIndex;                // 读取位置
    private final TreeMap<Integer, byte[]> outOfOrderBuffer;  // 失序数据缓存
    
    // 数据处理回调
    private final Consumer<byte[]> dataConsumer;

    public ReceiveBuffer(int initialSequenceNumber, Consumer<byte[]> dataConsumer) {
        // 初始化序列号
        this.irs = initialSequenceNumber;
        this.rcv_nxt = initialSequenceNumber;
        
        // 初始化窗口
        this.rcv_wnd = INITIAL_WINDOW;
        this.rcv_up = 0;
        
        // 初始化缓冲区
        this.receiveBuffer = new byte[MAX_WINDOW_SIZE];
        this.writeIndex = 0;
        this.readIndex = 0;
        this.outOfOrderBuffer = new TreeMap<>();
        
        this.dataConsumer = dataConsumer;
    }

    /**
     * 处理接收到的数据段
     * @return true 如果数据被成功处理
     */
    public synchronized boolean processSegment(int seqNum, byte[] data) {
        // 检查序号是否在接收窗口内
        if (!isInWindow(seqNum, data.length)) {
            return false;
        }

        if (seqNum == rcv_nxt) {
            // 按序到达的数据
            writeToBuffer(data);
            rcv_nxt += data.length;
            
            // 处理可能的失序数据
            processOutOfOrderData();
            
            // 通知数据可用
            deliverData();
            return true;
        } else if (seqNum > rcv_nxt) {
            // 失序数据，缓存起来
            outOfOrderBuffer.put(seqNum, data);
            return true;
        }
        
        return false;
    }

    /**
     * 检查序号是否在接收窗口内
     */
    private boolean isInWindow(int seqNum, int length) {
        // 处理序号回绕
        int windowEnd = (rcv_nxt + rcv_wnd) & 0xFFFFFFFF;
        if (rcv_nxt <= windowEnd) {
            return seqNum >= rcv_nxt && seqNum + length <= windowEnd;
        } else {
            return seqNum >= rcv_nxt || seqNum + length <= windowEnd;
        }
    }

    /**
     * 写入数据到接收缓冲区
     */
    private void writeToBuffer(byte[] data) {
        for (byte b : data) {
            receiveBuffer[writeIndex] = b;
            writeIndex = (writeIndex + 1) % MAX_WINDOW_SIZE;
        }
    }

    /**
     * 处理失序数据
     */
    private void processOutOfOrderData() {
        while (!outOfOrderBuffer.isEmpty()) {
            Map.Entry<Integer, byte[]> entry = outOfOrderBuffer.firstEntry();
            if (entry.getKey() != rcv_nxt) {
                break;
            }
            
            byte[] data = entry.getValue();
            writeToBuffer(data);
            rcv_nxt += data.length;
            outOfOrderBuffer.remove(entry.getKey());
        }
    }

    /**
     * 传递数据给应用层
     */
    private void deliverData() {
        if (readIndex == writeIndex) {
            return;
        }

        int available;
        if (writeIndex > readIndex) {
            available = writeIndex - readIndex;
        } else {
            available = MAX_WINDOW_SIZE - readIndex + writeIndex;
        }

        byte[] data = new byte[available];
        int count = 0;
        while (readIndex != writeIndex) {
            data[count++] = receiveBuffer[readIndex];
            readIndex = (readIndex + 1) % MAX_WINDOW_SIZE;
        }

        if (dataConsumer != null) {
            dataConsumer.accept(Arrays.copyOf(data, count));
        }
    }

    /**
     * 获取当前接收窗口大小
     */
    public synchronized int getWindowSize() {
        return Math.min(MAX_WINDOW_SIZE - ((writeIndex - readIndex + MAX_WINDOW_SIZE) % MAX_WINDOW_SIZE),
                       rcv_wnd);
    }

    /**
     * 获取下一个期望的序号
     */
    public int getNextExpectedSeqNum() {
        return rcv_nxt;
    }

    /**
     * 获取接收缓冲区状态
     */
    @Override
    public String toString() {
        return String.format(
            "ReceiveBuffer[IRS=%d, RCV.NXT=%d, RCV.WND=%d, " +
            "OutOfOrder=%d, BufferUsage=%d%%]",
            irs, rcv_nxt, rcv_wnd,
            outOfOrderBuffer.size(),
            ((writeIndex - readIndex + MAX_WINDOW_SIZE) % MAX_WINDOW_SIZE) * 100 / MAX_WINDOW_SIZE
        );
    }
}
