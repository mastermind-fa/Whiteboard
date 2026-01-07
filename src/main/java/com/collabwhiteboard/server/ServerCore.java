package com.collabwhiteboard.server;

import com.collabwhiteboard.common.MessageTypes;
import com.collabwhiteboard.common.ProtocolMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerCore implements Runnable {

    private final int port;
    private final AtomicInteger clientIdSeq = new AtomicInteger(1);
    private final Map<Integer, ClientSession> clients = new ConcurrentHashMap<>();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    // History so that new clients can see existing chat and board state.
    private final List<JsonObject> chatHistory = new CopyOnWriteArrayList<>();
    private final List<JsonObject> boardEvents = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;

    public ServerCore(int port) {
        this.port = port;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            while (running) {
                Socket socket = serverSocket.accept();
                int id = clientIdSeq.getAndIncrement();
                ClientSession session = new ClientSession(id, socket, this);
                clients.put(id, session);
                clientPool.submit(session);
                broadcastClientList();
                sendServerInfo("Client " + id + " connected.");
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Server error: " + e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        running = false;
        for (ClientSession s : clients.values()) {
            s.close();
        }
        clientPool.shutdownNow();
    }

    public Map<Integer, ClientSession> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    public void removeClient(int clientId) {
        clients.remove(clientId);
        broadcastClientList();
        sendServerInfo("Client " + clientId + " disconnected.");
    }

    public void handleIncoming(int clientId, ProtocolMessage message) {
        switch (message.getType()) {
            case HELLO -> handleHello(clientId, message);
            case CHAT -> handleChatMessage(clientId, message);
            case DRAW_EVENT -> handleDrawEventMessage(clientId, message);
            case CLEAR_BOARD -> handleClearBoardMessage(clientId, message);
            case BOARD_SNAPSHOT -> broadcastFrom(clientId, message);
            case FILE_META, FILE_CHUNK, FILE_COMPLETE -> handleFileTransfer(clientId, message);
            default -> {
                // ignore or log unsupported types
            }
        }
    }

    private void handleHello(int clientId, ProtocolMessage message) {
        JsonObject payload = message.getPayload();
        String name = payload.has("name") ? payload.get("name").getAsString() : ("Client-" + clientId);
        ClientSession session = clients.get(clientId);
        if (session != null) {
            session.setDisplayName(name);
        }
        // Tell this client its assigned id
        JsonObject welcomePayload = new JsonObject();
        welcomePayload.addProperty("clientId", clientId);
        ProtocolMessage welcome = new ProtocolMessage(MessageTypes.WELCOME, welcomePayload);
        if (session != null) {
            session.send(welcome);
        }
        // Send existing chat and board history to bring the new client up to date.
        sendHistoryTo(clientId);
        broadcastClientList();
    }

    private void handleChatMessage(int clientId, ProtocolMessage message) {
        // Store a copy and broadcast to everyone.
        JsonObject copy = message.getPayload().deepCopy();
        chatHistory.add(copy);
        broadcastFrom(clientId, message);
    }

    private void handleDrawEventMessage(int clientId, ProtocolMessage message) {
        JsonObject copy = message.getPayload().deepCopy();
        boardEvents.add(copy);
        broadcastFrom(clientId, message);
    }

    private void handleClearBoardMessage(int clientId, ProtocolMessage message) {
        boardEvents.clear();
        broadcastFrom(clientId, message);
    }

    private void broadcastFrom(int clientId, ProtocolMessage message) {
        for (ClientSession s : clients.values()) {
            s.send(message);
        }
    }

    private void handleFileTransfer(int clientId, ProtocolMessage message) {
        // Route file messages based on optional targetIds field in the payload.
        JsonObject payload = message.getPayload();
        if (payload.has("targetIds")) {
            JsonArray targets = payload.getAsJsonArray("targetIds");
            for (int i = 0; i < targets.size(); i++) {
                int targetId = targets.get(i).getAsInt();
                ClientSession s = clients.get(targetId);
                if (s != null) {
                    s.send(message);
                }
            }
        } else {
            // Fallback: broadcast to everyone
            broadcastFrom(clientId, message);
        }
    }

    private void broadcastClientList() {
        JsonArray arr = new JsonArray();
        for (ClientSession s : clients.values()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", s.getClientId());
            c.addProperty("name", s.getDisplayName());
            arr.add(c);
        }
        JsonObject payload = new JsonObject();
        payload.add("clients", arr);
        ProtocolMessage msg = new ProtocolMessage(MessageTypes.CLIENT_LIST, payload);
        for (ClientSession s : clients.values()) {
            s.send(msg);
        }
    }

    private void sendServerInfo(String info) {
        JsonObject payload = new JsonObject();
        payload.addProperty("info", info);
        ProtocolMessage msg = new ProtocolMessage(MessageTypes.SERVER_INFO, payload);
        for (ClientSession s : clients.values()) {
            s.send(msg);
        }
        System.out.println(info);
    }

    private void sendHistoryTo(int clientId) {
        ClientSession session = clients.get(clientId);
        if (session == null) {
            return;
        }

        // Chat history
        JsonArray chatArr = new JsonArray();
        for (JsonObject obj : chatHistory) {
            chatArr.add(obj);
        }
        JsonObject chatPayload = new JsonObject();
        chatPayload.add("items", chatArr);
        ProtocolMessage chatMsg = new ProtocolMessage(MessageTypes.CHAT_HISTORY, chatPayload);
        session.send(chatMsg);

        // Board strokes history
        JsonArray boardArr = new JsonArray();
        for (JsonObject obj : boardEvents) {
            boardArr.add(obj);
        }
        JsonObject boardPayload = new JsonObject();
        boardPayload.add("items", boardArr);
        ProtocolMessage boardMsg = new ProtocolMessage(MessageTypes.BOARD_HISTORY, boardPayload);
        session.send(boardMsg);
    }
}


