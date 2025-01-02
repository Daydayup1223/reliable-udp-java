package com.reliableudp;

public class ServerMain {
    private static final int SERVER_PORT = 9876;
    private static ReliableUDPServer server;

    public static void main(String[] args) {
        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭服务器...");
            if (server != null) {
                server.stop();
            }
            System.out.println("服务器已关闭");
        }));

        try {
            System.out.println("=== 可靠UDP服务器 ===");
            System.out.println("启动服务器在端口: " + SERVER_PORT);
            System.out.println("按 Ctrl+C 停止服务器");
            System.out.println("==================");
            
            server = new ReliableUDPServer(SERVER_PORT);
            server.start();
        } catch (Exception e) {
            System.err.println("服务器错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
