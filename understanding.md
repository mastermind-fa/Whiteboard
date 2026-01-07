## Understanding the Collaborative Whiteboard TCP Project

This document explains **how the project is structured**, **which classes implement which features**, and **how the TCP protocol works**, so it's easy to present and defend to a teacher.

### High-Level Architecture

- **Server (`server` package)**  
  - `ServerMain` – Entry point for the TCP server.  
  - `ServerCore` – Listens for TCP connections, creates `ClientSession` objects, and routes all messages (chat, whiteboard, files).  
  - `ClientSession` – Represents one connected client (socket + thread) and forwards incoming messages to `ServerCore`.

- **Client (`client` package)**  
  - `ClientApp` – JavaFX `Application` class, loads the FXML UI.  
  - `MainController` – Main JavaFX controller handling UI logic (whiteboard, chat, file sharing) and all client-side networking.

- **Common (`common` package)**  
  - `MessageTypes` – Enum listing all message types used in the protocol.  
  - `ProtocolMessage` – Wrapper for messages (type + JSON payload).  
  - `ProtocolIO` – Utility for sending/receiving **length-prefixed JSON** over TCP sockets.
  - `CongestionController` – Implements TCP Tahoe and Reno congestion control algorithms.
  - `CongestionAwareProtocolIO` – Client-side simulation layer for congestion control visualization.
  - `CongestionMode` – Enum for algorithm selection (TAHOE, RENO).
  - `CongestionPhase` – Enum for phase tracking (SLOW_START, CONGESTION_AVOIDANCE, FAST_RECOVERY).
  - `Packet` – Represents data packets with sequence numbers for congestion control.

### TCP Protocol (No WebSockets)

- All communication is over **plain TCP sockets** (`java.net.Socket`, `java.net.ServerSocket`).
- Each message is encoded as:
  - **4-byte integer**: length of the JSON payload (big-endian).
  - **UTF-8 JSON string**: containing `"type"` and `"payload"`.

Example JSON payload structure:

```json
{
  "type": "CHAT",
  "payload": {
    "from": "Farhana",
    "text": "Hello everyone!"
  }
}
```

`ProtocolIO` is responsible for:

- `sendMessage(Socket, ProtocolMessage)` – Serializes the message to JSON, writes the length, then writes the bytes.
- `readMessage(Socket)` – Reads the length, then the bytes, parses back into `ProtocolMessage`.

This **avoids WebSockets completely** and keeps everything TCP-based.

### Message Types and Their Roles

Defined in `MessageTypes`:

- **HELLO** – Client → Server: initial handshake with the display name.
- **WELCOME** – Server → Clients: tells client its assigned ID.
- **CLIENT_LIST** – Server → Clients: tells every client who is connected (IDs + names).
- **CHAT** – Chat line from a client to everyone.
- **CHAT_HISTORY** – Server → Client: previous chat messages for new clients.
- **DRAW_EVENT** – One drawing stroke segment on the whiteboard (coordinates, color, stroke width).
- **BOARD_HISTORY** – Server → Client: previous drawing events for new clients.
- **CLEAR_BOARD** – Instruction to clear the whiteboard on all clients.
- **BOARD_SNAPSHOT** – Marker for a shared board snapshot (handled via file transfer metadata).
- **FILE_META** – Metadata for an incoming file (name, unique ID, size, board snapshot flag, target recipients).
- **FILE_CHUNK** – Base64-encoded file bytes (single chunk for simplicity).
- **FILE_COMPLETE** – Indicates that the file transfer is complete for the given unique ID.
- **SERVER_INFO / ERROR** – Server informational or error messages.
- **PACKET** – Data packet with sequence number (for congestion control simulation).
- **ACK** – Acknowledgment with sequence number (for congestion control simulation).
- **CONGESTION_STATS** – Congestion control statistics update.

### Server Logic (How It Works)

#### `ServerMain`

- Parses an optional port argument (default **5050**).
- Creates `ServerCore` and calls `run()` to start the blocking accept loop.

**Function Details:**
- `main(String[] args)`: Entry point, parses port argument, creates and runs ServerCore.

#### `ServerCore`

- Uses a `ServerSocket` to **accept clients**.
- For each new connection:
  - Assigns a unique **client ID** with an `AtomicInteger`.
  - Wraps the socket in a `ClientSession` and runs it in a thread pool.
  - Stores it in a `ConcurrentHashMap<Integer, ClientSession>`.
  - Calls `broadcastClientList()` to update all clients' lists.

- Handles all incoming messages from clients via `handleIncoming(clientId, ProtocolMessage)`:
  - `HELLO` → Updates the display name for that client, sends WELCOME with client ID, sends history (chat + board), and rebroadcasts the client list.
  - `CHAT` → Stores in chat history, then **broadcasts** to all clients.
  - `DRAW_EVENT` → Stores in board events history, then **broadcasts** to all clients.
  - `CLEAR_BOARD` → Clears board events history, then **broadcasts** to all clients.
  - `BOARD_SNAPSHOT` → Broadcasts to all clients.
  - `FILE_META`, `FILE_CHUNK`, `FILE_COMPLETE` → Routes to target clients if `targetIds` present, otherwise broadcasts to all.

- `broadcastClientList()` builds a JSON array with entries `{ id, name }` for each client, and sends it as a `CLIENT_LIST` message to everyone.

- `sendServerInfo(String)` sends `SERVER_INFO` messages so clients can display system notices in the chat area.

