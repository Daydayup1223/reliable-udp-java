package com.reliableudp;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ClientMain {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 9876;

    public static void main(String[] args) {
        try {
            System.out.println("连接到服务器 " + SERVER_HOST + ":" + SERVER_PORT);
            ReliableUDPClient client = new ReliableUDPClient(SERVER_HOST, SERVER_PORT);
            
            // 连接服务器
            client.connect();
            System.out.println("连接成功！");
            
            // 读取用户输入并发送消息
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                System.out.print("请输入要发送的消息 (输入'exit'退出): ");
                String message = reader.readLine();
                
                if (message == null || message.equalsIgnoreCase("exit")) {
                    break;
                }
                
                // 发送消息
                System.out.println("发送消息: " + message);
                client.send(message);
            }
            
            // 断开连接
            System.out.println("断开连接...");
            client.disconnect();
            Thread.sleep(1000);
            
            // 关闭客户端
            client.close();
            System.out.println("客户端已关闭");
            
        } catch (Exception e) {
            System.err.println("客户端错误: " + e.getMessage());
        }
    }
}
