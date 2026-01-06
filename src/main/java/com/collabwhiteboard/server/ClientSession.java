package com.collabwhiteboard.server;

import com.collabwhiteboard.common.ProtocolIO;
import com.collabwhiteboard.common.ProtocolMessage;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientSession implements Runnable {

    private final int clientId;
    private final Socket socket;
    private final ServerCore server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private String displayName;

    public ClientSession(int clientId, Socket socket, ServerCore server) {
        this.clientId = clientId;
        this.socket = socket;
        this.server = server;
        this.displayName = "Client-" + clientId;
    }

    public int getClientId() {
        return clientId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void send(ProtocolMessage message) {
        try {
            ProtocolIO.sendMessage(socket, message);
        } catch (IOException e) {
            server.removeClient(clientId);
            close();
        }
    }

    @Override
    public void run() {
        try {
            while (running.get() && !socket.isClosed()) {
                ProtocolMessage message = ProtocolIO.readMessage(socket);
                server.handleIncoming(clientId, message);
            }
        } catch (IOException e) {
            // Client disconnected or protocol error.
        } finally {
            server.removeClient(clientId);
            close();
        }
    }

    public void close() {
        running.set(false);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}


