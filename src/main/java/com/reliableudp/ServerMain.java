package com.reliableudp;

public class ServerMain {
    private static final int SERVER_PORT = 9876;

    public static void main(String[] args) {
        try {
            System.out.println("启动服务器在端口: " + SERVER_PORT);
            ReliableUDPServer server = new ReliableUDPServer(SERVER_PORT);
            server.start();
        } catch (Exception e) {
            System.err.println("服务器错误: " + e.getMessage());
        }
    }
}
