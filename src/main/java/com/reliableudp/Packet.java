package com.reliableudp;

import java.io.*;
import java.nio.ByteBuffer;

/**
 * 数据包类，实现可靠UDP传输的基本单元
 */
public class Packet implements Serializable {
    private static final long serialVersionUID = 1L;

    // 数据包类型定义
    public static final int TYPE_SYN = 0;      // 连接建立请求包
    public static final int TYPE_SYN_ACK = 1;  // 连接建立确认包
    public static final int TYPE_ACK = 2;      // 确认包
    public static final int TYPE_DATA = 3;     // 数据包
    public static final int TYPE_FIN = 4;      // 连接断开请求包
    public static final int TYPE_FIN_ACK = 5;  // 连接断开确认包
    public static final int TYPE_WINDOW_UPDATE = 6;  // 窗口更新类型

    // 控制位标志定义（使用低4位）
    public static final int FLAG_ACK = 0x08;  // 确认应答号有效，除SYN包外必须为1
    public static final int FLAG_PSH = 0x04;  // 接收方应尽快将这个报文段交给应用层
    public static final int FLAG_SYN = 0x02;  // 连接建立请求，用于初始化序列号
    public static final int FLAG_FIN = 0x01;  // 断开连接请求，表示发送方已发送完所有数据

    private static final int HEADER_SIZE = 24;  // 实际的头部大小（字节）
    private static final int HEADER_LENGTH = 8;  // 头部长度字段的值（4位）

    private final int type;            // 数据包类型
    private final int sourcePort;      // 源端口号 (16位)
    private final int destPort;        // 目标端口号 (16位)
    private final int seqNum;          // 序列号 (32位)：随机初始值，每次发送数据时加上数据字节数
    private final int ackNum;          // 确认应答号 (32位)：期望收到的下一个序列号，表示此序列号之前的数据都已正确接收
    private final int headerLength;    // 首部长度 (4位)
    private final int flags;           // 控制位 (URG, ACK, PSH, RST, SYN, FIN)
    private final int windowSize;      // 接收窗口大小 (16位)：用于流量控制
    private final int checksum;        // 校验和 (16位)
    private final int urgentPointer;   // 紧急指针 (16位)
    private final byte[] data;         // 数据内容
    private final boolean last;        // 是否为最后一个包

    /**
     * 创建一个新的数据包
     * @param type 数据包类型
     * @param sourcePort 源端口
     * @param destPort 目标端口
     * @param seqNum 序列号（对于SYN包是初始序列号，对于数据包是累积序列号）
     * @param ackNum 确认应答号（期望收到的下一个序列号）
     * @param flags 控制位（ACK, RST, SYN, FIN等）
     * @param windowSize 接收窗口大小
     * @param data 数据内容
     * @param last 是否为最后一个包
     */
    public Packet(int type, int sourcePort, int destPort, int seqNum, int ackNum, 
                 int flags, int windowSize, byte[] data, boolean last) {
        this.type = type;
        this.sourcePort = sourcePort;
        this.destPort = destPort;
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.headerLength = HEADER_LENGTH;  // 使用4位表示的头部长度
        this.flags = flags;
        this.windowSize = windowSize;
        this.checksum = 0;  // 暂时不计算校验和
        this.urgentPointer = 0;
        this.data = data;
        this.last = last;
    }

    /**
     * 创建一个SYN包（连接建立请求）
     * @param sourcePort 源端口
     * @param destPort 目标端口
     * @param initialSeqNum 初始序列号（随机生成）
     * @param windowSize 接收窗口大小
     */
    public static Packet createSYN(int sourcePort, int destPort, int initialSeqNum, int windowSize) {
        return new Packet(TYPE_SYN, sourcePort, destPort, initialSeqNum, 0, FLAG_SYN, windowSize, null, false);
    }

    /**
     * 创建一个SYN-ACK包（连接建立响应）
     * @param sourcePort 源端口
     * @param destPort 目标端口
     * @param initialSeqNum 初始序列号（随机生成）
     * @param ackNum 确认应答号（收到的序列号+1）
     * @param windowSize 接收窗口大小
     */
    public static Packet createSYNACK(int sourcePort, int destPort, int initialSeqNum, int ackNum, int windowSize) {
        return new Packet(TYPE_SYN_ACK, sourcePort, destPort, initialSeqNum, ackNum, FLAG_SYN | FLAG_ACK, windowSize, null, false);
    }

    /**
     * 创建一个数据包
     * @param sourcePort 源端口
     * @param destPort 目标端口
     * @param seqNum 序列号
     * @param ackNum 确认应答号
     * @param data 数据内容
     * @param windowSize 接收窗口大小
     */
    public static Packet createData(int sourcePort, int destPort, int seqNum, int ackNum, byte[] data, int windowSize) {
        return new Packet(TYPE_DATA, sourcePort, destPort, seqNum, ackNum, FLAG_ACK, windowSize, data, false);
    }

    /**
     * 创建一个FIN包（连接断开请求）
     * @param sourcePort 源端口
     * @param destPort 目标端口
     * @param seqNum 序列号
     * @param ackNum 确认应答号
     * @param windowSize 接收窗口大小
     */
    public static Packet createFIN(int sourcePort, int destPort, int seqNum, int ackNum, int windowSize) {
        return new Packet(TYPE_FIN, sourcePort, destPort, seqNum, ackNum, FLAG_FIN | FLAG_ACK, windowSize, null, false);
    }