- `sendHistoryTo(int clientId)` sends `CHAT_HISTORY` and `BOARD_HISTORY` to newly connected clients.

**Function Details:**
- `ServerCore(int port)`: Constructor, initializes port.
- `run()`: Main server loop, accepts connections, creates ClientSession for each.
- `handleIncoming(int clientId, ProtocolMessage message)`: Routes messages based on type.
- `handleHello(int clientId, ProtocolMessage message)`: Processes HELLO, updates name, sends WELCOME and history.
- `handleChatMessage(int clientId, ProtocolMessage message)`: Stores chat in history, broadcasts.
- `handleDrawEventMessage(int clientId, ProtocolMessage message)`: Stores draw event in history, broadcasts.
- `handleClearBoardMessage(int clientId, ProtocolMessage message)`: Clears board history, broadcasts.
- `handleFileTransfer(int clientId, ProtocolMessage message)`: Routes file messages to target clients or broadcasts.
- `broadcastClientList()`: Sends updated client list to all clients.
- `sendServerInfo(String info)`: Broadcasts server informational messages.
- `sendHistoryTo(int clientId)`: Sends chat and board history to new client.
- `removeClient(int clientId)`: Removes client from map, broadcasts updated list.
- `shutdown()`: Stops server, closes all client connections.

#### `ClientSession`

- Each instance:
  - Holds a `Socket`, `clientId`, `displayName`, and reference to `ServerCore`.
  - Runs a loop calling `ProtocolIO.readMessage(...)` and passes each message to `ServerCore.handleIncoming(...)`.
  - On any I/O error, closes the socket and removes itself from the server's client map.

**Function Details:**
- `ClientSession(int clientId, Socket socket, ServerCore server)`: Constructor, initializes session.
- `getClientId()`: Returns client ID.
- `getDisplayName()`: Returns display name.
- `setDisplayName(String displayName)`: Updates display name.
- `send(ProtocolMessage message)`: Sends message to client using ProtocolIO.
- `run()`: Main loop, reads messages and forwards to ServerCore.
- `close()`: Closes socket and stops running.

### Client UI and Logic (JavaFX)

#### `ClientApp`

- JavaFX entry point:
  - Loads `MainView.fxml`.
  - Sets window title and size.
  - Applies custom CSS stylesheet.
  - Ensures the OS handles the window decorations (so the **top bar/title bar** is visible on Windows, macOS, and Linux).

**Function Details:**
- `start(Stage primaryStage)`: JavaFX application entry point, loads FXML, sets up window.

#### `MainView.fxml`

- Layout:
  - **Top**: `MenuBar` with:
    - `File` → *Save Whiteboard*, *Share Whiteboard*, *Send File*.
    - `Board` → *Clear Board*.
  - **Center**: `SplitPane` (vertical):
    - Upper: Whiteboard toolbar (color picker, stroke slider, clear button) + drawing canvas.
    - Lower: Chat area (non-editable `TextArea`) + input `TextField` + *Send* button.
  - **Right**: `TabPane` with two tabs:
    - **Clients Tab**: `ListView` showing `id: name` for each client, `Send File` button.
    - **Congestion Control Tab**: Toggle button, mode selection (Tahoe/Reno), metrics labels, cwnd graph, network sliders, activity log.
  - **Bottom**: `Label` for status messages.

This uses only **standard JavaFX controls** to ensure consistent behavior across OSes.

#### `MainController`

Responsible for:

- **Connecting to the server**
  - On `initialize()`:
    - Sets up canvas (white background, stroke settings).
    - Initializes congestion control UI (chart, sliders, toggle group).
    - Makes canvas responsive to window resizing.
    - Shows a dialog asking for `name@host:port` (host/port are optional).
    - Opens a `Socket` to the server.
    - Sends a `HELLO` message with the chosen display name.
    - Starts a background thread to read incoming messages.

- **Chat**
  - `onChatSend()` reads the text from the input field, creates a `CHAT` message with fields:
    - `"from"` – display name.
    - `"text"` – message text.
  - Sends via `sendAsync(...)`.
  - Incoming `CHAT` messages are handled with `handleChat(...)` and appended as `name: text` or `Me: text`.

- **Client list**
  - Incoming `CLIENT_LIST` messages are handled by `handleClientList(...)`.
  - Populates an in-memory `Map<Integer, String>` and updates the `ListView` with `id: name`.

- **Whiteboard drawing**
  - Uses a `Canvas` and `GraphicsContext`:
    - Mouse events: `onCanvasPressed` and `onCanvasDragged`.
    - While dragging:
      - Draws a line segment locally from `(lastX, lastY)` to `(x, y)`.
      - Sends a `DRAW_EVENT` with:
        - `x1`, `y1`, `x2`, `y2`
        - `color` – CSS hex string like `#FF0000`
        - `stroke` – stroke width.
      - Updates `lastX`, `lastY`.
  - Canvas is responsive: binds width/height to container, fills new areas with white on resize.
  - Incoming `DRAW_EVENT` messages call `handleDrawEvent(...)` to draw the same segment on the local canvas.

- **Clear board**
  - `onClearBoard()`:
    - Clears the local canvas to white.
    - Sends a `CLEAR_BOARD` message.
  - Incoming `CLEAR_BOARD` messages call `clearBoardLocal()` to clear the canvas, keeping everyone in sync.

