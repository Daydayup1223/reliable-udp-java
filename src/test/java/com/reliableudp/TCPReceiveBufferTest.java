package com.reliableudp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

public class TCPReceiveBufferTest {
    
    @Test
    public void testSequenceNumberWraparound() {
        // 创建一个较小的缓冲区以便测试
        final int BUFFER_SIZE = 16;
        List<byte[]> receivedData = new ArrayList<>();
        TCPReceiveBuffer buffer = new TCPReceiveBuffer(BUFFER_SIZE, receivedData::add);

        // 设置初始序号接近最大值
        int baseSeqNum = Integer.MAX_VALUE - 10;
        buffer.setInitialSequenceNumber(baseSeqNum);

        // 测试跨越序号环绕的数据包
        byte[] data1 = "Hello".getBytes();
        byte[] data2 = "World".getBytes();
        byte[] data3 = "Test!".getBytes();

        // 发送序号接近最大值的数据包
        assertEquals(1, buffer.receive(baseSeqNum + 1, data1));
        
        // 发送序号已经环绕的数据包
        assertEquals(1, buffer.receive(baseSeqNum + 6, data2));
        assertEquals(1, buffer.receive(baseSeqNum + 11, data3));

        // 验证数据是否按序接收
        assertEquals(3, receivedData.size());
        assertEquals("Hello", new String(receivedData.get(0)));
        assertEquals("World", new String(receivedData.get(1)));
        assertEquals("Test!", new String(receivedData.get(2)));
    }

    @Test
    public void testWindowWraparound() {
        final int BUFFER_SIZE = 16;
        List<byte[]> receivedData = new ArrayList<>();
        TCPReceiveBuffer buffer = new TCPReceiveBuffer(BUFFER_SIZE, receivedData::add);

        // 设置初始序号使得窗口会环绕
        int baseSeqNum = Integer.MAX_VALUE - 5;
        buffer.setInitialSequenceNumber(baseSeqNum);

        // 创建跨越窗口边界的数据包
        byte[] data1 = "ABC".getBytes();
        byte[] data2 = "DEF".getBytes();
        byte[] data3 = "GHI".getBytes();

        // 发送数据包，一些在窗口环绕前，一些在环绕后
        assertEquals(1, buffer.receive(baseSeqNum + 1, data1));
        assertEquals(1, buffer.receive(baseSeqNum + 4, data2));
        assertEquals(1, buffer.receive(baseSeqNum + 7, data3));

        // 验证数据是否正确接收
        assertEquals(3, receivedData.size());
        assertEquals("ABC", new String(receivedData.get(0)));
        assertEquals("DEF", new String(receivedData.get(1)));
        assertEquals("GHI", new String(receivedData.get(2)));
    }

    @Test
    public void testOutOfOrderWithWraparound() {
        final int BUFFER_SIZE = 16;
        List<byte[]> receivedData = new ArrayList<>();
        TCPReceiveBuffer buffer = new TCPReceiveBuffer(BUFFER_SIZE, receivedData::add);

        // 设置初始序号接近最大值
        int baseSeqNum = Integer.MAX_VALUE - 8;
        buffer.setInitialSequenceNumber(baseSeqNum);

        // 创建跨越序号环绕的乱序数据包
        byte[] data1 = "First".getBytes();
        byte[] data2 = "Second".getBytes();
        byte[] data3 = "Third".getBytes();

        // 乱序发送数据包，包括环绕前后的序号
        assertEquals(1, buffer.receive(baseSeqNum + 6, data2));  // Second
        assertEquals(1, buffer.receive(baseSeqNum + 1, data1));  // First
        assertEquals(1, buffer.receive(baseSeqNum + 12, data3)); // Third

        // 验证数据是否按正确顺序处理
        assertEquals(3, receivedData.size());
        assertEquals("First", new String(receivedData.get(0)));
        assertEquals("Second", new String(receivedData.get(1)));
        assertEquals("Third", new String(receivedData.get(2)));
    }

    @Test
    public void testWindowBoundary() {
        final int BUFFER_SIZE = 16;
        List<byte[]> receivedData = new ArrayList<>();
        TCPReceiveBuffer buffer = new TCPReceiveBuffer(BUFFER_SIZE, receivedData::add);

        // 设置初始序号
        int baseSeqNum = Integer.MAX_VALUE - 20;
        buffer.setInitialSequenceNumber(baseSeqNum);

        // 测试窗口边界的数据包
        byte[] data1 = "In".getBytes();
        byte[] data2 = "Edge".getBytes();
        byte[] data3 = "Out".getBytes();

        // 发送窗口内、窗口边缘和窗口外的数据包
        assertEquals(1, buffer.receive(baseSeqNum + 1, data1));   // 窗口内
        assertEquals(1, buffer.receive(baseSeqNum + 3, data2));   // 窗口边缘
        assertEquals(-1, buffer.receive(baseSeqNum + BUFFER_SIZE + 10, data3)); // 窗口外

        // 验证只有窗口内的数据被接收
        assertEquals(2, receivedData.size());
        assertEquals("In", new String(receivedData.get(0)));
        assertEquals("Edge", new String(receivedData.get(1)));
    }

    @Test
    public void testDuplicatePackets() {
        final int BUFFER_SIZE = 16;
        List<byte[]> receivedData = new ArrayList<>();
        TCPReceiveBuffer buffer = new TCPReceiveBuffer(BUFFER_SIZE, receivedData::add);

        // 设置初始序号接近最大值
        int baseSeqNum = Integer.MAX_VALUE - 10;
        buffer.setInitialSequenceNumber(baseSeqNum);

        // 测试重复数据包，包括环绕情况
        byte[] data1 = "Test".getBytes();
        byte[] data2 = "Dup".getBytes();

        // 首次发送
        assertEquals(1, buffer.receive(baseSeqNum + 1, data1));
        assertEquals(1, buffer.receive(baseSeqNum + 5, data2));

        // 重复发送
        assertEquals(0, buffer.receive(baseSeqNum + 1, data1));
        assertEquals(0, buffer.receive(baseSeqNum + 5, data2));

        // 验证数据只被处理一次
        assertEquals(2, receivedData.size());
        assertEquals("Test", new String(receivedData.get(0)));
        assertEquals("Dup", new String(receivedData.get(1)));
    }
}
