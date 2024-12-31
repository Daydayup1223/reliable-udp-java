package com.reliableudp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;

public class Main {
    private static final int SERVER_PORT = 9876;

    public static void main(String[] args) {
        // 启动服务器线程
        Thread serverThread = new Thread(() -> {
            try {
                ReliableUDPServer server = new ReliableUDPServer(SERVER_PORT);
                server.start();
            } catch (Exception e) {
                System.err.println("服务器错误: " + e.getMessage());
            }
        });
        serverThread.start();

        // 等待服务器启动
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 启动客户端
        try {
            ReliableUDPClient client = new ReliableUDPClient("localhost", SERVER_PORT);
            client.connect();
            System.out.println("客户端连接成功");

            // 发送测试消息
            String message = "Hello, Reliable UDP!";
            System.out.println("客户端发送消息: " + message);
            client.send(message);

            // 等待消息处理
            Thread.sleep(2000);

            // 断开连接
            System.out.println("客户端断开连接");
            client.disconnect();
            
            // 等待连接完全关闭
            Thread.sleep(3000);
            
            client.close();
            System.out.println("客户端关闭");
            
        } catch (Exception e) {
            System.err.println("客户端错误: " + e.getMessage());
        }
    }
}