- **Save whiteboard**
  - `onSaveBoard()`:
    - Opens a `FileChooser` filtered to `*.png`.
    - Uses `Canvas.snapshot(...)` and `ImageIO.write(...)` to save the current canvas as PNG.

- **Share whiteboard snapshot**
  - `onShareBoard()`:
    - Saves the current board to a temporary PNG file.
    - Calls `sendFileInternal(tempFile, true)`.
    - This sends the file to all clients just like any file (and logs in chat that the board was shared).

- **File sharing**
  - `onSendFile()` opens file chooser, calls `sendFileInternal(file, false)`.
  - `sendFileInternal(File file, boolean isBoardSnapshot)`:
    - Prompts for recipients using `promptForRecipients()`.
    - Reads file bytes, encodes as Base64.
    - Generates unique ID using `System.currentTimeMillis()`.
    - Sends `FILE_META`, `FILE_CHUNK`, `FILE_COMPLETE` messages with `targetIds` array.
  - `handleFileMeta`, `handleFileChunk`, `handleFileComplete` handle incoming files:
    - Check if file is intended for this client (via `targetIds`).
    - Buffer chunks, save with unique name on completion.

- **Congestion Control**
  - `onToggleCongestionControl()`: Enables/disables congestion control visualization.
  - `initializeCongestionControlUI()`: Sets up chart, sliders, toggle group.
  - `updateCongestionStats(CongestionStats stats)`: Updates UI with real-time metrics and graph.
  - `updateCongestionControlUIState(boolean enabled)`: Enables/disables UI controls.

**Function Details:**
- `initialize()`: Initializes UI, sets up canvas, congestion control UI, connects to server.
- `redrawCanvas()`: Fills canvas with white background.
- `initializeCongestionControlUI()`: Sets up congestion control UI components.
- `updateCongestionControlUIState(boolean enabled)`: Enables/disables congestion control UI.
- `connectToServer()`: Shows dialog, connects to server, sends HELLO, starts reader loop.
- `sendHello()`: Sends HELLO message with display name.
- `startReaderLoop()`: Background thread that reads messages from server.
- `handleIncoming(ProtocolMessage message)`: Routes incoming messages to appropriate handlers.
- `handleWelcome(JsonObject payload)`: Stores assigned client ID, updates status.
- `handleChat(JsonObject payload)`: Displays chat message (with "Me:" for own messages).
- `handleChatHistory(JsonObject payload)`: Processes and displays chat history.
- `handleClientList(JsonObject payload)`: Updates client list in UI.
- `handleDrawEvent(JsonObject payload)`: Draws stroke on canvas.
- `handleBoardHistory(JsonObject payload)`: Applies board history to canvas.
- `handleFileMeta(JsonObject payload)`: Initializes file reception.
- `handleFileChunk(JsonObject payload)`: Buffers file chunk data.
- `handleFileComplete(JsonObject payload)`: Saves received file with unique name.
- `onChatSend()`: Sends chat message.
- `appendChat(String line)`: Appends text to chat area.
- `onCanvasPressed(MouseEvent e)`: Records starting point for drawing.
- `onCanvasDragged(MouseEvent e)`: Draws line segment, sends DRAW_EVENT.
- `onClearBoard()`: Clears canvas, sends CLEAR_BOARD message.
- `clearBoardLocal()`: Clears canvas locally.
- `onSaveBoard()`: Saves canvas as PNG file.
- `onShareBoard()`: Shares board snapshot as file.
- `onSendFile()`: Opens file chooser, sends selected file.
- `sendFileInternal(File file, boolean isBoardSnapshot)`: Handles file sending logic.
- `promptForRecipients()`: Shows dialog to select file recipients.
- `renameWithId(String original, String id)`: Adds unique ID to filename.
- `sendAsync(ProtocolMessage msg)`: Sends message asynchronously.
- `toWebColor(Color c)`: Converts JavaFX Color to CSS hex string.
- `saveCanvasToFile(File file)`: Saves canvas snapshot to file.
- `showError(String msg)`: Shows error alert.
- `shutdown()`: Cleans up resources, closes socket, shuts down executors.
- `onToggleCongestionControl()`: Toggles congestion control on/off.
- `updateCongestionStats(CongestionStats stats)`: Updates congestion control UI.
- `appendCongestionLog(String message)`: Appends message to congestion log.

### File Sharing and Unique IDs

#### Sending a file (client-side)

`onSendFile()` and `sendFileInternal(File, boolean)` implement file sharing:

1. User chooses a file with `FileChooser`.
2. The client reads the file's bytes and encodes them as **Base64**.
3. Generates a **unique ID** using `System.currentTimeMillis()` (could be extended with client ID if needed).
4. Prompts user to select recipients (one or multiple clients).
5. Sends three messages:
   - `FILE_META`
     - `name` – original file name, e.g. `demo.txt`.
     - `uniqueId` – string, e.g. `"24343"` or timestamp.
     - `size` – byte length.
     - `isBoardSnapshot` – `true` if it was the whiteboard, otherwise `false`.
     - `targetIds` – JSON array of recipient client IDs.
   - `FILE_CHUNK`
     - `data` – Base64 string of the file bytes.
     - `targetIds` – JSON array of recipient client IDs.
   - `FILE_COMPLETE`
     - `uniqueId` – same unique ID as in `FILE_META`.
     - `targetIds` – JSON array of recipient client IDs.

The server routes these messages to target clients if `targetIds` is present, otherwise broadcasts to all.

