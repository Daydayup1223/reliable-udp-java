package com.reliableudp;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ReliableUDPTest {
    private static final int SERVER_PORT = 9876;
    private static ReliableUDPServer server;
    private static ExecutorService executor;

    public static void main(String[] args) {
        try {
            // 启动服务器
            startServer();
            
            // 等待服务器启动
            Thread.sleep(1000);
            
            // 运行测试
            runClientTest();
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 清理资源
            cleanup();
        }
    }

    private static void startServer() {
        executor = Executors.newSingleThreadExecutor();
        server = new ReliableUDPServer(SERVER_PORT);
        executor.submit(() -> {
            System.out.println("启动服务器...");
            server.start();
        });
    }

    private static void runClientTest() throws IOException {
        System.out.println("\n开始客户端测试...");
        ReliableUDPClient client = new ReliableUDPClient("localhost", SERVER_PORT);

        try {
            // 测试发送消息
            String message = "Hello, Reliable UDP!";
            System.out.println("发送消息: " + message);
            client.sendData(message.getBytes());

            // 等待服务器处理
            Thread.sleep(1000);

            // 测试发送大消息
            StringBuilder largeMessage = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                largeMessage.append("Test-").append(i).append(" ");
            }
            System.out.println("\n发送大消息 (长度: " + largeMessage.length() + " 字节)");
            client.sendData(largeMessage.toString().getBytes());

        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            client.close();
        }
    }

    private static void cleanup() {
        System.out.println("\n清理资源...");
        if (server != null) {
            server.stop();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("测试完成.");
    }
}
