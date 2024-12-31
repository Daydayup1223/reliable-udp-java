package com.reliableudp;

import java.io.*;
import java.nio.ByteBuffer;

public class Packet implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // 数据包类型
    public static final int TYPE_SYN = 0;      // 连接建立请求包
    public static final int TYPE_SYN_ACK = 1;  // 连接建立确认包
    public static final int TYPE_ACK = 2;      // 确认包
    public static final int TYPE_DATA = 3;     // 数据包
    public static final int TYPE_FIN = 4;      // 连接断开请求包
    public static final int TYPE_FIN_ACK = 5;  // 连接断开确认包
    public static final int TYPE_WINDOW_UPDATE = 6;  // 窗口更新类型

    private final int type;            // 数据包类型
    private final int seqNum;          // 序列号
    private final byte[] data;         // 数据内容
    private final boolean last;      // 是否为最后一个包
    private final int windowSize;  // 窗口大小字段

    public Packet(int type, int seqNum, byte[] data, boolean last) {
        this(type, seqNum, data, last, -1);
    }

    public Packet(int type, int seqNum, byte[] data, boolean last, int windowSize) {
        this.type = type;
        this.seqNum = seqNum;
        this.data = data;
        this.last = last;
        this.windowSize = windowSize;
    }

    // 将数据包转换为字节数组
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 1 + 4 + 4 + (data != null ? data.length : 0));
        buffer.put((byte)type);                    // 类型 (1 byte)
        buffer.putInt(seqNum);               // 序列号 (4 bytes)
        buffer.put((byte)(last ? 1 : 0));  // 是否为最后一个包 (1 byte)
        buffer.putInt(data != null ? data.length : 0);  // 数据长度 (4 bytes)
        buffer.putInt(windowSize);  // 窗口大小 (4 bytes)
        if (data != null) {
            buffer.put(data);                // 数据内容
        }
        return buffer.array();
    }

    // 从字节数组解析数据包
    public static Packet fromBytes(byte[] bytes) {
        try {
            if (bytes == null || bytes.length < 14) { // 至少需要14字节的头部
                throw new IllegalArgumentException("Invalid packet: too short");
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int type = buffer.get() & 0xFF;
            int seqNum = buffer.getInt();
            boolean last = buffer.get() == 1;
            int dataLength = buffer.getInt();
            int windowSize = buffer.getInt();

            // 验证数据长度
            if (dataLength < 0 || dataLength > bytes.length - 14) {
                throw new IllegalArgumentException("Invalid packet: incorrect data length");
            }

            byte[] data = null;
            if (dataLength > 0) {
                data = new byte[dataLength];
                buffer.get(data);
            }

            return new Packet(type, seqNum, data, last, windowSize);
        } catch (Exception e) {
            System.err.println("Error parsing packet: " + e.getMessage());
            // 返回一个错误包，让上层处理
            return new Packet(-1, -1, null, false, -1);
        }
    }

    // Getters
    public int getType() {
        return type;
    }

    public int getSeqNum() {
        return seqNum;
    }

    public byte[] getData() {
        return data;
    }

    public boolean isLast() {
        return last;
    }

    public int getWindowSize() {
        return windowSize;
    }
}
