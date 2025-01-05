package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.Random;

public class ReliableUDPClient {
    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final int serverPort;
    private final TCPStateMachine tcpStateMachine;
    private volatile boolean running;
    private final Thread receiveThread;

    public ReliableUDPClient(String host, int port) throws IOException {
        this.socket = new DatagramSocket();
        this.serverAddress = InetAddress.getByName(host);
        this.serverPort = port;
        this.tcpStateMachine = new TCPStateMachine(socket, serverAddress, port, this::handleData, false);
        this.running = false;

        // 创建接收线程
        this.receiveThread = new Thread(this::receiveLoop);
    }

    /**
     * 建立连接
     */
    public void connect() throws IOException {
        running = true;
        receiveThread.start();
        tcpStateMachine.connect();
    }

    /**
     * 发送数据
     */
    public void send(byte[] data) throws IOException {
        tcpStateMachine.send(data);
    }

    /**
     * 发送数据，可选是否使用随机延迟
     */
    public void send(byte[] data, boolean useDelay) throws IOException {
        tcpStateMachine.send(data, useDelay);
    }

    /**
     * 延迟发送数据包
     */
    private void sendWithDelay(DatagramPacket packet) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                // 随机延迟0-1000ms
                Thread.sleep(new Random().nextInt(1000));
                socket.send(packet);
                System.out.println("延迟发送数据包，大小: " + packet.getLength() + " 字节");
            } catch (Exception e) {
                System.err.println("延迟发送失败: " + e.getMessage());
            }
        });
        executor.shutdown();
    }

    /**
     * 乱序发送数据
     */
    public void sendOutOfOrder(byte[][] dataChunks, long[] delays) throws IOException {
        if (tcpStateMachine.getState() != TCPStateMachine.State.ESTABLISHED) {
            throw new IOException("Connection not established");
        }

        if (dataChunks.length != delays.length) {
            throw new IllegalArgumentException(
                String.format("Data chunks and delays arrays must have the same length (chunks: %d, delays: %d)",
                            dataChunks.length, delays.length)
            );
        }

        // 创建发送线程
        Thread[] sendThreads = new Thread[dataChunks.length];
        for (int i = 0; i < dataChunks.length; i++) {
            final int index = i;
            final byte[] data = dataChunks[index];
            final long delay = delays[index];

            sendThreads[i] = new Thread(() -> {
                try {
                    System.out.println("Thread " + index + " - 准备发送数据，延迟: " + delay + "ms");
                    Thread.sleep(delay);
                    tcpStateMachine.send(data);
                } catch (Exception e) {
                    System.err.println("Thread " + index + " - 发送错误: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            sendThreads[i].start();
        }

        // 等待所有线程完成
        for (Thread thread : sendThreads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                System.err.println("等待发送线程时被中断: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() throws IOException {
        tcpStateMachine.disconnect();
        running = false;
        receiveThread.interrupt();
        socket.close();
    }

    /**
     * 接收循环
     */
    private void receiveLoop() {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running) {
            try {
                socket.receive(packet);

                // 处理数据包
                try {
                    // 创建一个新的字节数组，只包含实际接收到的数据
                    byte[] actualData = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), actualData, 0, packet.getLength());
                    
                    Packet receivedPacket = Packet.fromBytes(actualData);
                    if (tcpStateMachine != null) {
                        tcpStateMachine.handlePacket(receivedPacket);
                    }
                } catch (Exception e) {
                    System.err.println("处理数据包错误: " + e.getMessage());
                    e.printStackTrace();
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("接收数据错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 处理接收到的数据
     */
    private void handleData(byte[] data) {
        // 这里可以添加数据处理逻辑
        System.out.println("收到数据: " + new String(data));
    }
}