#### Receiving a file (client-side)

`handleFileMeta`, `handleFileChunk`, `handleFileComplete`:

1. **`FILE_META`**
   - Checks if `targetIds` contains this client's ID (if present).
   - If not intended for this client, ignores the file.
   - If intended, stores:
     - `currentFileBaseName` – original file name (e.g. `demo.txt`).
     - `currentUniqueId` – ID generated by the sender.
   - Initializes a `ByteArrayOutputStream` buffer and logs a line in chat.

2. **`FILE_CHUNK`**
   - Checks if file is intended for this client.
   - Decodes the Base64 data and appends bytes to `currentFileBuffer`.

3. **`FILE_COMPLETE`**
   - Checks if file is intended for this client.
   - Builds the **renamed file name** using `renameWithId(originalName, uniqueId)`:
     - If original is `demo.txt` and ID is `24343`, it becomes `demo_24343.txt`.
   - Prompts the user with a `FileChooser` to pick where to save, pre-filled with this new name.
   - Writes the bytes to disk using `FileOutputStream`.
   - Logs the saved file name in the chat area.

This logic guarantees the behavior you requested:  
> when sending `demo.txt`, a client may receive and save it as `demo_24343.txt`.

### TCP Congestion Control Implementation

#### Overview

The congestion control feature is a **client-side simulation** that visualizes TCP Tahoe and Reno algorithms. It doesn't modify the actual protocol - messages are still sent using regular `ProtocolIO` for backward compatibility. The simulation tracks congestion control metrics and displays them in real-time.

#### Architecture

**Core Components:**

1. **`CongestionController`** - Implements the congestion control algorithms
2. **`CongestionAwareProtocolIO`** - Simulation layer that wraps message sending
3. **`CongestionMode`** - Enum: TAHOE, RENO
4. **`CongestionPhase`** - Enum: SLOW_START, CONGESTION_AVOIDANCE, FAST_RECOVERY
5. **`Packet`** - Represents packets with sequence numbers

#### How It Works

**1. Message Sending with Congestion Control Simulation**

When congestion control is enabled:
- `MainController.sendAsync()` calls `CongestionAwareProtocolIO.sendMessage()` instead of `ProtocolIO.sendMessage()`.
- `CongestionAwareProtocolIO.sendMessage()`:
  - Calculates how many packets the message would be split into (based on MSS = 1460 bytes).
  - Creates simulated packets with sequence numbers.
  - Calls `controller.onPacketSent()` for each packet.
  - Actually sends the message using regular `ProtocolIO.sendMessage()` (backward compatible).
  - Simulates packet loss based on configured loss rate.
  - Simulates network delay.

**2. ACK Simulation**

- `simulateAcks()` runs periodically (every 200ms):
  - Checks all unacknowledged simulated packets.
  - For packets older than network delay:
    - Applies packet loss simulation.
    - If not lost, generates ACK.
    - Detects duplicate ACKs (ACK number < expected next ACK).
    - Calls `controller.onAckReceived(ackNumber, isDuplicate)`.

**3. Timeout Detection**

- `checkTimeouts()` runs periodically (every 100ms):
  - Checks all unacknowledged packets.
  - If packet age > timeout threshold (2 * RTT), triggers timeout.
  - Calls `controller.onTimeout()`.
  - Simulates retransmission.

**4. Congestion Control Algorithm (`CongestionController`)**

**Slow Start Phase:**
- On new ACK: `cwnd += 1` (exponential growth per RTT).
- When `cwnd >= ssthresh`: Transition to Congestion Avoidance.

**Congestion Avoidance Phase:**
- On new ACK: `cwnd += 1/cwnd` (linear growth: 1 packet per RTT).
- Uses fractional counter: `congestionAvoidanceCounter += 1.0 / cwnd`.
- When counter >= 1.0: increment cwnd by 1.

**3 Duplicate ACKs (Fast Retransmit):**
- **TCP Tahoe**: 
  - `ssthresh = cwnd / 2`
  - `cwnd = 1`
  - Re-enter Slow Start.
- **TCP Reno**:
  - `ssthresh = cwnd / 2`
  - `cwnd = ssthresh` (cwnd/2)
  - Enter Fast Recovery.

**Fast Recovery (Reno only):**
- On duplicate ACK: `cwnd += 1` (inflate window).
- On new ACK: Exit fast recovery, `cwnd = ssthresh`, enter Congestion Avoidance.

**Timeout:**
- Both Tahoe and Reno:
  - `ssthresh = cwnd / 2`
  - `cwnd = 1`
  - Re-enter Slow Start.

**5. Transmission Round Tracking**

- Transmission round increments when `acksInCurrentRound >= cwnd`.
- This accurately represents RTT-based growth.
- Graph shows cwnd vs transmission rounds (not time).

**6. UI Updates**

- `updateCongestionStats()` is called whenever congestion state changes.
- Updates labels (cwnd, ssthresh, RTT, phase).
- Updates graph with new data point (round, cwnd).
- Appends events to activity log.

#### Detailed Function Descriptions

##### `CongestionController`

**Constructor:**
- `CongestionController(CongestionMode mode)`
  - **Parameters**: `mode` - TAHOE or RENO algorithm
  - **Purpose**: Initializes controller with initial values (cwnd=1, ssthresh=64, phase=SLOW_START)

