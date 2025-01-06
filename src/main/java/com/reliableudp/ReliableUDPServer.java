package com.reliableudp;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;

import com.reliableudp.TCPStateMachine.State;

public class ReliableUDPServer {
    private final DatagramSocket socket;
    private volatile boolean running;
    private final Thread receiveThread;
    private ConcurrentHashMap<String, TCPStateMachine> clientStates = new ConcurrentHashMap<>();

    public ReliableUDPServer(int port) throws IOException {
        try {
            this.socket = new DatagramSocket(port);
            this.running = false;
            this.receiveThread = new Thread(this::receiveLoop);
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

                // 处理新的客户端连接
                if (receivedPacket.isSYN()) {
                    handleNewClient(packet.getAddress(), packet.getPort(), receivedPacket);
                }

                TCPStateMachine tcpStateMachine = clientStates.get(getClientKey(packet.getAddress(), packet.getPort()));
                tcpStateMachine.handlePacket(receivedPacket);

                // 重置缓冲区
                packet.setLength(buffer.length);
            } catch (IOException e) {
                System.err.println("接收数据包失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 处理新的客户端连接
     */
    private void handleNewClient(InetAddress clientAddress, int clientPort, Packet synPacket) {
        try {
            // 创建新的TCP状态机
            TCPStateMachine tcpStateMachine = new TCPStateMachine(
                socket,
                clientAddress,
                clientPort,
                socket.getLocalPort()
            );
            // 设置状态为LISTEN
            tcpStateMachine.setState(State.LISTEN);
            // 保存状态机
            clientStates.put(getClientKey(clientAddress, clientPort), tcpStateMachine);

        } catch (Exception e) {
            System.err.println("处理新客户端连接失败: " + e.getMessage());
        }
    }

    /**
     * 发送数据到指定客户端
     */
    public void sendToClient(InetAddress clientAddress, int clientPort, byte[] data) throws IOException {
        String clientKey = getClientKey(clientAddress, clientPort);
        TCPStateMachine tcpStateMachine = clientStates.get(clientKey);
        if (tcpStateMachine != null) {
            tcpStateMachine.send(data, true);  // 设置PUSH标志
        } else {
            throw new IOException("Client not connected");
        }
    }

    private String getClientKey(InetAddress clientAddress, int clientPort) {
        return clientAddress.getHostAddress() + ":" + clientPort;
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
