package com.collabwhiteboard.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

/**
 * Utility class for sending/receiving length-prefixed JSON messages over a TCP socket.
 * Includes congestion control features such as buffer management and timeout handling.
 */
public class ProtocolIO {

    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGE_SIZE = 10_000_000;  // 10MB max message
    private static final int SOCKET_TIMEOUT_MS = 30000;  // 30 second timeout

    public static void sendMessage(Socket socket, ProtocolMessage message) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("type", message.getType().name());
        root.add("payload", message.getPayload());
        byte[] data = GSON.toJson(root).getBytes("UTF-8");

        // Congestion control: check message size and apply limits
        if (data.length > MAX_MESSAGE_SIZE) {
            throw new IOException("Message too large: " + data.length + " bytes exceeds limit of " + MAX_MESSAGE_SIZE);
        }

        // Synchronize on the socket to prevent multiple writers from corrupting the stream
        synchronized (socket) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            try {
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            } catch (IOException e) {
                // First attempt failed - try a single short retry to tolerate transient congestion
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                try {
                    out.writeInt(data.length);
                    out.write(data);
                    out.flush();
                } catch (IOException e2) {
                    // Give up after one retry
                    System.err.println("[ProtocolIO] Send failed after retry: " + e2.getMessage());
                    throw new IOException("Socket error during send (possibly due to congestion): " + e2.getMessage(), e2);
                }
            }
        }
    }

    public static ProtocolMessage readMessage(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int length;
        try {
            // Set read timeout for congestion detection
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            length = in.readInt();
        } catch (SocketException e) {
            throw new IOException("Socket timeout or error during read: " + e.getMessage(), e);
        } catch (IOException ex) {
            throw ex;
        }
        
        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
            throw new IOException("Invalid message length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        String json = new String(data, "UTF-8");
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        MessageTypes type = MessageTypes.valueOf(root.get("type").getAsString());
        JsonObject payload = root.get("payload").getAsJsonObject();
        return new ProtocolMessage(type, payload);
    }
}


