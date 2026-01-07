package com.collabwhiteboard.server;

import com.collabwhiteboard.common.ProtocolIO;
import com.collabwhiteboard.common.ProtocolMessage;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientSession implements Runnable {

    private final int clientId;
    private final Socket socket;
    private final ServerCore server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private String displayName;
    
    // Congestion control: bounded queue for outgoing messages to prevent memory overflow
    private final BlockingQueue<ProtocolMessage> outgoingQueue = new LinkedBlockingQueue<>(256);
    private Thread senderThread;

    public ClientSession(int clientId, Socket socket, ServerCore server) {
        this.clientId = clientId;
        this.socket = socket;
        this.server = server;
        this.displayName = "Client-" + clientId;
        
        // Configure socket for better congestion control
        try {
            socket.setTcpNoDelay(true);  // Disable Nagle's algorithm for lower latency
            socket.setSendBufferSize(65536);  // 64KB send buffer
            socket.setReceiveBufferSize(65536);  // 64KB receive buffer
        } catch (IOException e) {
            System.err.println("Warning: Failed to configure socket: " + e.getMessage());
        }
        
        // Start dedicated sender thread to prevent blocking on network I/O
        senderThread = new Thread(this::senderLoop, "ClientSession-" + clientId + "-Sender");
        senderThread.setDaemon(true);
        senderThread.start();
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
        // Add message to outgoing queue instead of sending directly
        // This prevents blocking if the socket write buffer is full
        if (!outgoingQueue.offer(message)) {
            // Queue is full - apply backpressure by dropping oldest message
            // and adding the new one (implements drop-oldest policy)
            int queueSize = outgoingQueue.size();
            outgoingQueue.poll();
            outgoingQueue.offer(message);
            System.out.println("[CONGESTION] Client-" + clientId + " outgoing queue FULL (" + queueSize + 
                             "/256). Dropping oldest message. Type: " + message.getType());
        }
    }
    
    /**
     * Check if this client has a significant message backlog.
     * Used for congestion detection by the server.
     */
    public boolean hasBacklog() {
        int queueSize = outgoingQueue.size();
        boolean hasBacklog = queueSize > 128;  // More than half of max queue (256)
        if (hasBacklog) {
            System.out.println("[BACKLOG DETECTED] Client-" + clientId + " has " + queueSize + 
                             "/256 messages queued. Latency increasing.");
        }
        return hasBacklog;
    }
    
    /**
     * Dedicated sender thread that processes outgoing messages one at a time.
     * This decouples message queueing from socket I/O.
     */
    private void senderLoop() {
        while (running.get() && !socket.isClosed()) {
            try {
                ProtocolMessage msg = outgoingQueue.poll(500, TimeUnit.MILLISECONDS);
                if (msg != null && running.get() && !socket.isClosed()) {
                    ProtocolIO.sendMessage(socket, msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (running.get()) {
                    server.removeClient(clientId);
                    close();
                }
            }
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
        // Interrupt sender thread so it stops waiting promptly
        if (senderThread != null) {
            senderThread.interrupt();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}