**Core Methods:**
- `onPacketSent()`
  - **Parameters**: None
  - **Purpose**: Called when a packet is sent. Increments sequence number, updates statistics.
  - **Returns**: void

- `onAckReceived(int ackNumber, boolean isDuplicate)`
  - **Parameters**: 
    - `ackNumber` - ACK sequence number
    - `isDuplicate` - Whether this is a duplicate ACK
  - **Purpose**: Main congestion control logic. Updates cwnd based on phase and algorithm.
  - **Returns**: void
  - **Logic**:
    - Detects duplicate ACKs (ackNumber < expectedAckNumber)
    - On 3rd duplicate ACK: triggers fast retransmit (Reno) or timeout-like behavior (Tahoe)
    - On new ACK: updates cwnd based on phase (slow start or congestion avoidance)
    - Tracks transmission rounds

- `onTimeout()`
  - **Parameters**: None
  - **Purpose**: Handles timeout event. Both Tahoe and Reno handle timeout identically.
  - **Returns**: void
  - **Actions**: Sets ssthresh = cwnd/2, cwnd = 1, re-enters slow start

- `handleFastRetransmit()` (private)
  - **Parameters**: None
  - **Purpose**: Handles fast retransmit for Reno (3 duplicate ACKs).
  - **Returns**: void
  - **Actions**: Sets ssthresh = cwnd/2, cwnd = ssthresh, enters fast recovery

- `updateRTT(long sampleRTT)`
  - **Parameters**: `sampleRTT` - Measured round trip time in milliseconds
  - **Purpose**: Updates estimated RTT using exponential weighted moving average (EWMA).
  - **Returns**: void
  - **Formula**: `estimatedRTT = 0.875 * estimatedRTT + 0.125 * sampleRTT`

**Getter Methods:**
- `getCwnd()`: Returns current congestion window size
- `getSsthresh()`: Returns slow start threshold
- `getPhase()`: Returns current phase (SLOW_START, CONGESTION_AVOIDANCE, FAST_RECOVERY)
- `getEstimatedRTT()`: Returns estimated RTT in milliseconds
- `getTransmissionRound()`: Returns current transmission round number
- `getTimeoutThreshold()`: Returns timeout threshold (2 * RTT)
- `getStats()`: Returns CongestionStats object with all current metrics

**Configuration:**
- `setStatsCallback(Consumer<CongestionStats> callback)`
  - **Parameters**: `callback` - Function to call when stats update
  - **Purpose**: Sets callback for UI updates when congestion state changes

##### `CongestionAwareProtocolIO`

**Constructor:**
- `CongestionAwareProtocolIO(Socket socket, CongestionMode mode)`
  - **Parameters**: 
    - `socket` - TCP socket connection
    - `mode` - TAHOE or RENO algorithm
  - **Purpose**: Creates simulation layer, initializes CongestionController, starts background threads
  - **Throws**: IOException if socket operations fail

**Core Methods:**
- `sendMessage(ProtocolMessage message)`
  - **Parameters**: `message` - Protocol message to send
  - **Purpose**: Simulates packet-based transmission, actually sends via regular ProtocolIO
  - **Returns**: void
  - **Throws**: IOException if connection closed
  - **Process**:
    1. Calculates number of packets (message size / MSS)
    2. Creates simulated packets with sequence numbers
    3. Calls `controller.onPacketSent()` for each packet
    4. Queues message for actual sending
    5. Applies network delay simulation
    6. Sends via regular ProtocolIO
    7. Simulates packet loss

- `simulateAcks()` (private)
  - **Parameters**: None
  - **Purpose**: Periodically simulates ACK reception for unacknowledged packets
  - **Returns**: void
  - **Process**:
    1. Checks all unacknowledged packets
    2. For packets older than network delay:
       - Applies packet loss simulation
       - If not lost, generates ACK
       - Detects duplicate ACKs
       - Calls `controller.onAckReceived()`
       - Updates RTT estimate

- `checkTimeouts()` (private)
  - **Parameters**: None
  - **Purpose**: Periodically checks for timed-out packets
  - **Returns**: void
  - **Process**:
    1. Checks all unacknowledged packets
    2. If packet age > timeout threshold:
       - Calls `controller.onTimeout()`
       - Simulates retransmission
       - Resets packet send time

- `updateStats()` (private)
  - **Parameters**: None
  - **Purpose**: Periodically publishes congestion statistics to UI
  - **Returns**: void

**Configuration Methods:**
- `setPacketLossRate(double rate)`
  - **Parameters**: `rate` - Packet loss rate (0.0 to 1.0)
  - **Purpose**: Sets simulated packet loss rate

- `setNetworkDelay(long delayMs)`
  - **Parameters**: `delayMs` - Network delay in milliseconds
  - **Purpose**: Sets simulated network delay

- `setStatsCallback(Consumer<CongestionStats> callback)`
  - **Parameters**: `callback` - Function to call with stats updates
  - **Purpose**: Sets callback for UI updates

- `getController()`
  - **Returns**: CongestionController instance
  - **Purpose**: Provides access to controller for direct queries

- `shutdown()`
  - **Parameters**: None
  - **Purpose**: Shuts down all background threads, cleans up resources
  - **Returns**: void

##### `MainController` (Congestion Control Methods)

- `initializeCongestionControlUI()`
  - **Parameters**: None
  - **Purpose**: Initializes congestion control UI components (chart, sliders, toggle group)
  - **Returns**: void

