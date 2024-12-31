package com.reliableudp;

public class OutOfOrderTest {
    private static final int SERVER_PORT = 9876;

    public static void main(String[] args) {
        // 启动服务器线程
        Thread serverThread = new Thread(() -> {
            try {
                System.out.println("启动服务器...");
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

        // 启动客户端并测试乱序发送
        try {
            System.out.println("启动客户端...");
            ReliableUDPClient client = new ReliableUDPClient("localhost", SERVER_PORT);
            
            // 连接服务器
            System.out.println("连接服务器...");
            client.connect();
            System.out.println("连接建立成功");

            // 等待连接完全建立
            Thread.sleep(1000);

            // 准备测试数据 - 模拟序号回绕
            int numMessages = 20;  // 发送超过窗口大小的消息
            String[] messages = new String[numMessages];
            int[] delays = new int[numMessages];
            
            // 生成测试数据
            for (int i = 0; i < numMessages; i++) {
                messages[i] = String.format("[%02d] 这是第%d条消息\n", i, i);
                // 使用随机延迟，但确保最后一个消息最后到达
                if (i == numMessages - 1) {
                    delays[i] = 1000;
                } else {
                    delays[i] = (int)(Math.random() * 500);
                }
            }

            // 发送乱序消息
            System.out.println("\n开始发送乱序消息（测试序号回绕）...");
            System.out.println("消息数量: " + numMessages + "，接收窗口大小: 16");
            client.sendOutOfOrder(messages, delays);

            // 等待所有消息处理完成
            Thread.sleep(3000);

            // 断开连接
            System.out.println("\n断开连接...");
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
