package com.reliableudp;

import java.io.IOException;
import java.net.*;

import com.reliableudp.TCPConnectionManager.BaseConnection;
import com.reliableudp.TCPConnectionManager.RequestSock;
import com.reliableudp.TCPConnectionManager.TCPConnection;
import com.reliableudp.TCPStateMachine.State;

public class ReliableUDPClient {
    private BaseConnection connection;
    private Thread receiveThread;
    private volatile boolean running;
    private final byte[] receiveBuffer = new byte[1024];

    public ReliableUDPClient(String host, int port) throws IOException {
        connection = new RequestSock(
            host,
            port,
            9276,
            (int)System.currentTimeMillis() // 简单的序列号生成
        );
        connection.setSocket(new DatagramSocket(9276));
    }

    /**
     * 建立连接
     */
    public void connect() throws IOException {
        connection.getTcpStateMachine().connect((RequestSock)connection);
        this.receiveThread = new Thread(this::receiveLoop);
        this.receiveThread.start();
        // 创建接收线程
        this.running = true;
    }

    /**
     * 发送数据
     */
    public void send(byte[] data, boolean push) throws IOException {
        TCPConnection tcpConnection = (TCPConnection)connection;
        if (tcpConnection.getState() != TCPStateMachine.State.ESTABLISHED) {
            throw new IOException("连接未建立");
        }
        tcpConnection.getSendBuffer().writeToBuffer(data, push);
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
     * 接收循环
     */
    private void receiveLoop() {
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                connection.getSocket().receive(packet);
                
                // 处理接收到的数据
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                Packet tcpPacket = Packet.fromBytes(data);

                // 交给TCP状态机处理
                connection.getTcpStateMachine().handlePacket(tcpPacket, connection);

                if (connection.state == State.ESTABLISHED) {
                    RequestSock requestSock = (RequestSock)connection;
                    connection = requestSock.promoteToFullConnection();
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("接收数据失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 关闭连接
     */
    public void close() {
        running = false;
        connection.getSocket().close();
        receiveThread.interrupt();
    }
}