- `onToggleCongestionControl()`
  - **Parameters**: None
  - **Purpose**: Toggles congestion control on/off
  - **Returns**: void
  - **Process**:
    - If enabling: Creates CongestionAwareProtocolIO, sets network parameters, enables UI
    - If disabling: Shuts down CongestionAwareProtocolIO, disables UI

- `updateCongestionStats(CongestionStats stats)`
  - **Parameters**: `stats` - Current congestion control statistics
  - **Purpose**: Updates UI with real-time metrics and graph
  - **Returns**: void
  - **Updates**:
    - Labels: cwnd, ssthresh, RTT, phase
    - Graph: Adds data point (transmission round, cwnd)
    - Auto-scales axes

- `appendCongestionLog(String message)`
  - **Parameters**: `message` - Log message to append
  - **Purpose**: Appends message to congestion control activity log
  - **Returns**: void

#### Theory Verification

**TCP Tahoe:**
- ✅ Slow Start: cwnd += 1 per ACK (exponential growth)
- ✅ Congestion Avoidance: cwnd += 1/cwnd per ACK (linear growth)
- ✅ 3 Duplicate ACKs: cwnd = 1, re-enter slow start
- ✅ Timeout: cwnd = 1, ssthresh = cwnd/2, re-enter slow start
- ✅ No Fast Recovery phase

**TCP Reno:**
- ✅ Slow Start: cwnd += 1 per ACK (exponential growth)
- ✅ Congestion Avoidance: cwnd += 1/cwnd per ACK (linear growth)
- ✅ 3 Duplicate ACKs: Fast retransmit, cwnd = ssthresh, enter fast recovery
- ✅ Fast Recovery: cwnd += 1 per duplicate ACK, exit on new ACK
- ✅ Timeout: cwnd = 1, ssthresh = cwnd/2, re-enter slow start

**ssthresh Behavior:**
- ✅ Only decreases (never increases)
- ✅ Set to cwnd/2 on timeout
- ✅ Set to cwnd/2 on fast retransmit (3 duplicate ACKs)
- ✅ Initial value: 64
- ✅ Minimum value: 2

**RTT Estimation:**
- ✅ Uses exponential weighted moving average (EWMA)
- ✅ Formula: `estimatedRTT = 0.875 * oldRTT + 0.125 * sampleRTT`
- ✅ Timeout threshold = 2 * estimatedRTT

**Transmission Rounds:**
- ✅ Round increments when ACKs for full window received
- ✅ Graph shows cwnd vs transmission rounds (not time)
- ✅ Accurately represents RTT-based growth

### Cross-Platform and Professional UI Choices

- The application uses **JavaFX** with:
  - Standard controls (`MenuBar`, `BorderPane`, `SplitPane`, `ListView`, `TextArea`, `Canvas`, `TabPane`, `LineChart`).
  - No custom window decorations or OS-specific APIs.
- This ensures:
  - The **title bar/top bar shows correctly** on Windows, macOS, and Linux.
  - Layout resizes nicely due to `BorderPane` + `SplitPane` + `VBox/HBox` usage.
  - Canvas is responsive and fills available space.
- Colors, paddings, and fonts are kept simple and neutral for a professional, clean look.

### How to Explain the Project to Your Teacher

- **Concept**: "It's a collaborative whiteboard and chat app with file sharing, built on plain TCP sockets. All clients connect to a central server. It includes TCP congestion control visualization showing Tahoe and Reno algorithms."
- **Technologies**: Java 25, JavaFX for UI, Gson for JSON, plain `java.net.Socket`/`ServerSocket` for networking.
- **Key Points to Highlight**:
  - No WebSockets – every message is a custom length-prefixed JSON frame over TCP.
  - Real-time drawing is done by sending small `DRAW_EVENT` messages for every mouse drag segment.
  - File sharing uses `FILE_META`, `FILE_CHUNK`, and `FILE_COMPLETE` messages with a unique ID that is appended to the filename when saving.
  - The UI is the same on Windows/Mac/Linux because it uses only standard JavaFX components with OS-managed window decorations.
  - TCP congestion control is implemented with accurate Tahoe and Reno algorithms, visualized in real-time with graphs showing cwnd evolution over transmission rounds.

---

## Q&A Section - Common Teacher Questions

### General Architecture Questions

**Q: What is the overall architecture of this project?**
A: Centralized client-server model. One TCP server manages multiple client connections. All communication uses custom length-prefixed JSON messages over TCP sockets. No WebSockets are used.

**Q: Why did you choose TCP instead of WebSockets?**
A: To demonstrate fundamental networking concepts - TCP socket programming, message framing, reliable data transfer. WebSockets abstract away these details, but we wanted to implement them directly.

**Q: How does the server handle multiple clients simultaneously?**
A: Server uses `ExecutorService` thread pool. Each client connection gets its own `ClientSession` running in a separate thread. Server maintains a `ConcurrentHashMap` to track all active clients.

**Q: How do you ensure messages are received completely?**
A: We use length-prefixed messages. Each message starts with a 4-byte integer indicating the JSON payload length. The receiver reads this length first, then reads exactly that many bytes.

### Protocol Questions

**Q: Explain your message protocol.**
A: Each message has:
1. 4-byte integer (big-endian): JSON payload length
2. UTF-8 JSON string with `type` and `payload` fields
This handles TCP's stream nature by providing message boundaries.

