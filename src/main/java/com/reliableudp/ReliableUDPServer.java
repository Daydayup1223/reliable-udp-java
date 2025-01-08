package com.reliableudp;

import java.io.IOException;
import java.net.*;

import com.reliableudp.TCPConnectionManager.BaseConnection;
import com.reliableudp.TCPConnectionManager.RequestSock;
import com.reliableudp.TCPConnectionManager.TCPConnection;
import com.reliableudp.TCPStateMachine.State;

public class ReliableUDPServer {
    private final DatagramSocket serverSocket;
    private volatile boolean running;
    private final Thread receiveThread;
    private final TCPConnectionManager connectionManager;
    private final byte[] receiveBuffer = new byte[1024];
    private final int port;

    public ReliableUDPServer(int port) throws IOException {
        try {
            this.port = port;
            this.serverSocket = new DatagramSocket(port);
            this.running = false;
            this.receiveThread = new Thread(this::receiveLoop);
            this.connectionManager = new TCPConnectionManager(100, 50); // 最大半连接队列100，最大全连接队列50
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
            System.out.println("服务器启动在端口: " + serverSocket.getLocalPort());
        } catch (Exception e) {
            System.err.println("启动服务器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 停止服务器
     */
    public void stop() {
        running = false;
        receiveThread.interrupt();
        serverSocket.close();
    }

    /**
     * 接收循环
     */
    private void receiveLoop() {
        while (running) {
            try {
                // 接收数据包
                DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                serverSocket.receive(packet);

                // 获取客户端信息
                String clientIP = packet.getAddress().getHostAddress();
                int clientPort = packet.getPort();

                // 获取或创建连接
                String connectionKey = clientIP + ":" + clientPort;
                BaseConnection connection = connectionManager.getConnection(connectionKey);
                
                if (connection == null) {
                    // 检查是否已经在半连接队列中
                    RequestSock requestSock = connectionManager.findRequestInSynQueue(clientIP, clientPort);
                    
                    if (requestSock == null) {
                        // 创建新的连接请求
                        requestSock = new RequestSock(
                            clientIP,
                            clientPort,
                            port,
                            (int)System.currentTimeMillis() // 简单的序列号生成
                        );
                        requestSock.setSocket(serverSocket);
                        connectionManager.addToSynQueue(requestSock);
                        requestSock.setState(State.LISTEN);
                    }

                    // 处理数据包
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                    Packet tcpPacket = Packet.fromBytes(data);

                    requestSock.getTcpStateMachine().handlePacket(tcpPacket, requestSock);


                    if (requestSock.state == State.ESTABLISHED) {
                        connectionManager.moveToAcceptQueue(requestSock);
                        TCPConnection newConnection = requestSock.promoteToFullConnection();
                        connectionManager.putConnection(connectionKey, newConnection);
                    }
                } else {
                    // 处理现有连接的数据
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                    Packet tcpPacket = Packet.fromBytes(data);
                    connection.getTcpStateMachine().handlePacket(tcpPacket, connection);
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("接收数据失败: " + e.getMessage());
                }
            }
        }
    }
}
