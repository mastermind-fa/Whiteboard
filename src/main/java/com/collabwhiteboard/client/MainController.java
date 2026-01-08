package com.collabwhiteboard.client;

import com.collabwhiteboard.common.CongestionAwareProtocolIO;
import com.collabwhiteboard.common.CongestionController;
import com.collabwhiteboard.common.CongestionMode;
import com.collabwhiteboard.common.MessageTypes;
import com.collabwhiteboard.common.ProtocolIO;
import com.collabwhiteboard.common.ProtocolMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {

    @FXML
    private BorderPane rootPane;

    @FXML
    private TextArea chatArea;

    @FXML
    private TextField chatInput;

    @FXML
    private ListView<String> clientListView;

    @FXML
    private Canvas whiteboardCanvas;
    
    @FXML
    private StackPane canvasContainer;

    @FXML
    private ColorPicker colorPicker;

    @FXML
    private Slider strokeSlider;

    @FXML
    private Button saveBoardButton;

    @FXML
    private Button shareBoardButton;

    @FXML
    private Button sendFileButton;

    @FXML
    private Label statusLabel;

    // Congestion control UI components
    @FXML
    private ToggleButton congestionControlToggle;
    @FXML
    private RadioButton tahoeRadio;
    @FXML
    private RadioButton renoRadio;
    @FXML
    private Label cwndLabel;
    @FXML
    private Label ssthreshLabel;
    @FXML
    private Label rttLabel;
    @FXML
    private Label phaseLabel;
    @FXML
    private LineChart<Number, Number> cwndChart;
    @FXML
    private Slider lossRateSlider;
    @FXML
    private Slider delaySlider;
    @FXML
    private TextArea congestionLog;

    private GraphicsContext gc;

    private Socket socket;
    private CongestionAwareProtocolIO congestionAwareIO;
    private boolean congestionControlEnabled = false;
    // Separate executors for reading and sending so outgoing messages are not blocked by the read loop.
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    private String displayName = "User";
    private final Map<Integer, String> clients = new HashMap<>();

    private int myClientId = -1;

    private double lastX, lastY;

    @FXML
    public void initialize() {
        gc = whiteboardCanvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(2.0);

        colorPicker.setValue(Color.BLACK);
        strokeSlider.setValue(2.0);

        // Make canvas responsive to container size - unlimited size
        if (canvasContainer != null) {
            // Bind canvas size to container size - no max constraints
            whiteboardCanvas.widthProperty().bind(canvasContainer.widthProperty());
            whiteboardCanvas.heightProperty().bind(canvasContainer.heightProperty());
            
            // Fill new areas with white when canvas grows (but preserve existing drawings)
            whiteboardCanvas.widthProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal.doubleValue() > 0) {
                    Platform.runLater(() -> {
                        if (newVal.doubleValue() > oldVal.doubleValue()) {
                            // Fill new area with white
                            gc.setFill(Color.WHITE);
                            gc.fillRect(oldVal.doubleValue(), 0, newVal.doubleValue() - oldVal.doubleValue(), whiteboardCanvas.getHeight());
                        } else if (oldVal.doubleValue() == 0 && newVal.doubleValue() > 0) {
                            // Initial resize - fill entire canvas
                            gc.setFill(Color.WHITE);
                            gc.fillRect(0, 0, newVal.doubleValue(), whiteboardCanvas.getHeight());
                        }
                    });
                }
            });
            whiteboardCanvas.heightProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal.doubleValue() > 0) {
                    Platform.runLater(() -> {
                        if (newVal.doubleValue() > oldVal.doubleValue()) {
                            // Fill new area with white
                            gc.setFill(Color.WHITE);
                            gc.fillRect(0, oldVal.doubleValue(), whiteboardCanvas.getWidth(), newVal.doubleValue() - oldVal.doubleValue());
                        } else if (oldVal.doubleValue() == 0 && newVal.doubleValue() > 0) {
                            // Initial resize - fill entire canvas
                            gc.setFill(Color.WHITE);
                            gc.fillRect(0, 0, whiteboardCanvas.getWidth(), newVal.doubleValue());
                        }
                    });
                }
            });
        }
        
        // Initial draw - fill entire canvas with white (will be called after layout)
        Platform.runLater(() -> {
            // Delay to ensure canvas has been laid out
            Platform.runLater(() -> redrawCanvas());
        });

        // Initialize congestion control UI
        initializeCongestionControlUI();

        connectToServer();
    }
    
    private void redrawCanvas() {
        Platform.runLater(() -> {
            double width = whiteboardCanvas.getWidth();
            double height = whiteboardCanvas.getHeight();
            if (width > 0 && height > 0) {
                gc.setFill(Color.WHITE);
                gc.fillRect(0, 0, width, height);
            }
        });
    }

    private void initializeCongestionControlUI() {
        // Set up ToggleGroup for radio buttons
        if (tahoeRadio != null && renoRadio != null) {
            ToggleGroup modeGroup = new ToggleGroup();
            tahoeRadio.setToggleGroup(modeGroup);
            renoRadio.setToggleGroup(modeGroup);
        }

        // Initialize chart
        if (cwndChart != null) {
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName("cwnd");
            cwndChart.getData().add(series);
            cwndChart.setAnimated(false);
            cwndChart.setCreateSymbols(false);
            // Set initial point at (0, 1) for round 0
            series.getData().add(new XYChart.Data<>(0, 1));
        }

        // Set up sliders
        if (lossRateSlider != null) {
            lossRateSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (congestionAwareIO != null) {
                    congestionAwareIO.setPacketLossRate(newVal.doubleValue() / 100.0);
                }
            });
        }

        if (delaySlider != null) {
            delaySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (congestionAwareIO != null) {
                    congestionAwareIO.setNetworkDelay(newVal.longValue());
                }
            });
        }

        // Disable congestion control UI initially
        updateCongestionControlUIState(false);
    }

    private void updateCongestionControlUIState(boolean enabled) {
        if (tahoeRadio != null) tahoeRadio.setDisable(!enabled);
        if (renoRadio != null) renoRadio.setDisable(!enabled);
        if (lossRateSlider != null) lossRateSlider.setDisable(!enabled);
        if (delaySlider != null) delaySlider.setDisable(!enabled);
    }

    private void connectToServer() {
        TextInputDialog dialog = new TextInputDialog("User");
        dialog.setTitle("Connect to Server");
        dialog.setHeaderText("Enter display name (and optional host:port)");
        dialog.setContentText("Format: name@host:port (default host=localhost, port=5050)");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            Platform.exit();
            return;
        }

        String input = result.get().trim();
        String namePart = input;
        String host = "localhost";
        int port = 5050;

        if (input.contains("@")) {
            String[] split = input.split("@", 2);
            namePart = split[0].trim();
            String hostPort = split[1].trim();
            if (hostPort.contains(":")) {
                String[] hp = hostPort.split(":", 2);
                host = hp[0].trim();
                try {
                    port = Integer.parseInt(hp[1].trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (!hostPort.isEmpty()) {
                host = hostPort;
            }
        }

        displayName = namePart.isEmpty() ? "User" : namePart;

        try {
            socket = new Socket(host, port);
            statusLabel.setText("Connected to " + host + ":" + port);
            
            sendHello();
            startReaderLoop();
        } catch (IOException e) {
            showError("Unable to connect to server: " + e.getMessage());
            Platform.exit();
        }
    }

    @FXML
    private void onToggleCongestionControl() {
        if (congestionControlToggle == null || socket == null || socket.isClosed()) {
            return;
        }

        boolean shouldEnable = congestionControlToggle.isSelected();
        
        if (shouldEnable && !congestionControlEnabled) {
            // Enable congestion control
            try {
                CongestionMode mode = tahoeRadio != null && tahoeRadio.isSelected() 
                    ? CongestionMode.TAHOE 
                    : CongestionMode.RENO;
                
                congestionAwareIO = new CongestionAwareProtocolIO(socket, mode);
                
                // Set network parameters
                if (lossRateSlider != null) {
                    congestionAwareIO.setPacketLossRate(lossRateSlider.getValue() / 100.0);
                }
                if (delaySlider != null) {
                    congestionAwareIO.setNetworkDelay((long) delaySlider.getValue());
                }
                
                // Set up stats callback
                congestionAwareIO.setStatsCallback(this::updateCongestionStats);
                
                congestionControlEnabled = true;
                updateCongestionControlUIState(true);
                
                // Clear and reset chart
                if (cwndChart != null && !cwndChart.getData().isEmpty()) {
                    cwndChart.getData().get(0).getData().clear();
                    cwndChart.getData().get(0).getData().add(new XYChart.Data<>(0, 1));
                }
                
                appendCongestionLog("Congestion control enabled: " + mode);
                
            } catch (IOException e) {
                showError("Failed to enable congestion control: " + e.getMessage());
                congestionControlToggle.setSelected(false);
            }
        } else if (!shouldEnable && congestionControlEnabled) {
            // Disable congestion control
            if (congestionAwareIO != null) {
                congestionAwareIO.shutdown();
                congestionAwareIO = null;
            }
            congestionControlEnabled = false;
            updateCongestionControlUIState(false);
            appendCongestionLog("Congestion control disabled");
        }
    }

    private void updateCongestionStats(CongestionController.CongestionStats stats) {
        Platform.runLater(() -> {
            if (cwndLabel != null) {
                cwndLabel.setText(String.format("cwnd: %d", stats.cwnd));
            }
            if (ssthreshLabel != null) {
                ssthreshLabel.setText(String.format("ssthresh: %d", stats.ssthresh));
            }
            if (rttLabel != null) {
                rttLabel.setText(String.format("RTT: %dms", stats.rtt));
            }
            if (phaseLabel != null) {
                phaseLabel.setText("Phase: " + stats.phase);
            }

            // Update chart with transmission rounds
            // Always add a point to capture all phase transitions and events
            if (cwndChart != null && !cwndChart.getData().isEmpty()) {
                XYChart.Series<Number, Number> series = cwndChart.getData().get(0);
                ObservableList<XYChart.Data<Number, Number>> data = series.getData();
                
                int currentRound = stats.transmissionRound;
                int currentCwnd = stats.cwnd;
                
                // Always add a new point to capture cwnd changes
                // This ensures we see fast retransmit drops, timeout drops, and all transitions
                if (data.isEmpty()) {
                    // First point
                    data.add(new XYChart.Data<>(currentRound, currentCwnd));
                } else {
                    XYChart.Data<Number, Number> lastPoint = data.get(data.size() - 1);
                    int lastRound = lastPoint.getXValue().intValue();
                    int lastCwnd = lastPoint.getYValue().intValue();
                    
                    // Add new point if:
                    // 1. Round changed, OR
                    // 2. cwnd changed significantly (for fast retransmit/timeout events)
                    if (currentRound > lastRound || Math.abs(currentCwnd - lastCwnd) > 0) {
                        // If same round but cwnd changed (fast retransmit/timeout), add point at same round
                        // This creates a vertical drop in the graph
                        if (currentRound == lastRound && currentCwnd != lastCwnd) {
                            data.add(new XYChart.Data<>(currentRound, currentCwnd));
                        } else if (currentRound > lastRound) {
                            // New round - add point
                            data.add(new XYChart.Data<>(currentRound, currentCwnd));
                        }
                    }
                }
                
                // Keep only last 200 points to show more history
                if (data.size() > 200) {
                    data.remove(0);
                }
                
                // Auto-scale X axis
                if (data.size() > 1) {
                    NumberAxis xAxis = (NumberAxis) cwndChart.getXAxis();
                    int maxRound = data.get(data.size() - 1).getXValue().intValue();
                    xAxis.setLowerBound(Math.max(0, maxRound - 50));
                    xAxis.setUpperBound(maxRound + 5);
                    
                    // Auto-scale Y axis to show full range
                    NumberAxis yAxis = (NumberAxis) cwndChart.getYAxis();
                    int maxCwnd = data.stream()
                        .mapToInt(d -> d.getYValue().intValue())
                        .max()
                        .orElse(100);
                    yAxis.setUpperBound(Math.max(maxCwnd + 10, 20));
                }
            }
        });
    }

    private void appendCongestionLog(String message) {
        if (congestionLog != null) {
            Platform.runLater(() -> {
                String timestamp = String.format("[%tT]", System.currentTimeMillis());
                congestionLog.appendText(timestamp + " " + message + "\n");
            });
        }
    }

    private void sendHello() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", displayName);
        ProtocolMessage msg = new ProtocolMessage(MessageTypes.HELLO, payload);
        if (congestionControlEnabled && congestionAwareIO != null) {
            congestionAwareIO.sendMessage(msg);
        } else {
            ProtocolIO.sendMessage(socket, msg);
        }
    }

    private void startReaderLoop() {
        readerExecutor.submit(() -> {
            while (running && !socket.isClosed()) {
                try {
                    ProtocolMessage message = ProtocolIO.readMessage(socket);
                    handleIncoming(message);
                } catch (IOException e) {
                    if (running) {
                        Platform.runLater(() -> showError("Disconnected from server."));
                    }
                    break;
                }
            }
        });
    }

    private void handleIncoming(ProtocolMessage message) {
        switch (message.getType()) {
            case CHAT -> handleChat(message.getPayload());
            case CHAT_HISTORY -> handleChatHistory(message.getPayload());
            case CLIENT_LIST -> handleClientList(message.getPayload());
            case DRAW_EVENT -> handleDrawEvent(message.getPayload());
            case BOARD_HISTORY -> handleBoardHistory(message.getPayload());
            case CLEAR_BOARD -> clearBoardLocal();
            case BOARD_SNAPSHOT -> handleBoardSnapshot(message.getPayload());
            case FILE_META -> handleFileMeta(message.getPayload());
            case FILE_CHUNK -> handleFileChunk(message.getPayload());
            case FILE_COMPLETE -> handleFileComplete(message.getPayload());
            case WELCOME -> handleWelcome(message.getPayload());
            case SERVER_INFO -> {
                String info = message.getPayload().get("info").getAsString();
                appendChat("[SERVER] " + info);
            }
            default -> {
            }
        }
    }

    private void handleWelcome(JsonObject payload) {
        myClientId = payload.get("clientId").getAsInt();
        Platform.runLater(() -> statusLabel.setText(
                "Connected as " + displayName + " (ID " + myClientId + ")"));
    }

    private void handleChat(JsonObject payload) {
        String from = payload.get("from").getAsString();
        String text = payload.get("text").getAsString();
        // Show "Me" for this client, name for others, so all windows display consistently.
        if (from.equals(displayName)) {
            appendChat("Me: " + text);
        } else {
            appendChat(from + ": " + text);
        }
    }

    private void handleChatHistory(JsonObject payload) {
        if (!payload.has("items")) {
            return;
        }
        JsonArray items = payload.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject msg = items.get(i).getAsJsonObject();
            handleChat(msg);
        }
    }

    private void handleClientList(JsonObject payload) {
        JsonArray arr = payload.getAsJsonArray("clients");
        clients.clear();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject c = arr.get(i).getAsJsonObject();
            int id = c.get("id").getAsInt();
            String name = c.get("name").getAsString();
            clients.put(id, name);
        }
        Platform.runLater(() -> {
            clientListView.getItems().clear();
            for (Map.Entry<Integer, String> e : clients.entrySet()) {
                clientListView.getItems().add(e.getKey() + ": " + e.getValue());
            }
        });
    }

    private void handleDrawEvent(JsonObject payload) {
        double x1 = payload.get("x1").getAsDouble();
        double y1 = payload.get("y1").getAsDouble();
        double x2 = payload.get("x2").getAsDouble();
        double y2 = payload.get("y2").getAsDouble();
        String color = payload.get("color").getAsString();
        double stroke = payload.get("stroke").getAsDouble();
        Platform.runLater(() -> {
            gc.setStroke(Color.web(color));
            gc.setLineWidth(stroke);
            gc.strokeLine(x1, y1, x2, y2);
        });
    }

    private void handleBoardHistory(JsonObject payload) {
        if (!payload.has("items")) {
            return;
        }
        JsonArray items = payload.getAsJsonArray("items");
        // Clear local board then apply all stored strokes.
        clearBoardLocal();
        for (int i = 0; i < items.size(); i++) {
            JsonObject stroke = items.get(i).getAsJsonObject();
            handleDrawEvent(stroke);
        }
    }

    private void handleBoardSnapshot(JsonObject payload) {
        appendChat("[INFO] Received shared whiteboard snapshot.");
        // Snapshot shared as a file transfer; here we just log info.
    }

    // Placeholder simple in-memory tracking for a single incoming file at a time.
    private ByteArrayOutputStream currentFileBuffer;
    private String currentFileBaseName;
    private String currentUniqueId;
    private boolean currentIsForMe;

    private void handleFileMeta(JsonObject payload) {
        // Check if this file is intended for this client (if targetIds present)
        if (payload.has("targetIds") && myClientId != -1) {
            JsonArray targets = payload.getAsJsonArray("targetIds");
            boolean found = false;
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i).getAsInt() == myClientId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                currentIsForMe = false;
                currentFileBuffer = null;
                return;
            }
        }
        currentIsForMe = true;
        currentFileBaseName = payload.get("name").getAsString();
        currentUniqueId = payload.get("uniqueId").getAsString();
        currentFileBuffer = new ByteArrayOutputStream();
        appendChat("[FILE] Incoming file: " + currentFileBaseName + " (id " + currentUniqueId + ")");
    }

    private void handleFileChunk(JsonObject payload) {
        if (!currentIsForMe || currentFileBuffer == null) {
            return;
        }
        String base64 = payload.get("data").getAsString();
        byte[] bytes = java.util.Base64.getDecoder().decode(base64);
        try {
            currentFileBuffer.write(bytes);
        } catch (IOException ignored) {
        }
    }

    private void handleFileComplete(JsonObject payload) {
        if (!currentIsForMe || currentFileBuffer == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        String renamed = renameWithId(currentFileBaseName, currentUniqueId);
        chooser.setInitialFileName(renamed);
        chooser.setTitle("Save received file");
        Platform.runLater(() -> {
            File file = chooser.showSaveDialog(rootPane.getScene().getWindow());
            if (file != null) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    currentFileBuffer.writeTo(fos);
                    appendChat("[FILE] You received a file. Saved as " + file.getName()
                            + " at " + file.getAbsolutePath());
                } catch (IOException e) {
                    showError("Failed to save file: " + e.getMessage());
                }
            } else {
                appendChat("[FILE] You received a file but chose not to save it.");
            }
            currentFileBuffer = null;
            currentFileBaseName = null;
            currentUniqueId = null;
            currentIsForMe = false;
        });
    }

    private String renameWithId(String original, String id) {
        int dot = original.lastIndexOf('.');
        if (dot > 0) {
            String base = original.substring(0, dot);
            String ext = original.substring(dot);
            return base + "_" + id + ext;
        } else {
            return original + "_" + id;
        }
    }

    @FXML
    private void onChatSend() {
        String text = chatInput.getText().trim();
        if (text.isEmpty()) return;
        chatInput.clear();
        JsonObject payload = new JsonObject();
        payload.addProperty("from", displayName);
        payload.addProperty("text", text);
        sendAsync(new ProtocolMessage(MessageTypes.CHAT, payload));
    }

    private void appendChat(String line) {
        Platform.runLater(() -> {
            chatArea.appendText(line + "\n");
        });
    }

    @FXML
    private void onCanvasPressed(MouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
    }

    @FXML
    private void onCanvasDragged(MouseEvent e) {
        double x = e.getX();
        double y = e.getY();
        Color c = colorPicker.getValue();
        double stroke = strokeSlider.getValue();

        gc.setStroke(c);
        gc.setLineWidth(stroke);
        gc.strokeLine(lastX, lastY, x, y);

        JsonObject payload = new JsonObject();
        payload.addProperty("x1", lastX);
        payload.addProperty("y1", lastY);
        payload.addProperty("x2", x);
        payload.addProperty("y2", y);
        payload.addProperty("color", toWebColor(c));
        payload.addProperty("stroke", stroke);
        sendAsync(new ProtocolMessage(MessageTypes.DRAW_EVENT, payload));

        lastX = x;
        lastY = y;
    }

    @FXML
    private void onClearBoard() {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, whiteboardCanvas.getWidth(), whiteboardCanvas.getHeight());
        JsonObject payload = new JsonObject();
        sendAsync(new ProtocolMessage(MessageTypes.CLEAR_BOARD, payload));
    }

    @FXML
    private void onSaveBoard() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Whiteboard Snapshot");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        File file = chooser.showSaveDialog(rootPane.getScene().getWindow());
        if (file != null) {
            saveCanvasToFile(file);
        }
    }

    @FXML
    private void onShareBoard() {
        try {
            File temp = File.createTempFile("board_", ".png");
            saveCanvasToFile(temp);
            sendFileInternal(temp, true);
            appendChat("[BOARD] Shared current whiteboard snapshot.");
        } catch (IOException e) {
            showError("Failed to share board: " + e.getMessage());
        }
    }

    @FXML
    private void onSendFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Send");
        File file = chooser.showOpenDialog(rootPane.getScene().getWindow());
        if (file != null) {
            sendFileInternal(file, false);
        }
    }

    private void sendFileInternal(File file, boolean isBoardSnapshot) {
        try {
            java.util.List<Integer> targetIds = promptForRecipients();
            if (targetIds.isEmpty()) {
                appendChat("[FILE] File send cancelled (no recipients selected).");
                return;
            }

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
            String uniqueId = String.valueOf(System.currentTimeMillis());

            JsonObject meta = new JsonObject();
            meta.addProperty("name", file.getName());
            meta.addProperty("uniqueId", uniqueId);
            meta.addProperty("size", bytes.length);
            meta.addProperty("isBoardSnapshot", isBoardSnapshot);
            JsonArray targetsArray = new JsonArray();
            for (Integer id : targetIds) {
                targetsArray.add(id);
            }
            meta.add("targetIds", targetsArray);
            sendAsync(new ProtocolMessage(MessageTypes.FILE_META, meta));

            JsonObject chunk = new JsonObject();
            chunk.addProperty("data", base64);
            chunk.add("targetIds", targetsArray);
            sendAsync(new ProtocolMessage(MessageTypes.FILE_CHUNK, chunk));

            JsonObject complete = new JsonObject();
            complete.addProperty("uniqueId", uniqueId);
            complete.add("targetIds", targetsArray);
            sendAsync(new ProtocolMessage(MessageTypes.FILE_COMPLETE, complete));

            appendChat("[FILE] Sent file " + file.getName() + " (" + bytes.length + " bytes) to "
                    + describeTargets(targetIds));
        } catch (IOException e) {
            showError("Failed to send file: " + e.getMessage());
        }
    }

    private java.util.List<Integer> promptForRecipients() {
        Platform.runLater(() -> statusLabel.setText("Choosing recipients..."));

        Dialog<java.util.List<Integer>> dialog = new Dialog<>();
        dialog.setTitle("Select Recipients");
        dialog.setHeaderText("Choose one or more clients to send the file to.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        VBox box = new VBox(6);
        box.setAlignment(Pos.TOP_LEFT);

        if (clients.isEmpty()) {
            Label none = new Label("No other clients connected.");
            box.getChildren().add(none);
        } else {
            for (Map.Entry<Integer, String> entry : clients.entrySet()) {
                int id = entry.getKey();
                String name = entry.getValue();
                CheckBox cb = new CheckBox(id + ": " + name);
                cb.setUserData(id);
                cb.setSelected(true); // default: send to everyone
                box.getChildren().add(cb);
            }
        }

        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                java.util.List<Integer> result = new java.util.ArrayList<>();
                for (javafx.scene.Node node : box.getChildren()) {
                    if (node instanceof CheckBox cb && cb.isSelected() && cb.getUserData() instanceof Integer id) {
                        result.add(id);
                    }
                }
                return result;
            }
            return java.util.Collections.emptyList();
        });

        Optional<java.util.List<Integer>> res = dialog.showAndWait();
        return res.orElse(java.util.Collections.emptyList());
    }

    private String describeTargets(java.util.List<Integer> ids) {
        if (ids.isEmpty()) return "no one";
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Integer id : ids) {
            String name = clients.getOrDefault(id, "Client-" + id);
            names.add(name + " (ID " + id + ")");
        }
        return String.join(", ", names);
    }

    private void saveCanvasToFile(File file) {
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.WHITE);
        javafx.scene.image.WritableImage image = whiteboardCanvas.snapshot(params, null);
        try {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", file);
        } catch (IOException e) {
            showError("Failed to save board: " + e.getMessage());
        }
    }

    private void sendAsync(ProtocolMessage msg) {
        if (socket == null || socket.isClosed()) return;
        sendExecutor.submit(() -> {
            try {
                if (congestionControlEnabled && congestionAwareIO != null) {
                    congestionAwareIO.sendMessage(msg);
                } else {
                    ProtocolIO.sendMessage(socket, msg);
                }
            } catch (IOException e) {
                showError("Failed to send message: " + e.getMessage());
            }
        });
    }

    private String toWebColor(Color c) {
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    private void clearBoardLocal() {
        redrawCanvas();
    }

    private void showError(String msg) {
        Platform.runLater(() -> {
            statusLabel.setText(msg);
            Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            alert.setHeaderText("Error");
            alert.showAndWait();
        });
    }

    public void shutdown() {
        running = false;
        if (congestionAwareIO != null) {
            congestionAwareIO.shutdown();
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        readerExecutor.shutdownNow();
        sendExecutor.shutdownNow();
    }
}


