package com.reliableudp;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 环形缓冲区实现，用于处理TCP风格的序列号
 */
public class CircularBuffer {
    private final int size;               // 缓冲区大小
    private final byte[][] buffer;        // 数据缓冲区
    private final boolean[] isOccupied;   // 标记位图
    private int nextSeq;                  // 下一个期望的序号
    private final BlockingQueue<String> messageQueue; // 消息队列
    private final Consumer<String> messageConsumer;   // 消息消费者

    public CircularBuffer(int size, Consumer<String> consumer) {
        this.size = size;
        this.buffer = new byte[size][];
        this.isOccupied = new boolean[size];
        this.nextSeq = 0;
        this.messageQueue = new LinkedBlockingQueue<>();
        this.messageConsumer = consumer;
        
        // 启动消费者线程
        Thread consumerThread = new Thread(() -> {
            while (true) {
                try {
                    String message = messageQueue.take();
                    messageConsumer.accept(message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    /**
     * 将数据包放入缓冲区
     * @return 返回处理结果：
     *         1 = 成功放入缓冲区
     *         0 = 重复的包（需要重发ACK）
     *         -1 = 窗口外的包（需要丢弃）
     */
    public int put(int seqNum, byte[] data) {
        // 检查是否是过期的包
        if (seqNum < nextSeq) {
            System.out.println("收到重复数据包，序号: " + seqNum + "，期望序号: " + nextSeq);
            return 0; // 重复的包，需要重发ACK
        }

        // 检查是否在接收窗口内
        if (seqNum >= nextSeq + size) {
            System.out.println("丢弃窗口外数据包，序号: " + seqNum + 
                             "，当前窗口: [" + nextSeq + " - " + (nextSeq + size - 1) + "]");
            return -1; // 窗口外的包，直接丢弃
        }

        // 放入缓冲区
        int index = seqNum % size;
        if (!isOccupied[index]) {
            buffer[index] = data;
            isOccupied[index] = true;
            System.out.println("缓存数据包，序号: " + seqNum);
            
            // 尝试处理连续的数据包
            processContiguous();
            return 1;
        }

        return 0; // 数据包已在缓冲区中
    }

    /**
     * 处理连续的数据包
     */
    private void processContiguous() {
        StringBuilder messageBuilder = new StringBuilder();
        boolean hasProcessed = false;
        
        // 处理缓冲区内的连续数据
        while (isOccupied[nextSeq % size]) {
            // 处理数据
            messageBuilder.append(new String(buffer[nextSeq % size]));
            
            // 清除缓冲区
            buffer[nextSeq % size] = null;
            isOccupied[nextSeq % size] = false;
            
            nextSeq++;
            hasProcessed = true;
        }

        // 如果有数据被处理，将其加入消息队列
        if (hasProcessed) {
            try {
                messageQueue.put(messageBuilder.toString());
                System.out.println("处理数据包至序号: " + (nextSeq - 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 获取下一个期望的序号
     */
    public int getNextSeq() {
        return nextSeq;
    }

    /**
     * 获取当前窗口大小
     */
    public int getWindowSize() {
        return size;
    }
}
