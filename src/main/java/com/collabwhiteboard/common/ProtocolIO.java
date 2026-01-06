package com.collabwhiteboard.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Utility class for sending/receiving length-prefixed JSON messages over a TCP socket.
 */
public class ProtocolIO {

    private static final Gson GSON = new Gson();

    public static void sendMessage(Socket socket, ProtocolMessage message) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        JsonObject root = new JsonObject();
        root.addProperty("type", message.getType().name());
        root.add("payload", message.getPayload());
        byte[] data = GSON.toJson(root).getBytes("UTF-8");
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    public static ProtocolMessage readMessage(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int length;
        try {
            length = in.readInt();
        } catch (IOException ex) {
            throw ex;
        }
        if (length <= 0 || length > 10_000_000) {
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


