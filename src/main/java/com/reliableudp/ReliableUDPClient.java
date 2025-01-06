package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.Random;

public class ReliableUDPClient {
    private static final int MAX_PACKET_SIZE = 1024;
    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final int serverPort;
    private final TCPStateMachine tcpStateMachine;
    private final Thread receiveThread;
    private volatile boolean running;

    public ReliableUDPClient(String host, int port) throws IOException {
        // 创建UDP socket
        this.socket = new DatagramSocket();
        this.serverAddress = InetAddress.getByName(host);
        this.serverPort = port;
        
        // 创建TCP状态机
        this.tcpStateMachine = new TCPStateMachine(
            socket,
            serverAddress,
            serverPort,
            socket.getLocalPort()
        );

        
        // 创建接收线程
        this.receiveThread = new Thread(this::receiveLoop);
        this.running = true;
        this.receiveThread.start();
        
        // 建立连接
        connect();
    }

    /**
     * 建立连接
     */
    public void connect() throws IOException {
        // 发送SYN包
        tcpStateMachine.connect();
        
        // 等待连接建立
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 5000) {  // 5秒超时
            if (tcpStateMachine.isConnected()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Connection interrupted");
            }
        }
        throw new IOException("Connection timeout");
    }

    /**
     * 发送数据
     */
    public void send(byte[] data, boolean push) throws IOException {
        tcpStateMachine.send(data, push);
    }

    /**
     * 发送数据（不设置PUSH标志）
     */
    public void send(byte[] data) throws IOException {
        send(data, false);
    }

    /**
     * 刷新发送缓冲区
     */
    public void flush() throws IOException {
        send(new byte[0], true);
    }

    /**
     * 关闭连接
     */
    public void disconnect() throws IOException {
        if (tcpStateMachine != null) {
            flush();  // 先刷新缓冲区
            tcpStateMachine.close();
            running = false;
            receiveThread.interrupt();
            socket.close();
        }
    }

    /**
     * 接收循环
     */
    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        
        while (running) {
            try {
                socket.receive(packet);
                byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                handlePacket(data);
            } catch (IOException e) {
                if (running) {
                    System.err.println("接收错误: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 处理收到的数据包
     */
    private void handlePacket(byte[] data) {
        try {
            Packet packet = Packet.fromBytes(data);
            tcpStateMachine.handlePacket(packet);
        } catch (Exception e) {
            System.err.println("处理数据包错误: " + e.getMessage());
        }
    }
}
