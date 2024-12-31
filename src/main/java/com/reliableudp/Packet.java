package com.reliableudp;

import java.io.*;
import java.nio.ByteBuffer;

public class Packet implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // 数据包类型
    public static final byte TYPE_SYN = 1;      // 连接建立请求包
    public static final byte TYPE_SYN_ACK = 2;  // 连接建立确认包
    public static final byte TYPE_ACK = 3;      // 确认包
    public static final byte TYPE_DATA = 4;     // 数据包
    public static final byte TYPE_FIN = 5;      // 连接断开请求包
    public static final byte TYPE_FIN_ACK = 6;  // 连接断开确认包

    private byte type;            // 数据包类型
    private int seqNum;          // 序列号
    private byte[] data;         // 数据内容
    private boolean isLast;      // 是否为最后一个包

    public Packet(byte type, int seqNum, byte[] data, boolean isLast) {
        this.type = type;
        this.seqNum = seqNum;
        this.data = data;
        this.isLast = isLast;
    }

    // 将数据包转换为字节数组
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 1 + 4 + (data != null ? data.length : 0));
        buffer.put(type);                    // 类型 (1 byte)
        buffer.putInt(seqNum);               // 序列号 (4 bytes)
        buffer.put((byte)(isLast ? 1 : 0));  // 是否为最后一个包 (1 byte)
        buffer.putInt(data != null ? data.length : 0);  // 数据长度 (4 bytes)
        if (data != null) {
            buffer.put(data);                // 数据内容
        }
        return buffer.array();
    }

    // 从字节数组解析数据包
    public static Packet fromBytes(byte[] bytes) {
        try {
            if (bytes == null || bytes.length < 10) { // 至少需要10字节的头部
                throw new IllegalArgumentException("Invalid packet: too short");
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte type = buffer.get();
            int seqNum = buffer.getInt();
            boolean isLast = buffer.get() == 1;
            int dataLength = buffer.getInt();

            // 验证数据长度
            if (dataLength < 0 || dataLength > bytes.length - 10) {
                throw new IllegalArgumentException("Invalid packet: incorrect data length");
            }

            byte[] data = null;
            if (dataLength > 0) {
                data = new byte[dataLength];
                buffer.get(data);
            }

            return new Packet(type, seqNum, data, isLast);
        } catch (Exception e) {
            System.err.println("Error parsing packet: " + e.getMessage());
            // 返回一个错误包，让上层处理
            return new Packet((byte) -1, -1, null, false);
        }
    }

    // Getters
    public byte getType() {
        return type;
    }

    public int getSeqNum() {
        return seqNum;
    }

    public byte[] getData() {
        return data;
    }

    public boolean isLast() {
        return isLast;
    }
}
