package com.collabwhiteboard.server;

public class ServerMain {

    public static void main(String[] args) {
        int port = 5050;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        ServerCore core = new ServerCore(port);
        core.run();
    }
}