    // 辅助方法：检查控制位
    public boolean isSYN() { return (flags & FLAG_SYN) != 0; }
    public boolean isACK() { return (flags & FLAG_ACK) != 0; }
    public boolean isFIN() { return (flags & FLAG_FIN) != 0; }
    public boolean isRST() { return false; }
    
    // Getter 方法
    public int getType() { return type; }
    public int getSeqNum() { return seqNum; }
    public int getAckNum() { return ackNum; }
    public byte[] getData() { return data; }
    public int getSourcePort() { return sourcePort; }
    public int getDestPort() { return destPort; }
    public int getWindowSize() { return windowSize; }
    public boolean isLast() { return last; }
    
    /**
     * 获取数据长度，用于计算下一个序列号
     * 对于SYN和FIN包，虽然没有数据，但也要占用一个序列号
     */
    public int getDataLength() {
        if (data != null) {
            return data.length;
        }
        // SYN和FIN包虽然没有数据，但也占用一个序列号
        if (isSYN() || isFIN()) {
            return 1;
        }
        return 0;
    }

    /**
     * 获取下一个序列号
     * 序列号需要加上数据的长度（如果是SYN或FIN包，则加1）
     */
    public int getNextSeqNum() {
        return seqNum + getDataLength();
    }

    // 将数据包转换为字节数组
    public byte[] toBytes() {
        int dataLength = data != null ? data.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + dataLength);  // 使用实际的头部大小
        
        // 头部
        buffer.putShort((short)sourcePort);     // 源端口 (2 bytes)
        buffer.putShort((short)destPort);       // 目标端口 (2 bytes)
        buffer.putInt(seqNum);                  // 序列号 (4 bytes)
        buffer.putInt(ackNum);                  // 确认应答号 (4 bytes)
        
        // 首部长度和标志位打包成一个字节
        byte headerAndFlags = (byte)((headerLength << 4) | flags);
        buffer.put(headerAndFlags);             // 首部长度和标志位 (1 byte)
        
        buffer.putShort((short)windowSize);     // 窗口大小 (2 bytes)
        buffer.putShort((short)checksum);       // 校验和 (2 bytes)
        buffer.putShort((short)urgentPointer);  // 紧急指针 (2 bytes)
        buffer.put((byte)type);                 // 类型 (1 byte)
        buffer.put((byte)(last ? 1 : 0));       // 是否为最后一个包 (1 byte)
        
        // 数据部分
        if (data != null) {
            buffer.put(data);
        }

        System.out.println("Created packet: type=" + type + ", flags=" + flags + 
                         ", seqNum=" + seqNum + ", ackNum=" + ackNum +
                         ", sourcePort=" + sourcePort + ", destPort=" + destPort +
                         ", headerLength=" + headerLength + ", windowSize=" + windowSize +
                         ", dataLength=" + (data != null ? data.length : 0));  // 只显示实际数据长度
        
        return buffer.array();
    }

    // 从字节数组解析数据包
    public static Packet fromBytes(byte[] bytes) {
        try {
            if (bytes == null || bytes.length < HEADER_SIZE) {
                throw new IllegalArgumentException("Invalid packet: too short, length=" + (bytes != null ? bytes.length : 0));
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            
            // 读取头部
            int sourcePort = buffer.getShort() & 0xFFFF;
            int destPort = buffer.getShort() & 0xFFFF;
            int seqNum = buffer.getInt();
            int ackNum = buffer.getInt();
            
            byte headerAndFlags = buffer.get();
            int headerLength = (headerAndFlags >> 4) & 0x0F;
            int flags = headerAndFlags & 0x0F;  // 只取低4位作为标志位
            
            int windowSize = buffer.getShort() & 0xFFFF;
            int checksum = buffer.getShort() & 0xFFFF;
            int urgentPointer = buffer.getShort() & 0xFFFF;
            int type = buffer.get() & 0xFF;
            boolean last = buffer.get() == 1;

            // 读取数据部分（如果有的话）
            byte[] data = null;
            int remainingBytes = bytes.length - HEADER_SIZE;  // 只处理实际的数据长度
            if (remainingBytes > 0) {
                data = new byte[remainingBytes];
                buffer.get(data);
            }

            System.out.println("Parsed packet: type=" + type + ", flags=" + flags + 
                             ", seqNum=" + seqNum + ", ackNum=" + ackNum +
                             ", sourcePort=" + sourcePort + ", destPort=" + destPort +
                             ", headerLength=" + headerLength + ", windowSize=" + windowSize +
                             ", dataLength=" + (data != null ? data.length : 0));

            return new Packet(type, sourcePort, destPort, seqNum, ackNum, 
                            flags, windowSize, data, last);
        } catch (Exception e) {
            System.err.println("Error parsing packet: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // Getters
    public int getHeaderLength() { return headerLength; }
    public int getFlags() { return flags; }
    public int getChecksum() { return checksum; }
    public int getUrgentPointer() { return urgentPointer; }
}
