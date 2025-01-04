package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;

public class ReliableUDPServer {
    private final DatagramSocket socket;
    private TCPStateMachine tcpStateMachine;  
    private volatile boolean running;
    private final Thread receiveThread;
    private InetAddress clientAddress;
    private int clientPort;
    private ConcurrentHashMap<String, TCPStateMachine> clients = new ConcurrentHashMap<>();

    public ReliableUDPServer(int port) throws IOException {
        try {
            this.socket = new DatagramSocket(port);
            this.running = false;
            this.receiveThread = new Thread(this::receiveLoop);
            // 初始化为null，等待客户端连接时设置
            this.tcpStateMachine = null;
        } catch (Exception e) {
            System.err.println("创建服务器失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * 启动服务器
     */
    public void start() {
        try {
            running = true;
            receiveThread.start();
            System.out.println("服务器启动在端口: " + socket.getLocalPort());
        } catch (Exception e) {
            System.err.println("启动服务器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 停止服务器
     */
    public void stop() {
        try {
            running = false;
            receiveThread.interrupt();
            socket.close();
        } catch (Exception e) {
            System.err.println("停止服务器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 接收循环
     */
    private void receiveLoop() {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                socket.receive(packet);

                // 解析数据包
                byte[] actualData = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, actualData, 0, packet.getLength());
                Packet receivedPacket = Packet.fromBytes(actualData);

                // 打印接收到的数据包
                //System.out.println("收到数据包: " + receivedPacket);

                // 如果是SYN包，创建新的连接
                if (receivedPacket.isSYN() && (tcpStateMachine == null || tcpStateMachine.getState() == TCPStateMachine.State.LISTEN)) {
                    handleNewClient(packet.getAddress(), packet.getPort());
                }

                // 处理数据包
                if (tcpStateMachine != null) {
                    tcpStateMachine.handlePacket(receivedPacket);
                }

                // 重置缓冲区
                packet.setLength(buffer.length);
            } catch (IOException e) {
                System.err.println("接收数据包失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 处理新客户端连接
     */
    private void handleNewClient(InetAddress clientAddress, int clientPort) {
        try {
            System.out.println("新客户端连接: " + clientAddress + ":" + clientPort);

            // 创建新的TCP状态机（服务器模式）
            TCPStateMachine newStateMachine = new TCPStateMachine(
                socket,
                clientAddress,
                clientPort,
                this::handleData,  // 数据处理回调
                true              // 服务器模式
            );

            // 替换旧的状态机
            if (tcpStateMachine != null) {
                // TODO: 可能需要优雅地关闭旧连接
            }
            tcpStateMachine = newStateMachine;
            clients.put(getClientKey(clientAddress, clientPort), newStateMachine);
        } catch (Exception e) {
            System.err.println("创建TCP状态机失败: " + e.getMessage());
            e.printStackTrace();
            // 重置客户端信息
            clientAddress = null;
            clientPort = 0;
        }
    }

    private String getClientKey(InetAddress clientAddress, int clientPort) {
        return clientAddress.getHostAddress() + ":" + clientPort;
    }

    /**
     * 处理接收到的数据
     */
    private void handleData(byte[] data) {
        // 这里可以添加数据处理逻辑
        System.out.println("收到数据: " + new String(data));

        try {
            // 回显数据
            if (tcpStateMachine != null) {
                tcpStateMachine.send(data);
            }
        } catch (Exception e) {
            System.err.println("发送响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        ReliableUDPServer server = null;
        try {
            server = new ReliableUDPServer(12345);
            server.start();

            // 等待用户输入来停止服务器
            System.out.println("按回车键停止服务器...");
            System.in.read();
        } catch (Exception e) {
            System.err.println("服务器错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }
}