**Q: What message types do you support?**
A: HELLO, WELCOME, CLIENT_LIST, CHAT, CHAT_HISTORY, DRAW_EVENT, BOARD_HISTORY, CLEAR_BOARD, BOARD_SNAPSHOT, FILE_META, FILE_CHUNK, FILE_COMPLETE, SERVER_INFO, ERROR, PACKET, ACK, CONGESTION_STATS.

**Q: How do you handle file transfers?**
A: Three-message sequence:
1. FILE_META: Metadata (name, size, unique ID, recipients)
2. FILE_CHUNK: Base64-encoded file data
3. FILE_COMPLETE: Completion signal
Files are renamed with unique IDs on reception (e.g., `demo.txt` → `demo_24343.txt`).

**Q: How do you ensure new clients see existing chat and board state?**
A: Server maintains `chatHistory` and `boardEvents` lists. When a client connects and sends HELLO, server sends CHAT_HISTORY and BOARD_HISTORY messages containing all previous data.

### Server Implementation Questions

**Q: What does `ServerCore.handleIncoming()` do?**
A: Routes incoming messages based on type:
- HELLO: Updates client name, sends WELCOME and history
- CHAT: Stores in history, broadcasts to all
- DRAW_EVENT: Stores in history, broadcasts to all
- FILE_*: Routes to target clients or broadcasts
- CLEAR_BOARD: Clears board history, broadcasts

**Q: How does `ClientSession` work?**
A: Each ClientSession:
- Wraps a Socket connection
- Runs in its own thread
- Continuously reads messages using `ProtocolIO.readMessage()`
- Forwards messages to `ServerCore.handleIncoming()`
- On error, closes socket and removes itself from server's client map

**Q: What is the purpose of `broadcastClientList()`?**
A: Builds a JSON array of all connected clients (id + name) and sends it as CLIENT_LIST message to all clients. Called whenever a client connects or disconnects.

### Client Implementation Questions

**Q: How does the whiteboard drawing work?**
A: On mouse drag:
1. Draws line segment locally on Canvas
2. Sends DRAW_EVENT message with coordinates (x1, y1, x2, y2), color, stroke width
3. Server broadcasts to all clients
4. Other clients receive and draw the same segment

**Q: How is the canvas made responsive?**
A: Canvas width/height are bound to container (StackPane) size. On resize, new areas are filled with white. Canvas always fills available space.

**Q: How does `sendAsync()` work?**
A: Submits message sending to a separate executor thread. This prevents blocking the UI thread. If congestion control is enabled, uses `CongestionAwareProtocolIO`, otherwise uses regular `ProtocolIO`.

**Q: Why do you have separate executors for reading and sending?**
A: Reading is blocking (waits for incoming messages). Sending should not be blocked by reading. Separate executors ensure outgoing messages (chat, drawing) are sent immediately even if read loop is waiting.

### Congestion Control Questions

**Q: Explain how TCP congestion control is implemented.**
A: Client-side simulation layer:
1. `CongestionAwareProtocolIO` wraps message sending
2. Simulates packet-based transmission (splits messages into packets)
3. Tracks sequence numbers, ACKs, timeouts
4. `CongestionController` implements Tahoe/Reno algorithms
5. Updates cwnd, ssthresh, phase based on events
6. UI displays metrics and graph in real-time

**Q: What is the difference between TCP Tahoe and TCP Reno?**
A: 
- **Tahoe**: On 3 duplicate ACKs, drops cwnd to 1 and re-enters slow start. No fast recovery.
- **Reno**: On 3 duplicate ACKs, halves cwnd (sets to ssthresh) and enters fast recovery. Fast recovery inflates cwnd on duplicate ACKs, exits on new ACK.
- Both handle timeout identically: cwnd = 1, ssthresh = cwnd/2, re-enter slow start.

**Q: How does slow start work?**
A: In slow start phase:
- cwnd increases by 1 for each ACK received
- This results in exponential growth (doubling per RTT)
- Continues until cwnd >= ssthresh
- Then transitions to congestion avoidance

**Q: How does congestion avoidance work?**
A: In congestion avoidance phase:
- cwnd increases by 1/cwnd for each ACK received
- This results in linear growth (1 packet per RTT)
- Uses fractional counter: `congestionAvoidanceCounter += 1.0 / cwnd`
- When counter >= 1.0, increment cwnd by 1

**Q: What is fast retransmit?**
A: When 3 duplicate ACKs are received:
- Indicates a packet was lost
- Reno: Immediately retransmits lost packet, halves cwnd, enters fast recovery
- Tahoe: Treats like timeout, drops cwnd to 1, re-enters slow start

**Q: What is fast recovery (Reno only)?**
A: Phase entered after fast retransmit:
- cwnd is set to ssthresh (half of previous cwnd)
- For each duplicate ACK, cwnd increases by 1 (inflates window)
- When new ACK arrives, exits fast recovery, sets cwnd = ssthresh, enters congestion avoidance

**Q: How do you detect duplicate ACKs?**
A: Track `expectedAckNumber`. If received ACK number < expectedAckNumber, it's a duplicate (ACK for already acknowledged data).

**Q: How is ssthresh managed?**
A: ssthresh (slow start threshold):
- Initial value: 64
- Updated only on congestion events:
  - Timeout: ssthresh = cwnd / 2
  - Fast retransmit: ssthresh = cwnd / 2
- Never increases, only decreases (or stays same)
- Minimum value: 2

