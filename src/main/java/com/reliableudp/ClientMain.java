package com.reliableudp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ClientMain {
    private static final String DEFAULT_SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 9876;
    private static ReliableUDPClient client;
    private static BufferedReader reader;

    public static void main(String[] args) {
        reader = new BufferedReader(new InputStreamReader(System.in));

        try {
            System.out.println("=== 可靠UDP客户端 ===");
            
            // 获取服务器地址
            System.out.print("请输入服务器地址 [" + DEFAULT_SERVER_HOST + "]: ");
            String serverHost = reader.readLine().trim();
            if (serverHost.isEmpty() || serverHost.equals(":")) {
                serverHost = DEFAULT_SERVER_HOST;
            } else if (serverHost.contains(":")) {
                // 如果输入包含端口号，只取主机名部分
                serverHost = serverHost.split(":")[0];
            }

            // 连接服务器
            System.out.println("\n连接到服务器 " + serverHost + ":" + SERVER_PORT);
            client = new ReliableUDPClient(serverHost, SERVER_PORT);
            client.connect();
            System.out.println("连接成功！\n");

            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n正在关闭客户端...");
                try {
                    if (client != null) {
                        client.disconnect();
                    }
                } catch (Exception e) {
                    System.err.println("关闭时发生错误: " + e.getMessage());
                }
                System.out.println("客户端已关闭");
            }));

            // 主循环
            while (true) {
                showMenu();
                String choice = reader.readLine().trim();
                
                switch (choice) {
                    case "1":
                        sendMessage();
                        break;
                    case "2":
                        sendLargeMessage();
                        break;
                    case "3":
                        System.out.println("正在退出...");
                        client.disconnect();
                        return;
                    default:
                        System.out.println("无效的选择，请重试");
                }
                System.out.println(); // 空行
            }
        } catch (Exception e) {
            System.err.println("客户端错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void showMenu() {
        System.out.println("请选择操作：");
        System.out.println("1. 发送消息");
        System.out.println("2. 发送大文件");
        System.out.println("3. 退出");
        System.out.print("请输入选择 (1-3): ");
    }

    private static void sendMessage() throws Exception {
        System.out.print("请输入要发送的消息: ");
        String message = reader.readLine();
        if (!message.isEmpty()) {
            System.out.println("发送消息: " + message);
            client.send(message.getBytes(StandardCharsets.UTF_8));
            System.out.println("消息已发送");
        }
    }

    private static void sendLargeMessage() throws Exception {
        System.out.println("生成大文件数据...");
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeMessage.append("这是第 ").append(i).append(" 个数据块 ");
        }
        System.out.println("发送大文件数据 (大小: " + largeMessage.length() + " 字节)");
        client.send(largeMessage.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("大文件数据已发送");
    }
}