**Q: How do you calculate RTT?**
A: Uses exponential weighted moving average (EWMA):
- Formula: `estimatedRTT = 0.875 * oldRTT + 0.125 * sampleRTT`
- Timeout threshold = 2 * estimatedRTT
- Updated whenever a packet is acknowledged

**Q: What is a transmission round?**
A: A transmission round represents one RTT. It increments when we've received ACKs for a full window (cwnd packets). The graph shows cwnd vs transmission rounds, which accurately represents RTT-based growth.

**Q: How does the graph visualization work?**
A: 
- X-axis: Transmission rounds (not time)
- Y-axis: Congestion window (cwnd) in packets
- Adds data point whenever:
  - Transmission round changes
  - cwnd changes significantly (fast retransmit, timeout)
- Shows exponential growth (slow start), linear growth (congestion avoidance), vertical drops (events)

**Q: Does congestion control affect actual message transmission?**
A: No. It's a client-side simulation. Messages are still sent using regular `ProtocolIO` for backward compatibility. The simulation only tracks metrics for visualization.

**Q: What network conditions can you simulate?**
A: 
- Packet loss rate: 0-10% (configurable via slider)
- Network delay: 0-200ms (configurable via slider)
- These affect when ACKs are generated and when timeouts occur

### Function-Specific Questions

**Q: What does `CongestionController.onAckReceived()` do?**
A: Main congestion control logic:
- Parameters: `ackNumber` (ACK sequence number), `isDuplicate` (whether duplicate)
- Detects duplicate ACKs
- On 3rd duplicate: triggers fast retransmit (Reno) or timeout behavior (Tahoe)
- On new ACK: updates cwnd based on phase (slow start: +1, congestion avoidance: +1/cwnd)
- Tracks transmission rounds
- Updates statistics

**Q: What does `CongestionController.onTimeout()` do?**
A: Handles timeout event:
- Sets ssthresh = cwnd / 2 (minimum 2)
- Sets cwnd = 1
- Re-enters slow start phase
- Resets duplicate ACK counter
- Same for both Tahoe and Reno

**Q: What does `CongestionAwareProtocolIO.sendMessage()` do?**
A: Simulates packet-based transmission:
- Calculates number of packets (message size / MSS)
- Creates simulated packets with sequence numbers
- Calls `controller.onPacketSent()` for each
- Actually sends message via regular ProtocolIO (backward compatible)
- Applies network delay simulation
- Simulates packet loss

**Q: What does `CongestionAwareProtocolIO.simulateAcks()` do?**
A: Periodically simulates ACK reception:
- Checks all unacknowledged packets
- For packets older than network delay:
  - Applies packet loss simulation
  - If not lost, generates ACK
  - Detects duplicate ACKs (ACK number < expected)
  - Calls `controller.onAckReceived()`
  - Updates RTT estimate

**Q: What does `MainController.updateCongestionStats()` do?**
A: Updates UI with congestion control metrics:
- Parameters: `stats` (CongestionStats object)
- Updates labels: cwnd, ssthresh, RTT, phase
- Adds data point to graph (transmission round, cwnd)
- Auto-scales graph axes
- Runs on JavaFX application thread

### Technical Implementation Questions

**Q: How do you handle thread safety?**
A: 
- Server: Uses `ConcurrentHashMap` for client storage
- Client: Uses separate executors for reading and sending
- Congestion control: Uses `ConcurrentHashMap` for packet tracking, `ConcurrentLinkedQueue` for message queue
- UI updates: All done on JavaFX application thread using `Platform.runLater()`

**Q: How do you prevent blocking?**
A: 
- Separate threads for reading (blocking) and sending (non-blocking)
- UI operations on JavaFX thread
- Background threads for congestion control simulation
- Asynchronous message sending via executor

**Q: What happens when a client disconnects?**
A: 
- `ClientSession.run()` loop exits on IOException
- `ServerCore.removeClient()` is called
- Client removed from ConcurrentHashMap
- `broadcastClientList()` updates all remaining clients
- Socket is closed

**Q: How do you ensure cross-platform compatibility?**
A: 
- Uses only standard JavaFX controls
- No OS-specific APIs
- Platform-agnostic file operations
- Standard Java networking (works on all platforms)
- OS-native window decorations

**Q: How is the canvas made responsive to window resizing?**
A: 
- Canvas width/height bound to StackPane container
- On resize, new areas filled with white
- Existing drawings preserved
- Canvas always fills available space

### Design Decision Questions

**Q: Why did you use a centralized server instead of peer-to-peer?**
A: 
- Simpler to implement and debug
- Easier state synchronization (server maintains history)
- Better for demonstrating client-server TCP concepts
- Centralized control for file routing

**Q: Why did you choose JavaFX over Swing?**
A: 
- Modern UI framework
- Better cross-platform consistency
- Built-in chart support (for congestion control graph)
- Better styling with CSS
- Active development and support

**Q: Why simulate congestion control instead of implementing it in the actual protocol?**
A: 
- Maintains backward compatibility
- Doesn't require server changes
- Allows visualization without affecting functionality
- Demonstrates concepts clearly
- Can be toggled on/off

**Q: Why use transmission rounds instead of time for the graph?**
A: 
- More accurate representation of TCP behavior
- TCP congestion control is RTT-based, not time-based
- Shows exponential/linear growth more clearly
- Matches academic literature and textbooks

Use this document together with the code to quickly point to each class when asked "where is this implemented?".
