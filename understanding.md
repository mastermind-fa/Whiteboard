## Understanding the Collaborative Whiteboard TCP Project

This document explains **how the project is structured**, **which classes implement which features**, and **how the TCP protocol works**, so it’s easy to present and defend to a teacher.

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
- **CLIENT_LIST** – Server → Clients: tells every client who is connected (IDs + names).
- **CHAT** – Chat line from a client to everyone.
- **DRAW_EVENT** – One drawing stroke segment on the whiteboard (coordinates, color, stroke width).
- **CLEAR_BOARD** – Instruction to clear the whiteboard on all clients.
- **BOARD_SNAPSHOT** – Marker for a shared board snapshot (handled via file transfer metadata).
- **FILE_META** – Metadata for an incoming file (name, unique ID, size, board snapshot flag).
- **FILE_CHUNK** – Base64-encoded file bytes (single chunk for simplicity).
- **FILE_COMPLETE** – Indicates that the file transfer is complete for the given unique ID.
- **SERVER_INFO / ERROR** – Server informational or error messages.

### Server Logic (How It Works)

#### `ServerMain`

- Parses an optional port argument (default **5050**).
- Creates `ServerCore` and calls `run()` to start the blocking accept loop.

#### `ServerCore`

- Uses a `ServerSocket` to **accept clients**.
- For each new connection:
  - Assigns a unique **client ID** with an `AtomicInteger`.
  - Wraps the socket in a `ClientSession` and runs it in a thread pool.
  - Stores it in a `ConcurrentHashMap<Integer, ClientSession>`.
  - Calls `broadcastClientList()` to update all clients’ lists.

- Handles all incoming messages from clients via `handleIncoming(clientId, ProtocolMessage)`:
  - `HELLO` → Updates the display name for that client and rebroadcasts the client list.
  - `CHAT`, `DRAW_EVENT`, `CLEAR_BOARD`, `BOARD_SNAPSHOT` → Simply **broadcasts** to all clients.
  - `FILE_META`, `FILE_CHUNK`, `FILE_COMPLETE` → Relayed to all clients as a file transfer.

- `broadcastClientList()` builds a JSON array with entries `{ id, name }` for each client, and sends it as a `CLIENT_LIST` message to everyone.

- `sendServerInfo(String)` sends `SERVER_INFO` messages so clients can display system notices in the chat area.

#### `ClientSession`

- Each instance:
  - Holds a `Socket`, `clientId`, `displayName`, and reference to `ServerCore`.
  - Runs a loop calling `ProtocolIO.readMessage(...)` and passes each message to `ServerCore.handleIncoming(...)`.
  - On any I/O error, closes the socket and removes itself from the server’s client map.

### Client UI and Logic (JavaFX)

#### `ClientApp`

- JavaFX entry point:
  - Loads `MainView.fxml`.
  - Sets window title and size.
  - Ensures the OS handles the window decorations (so the **top bar/title bar** is visible on Windows, macOS, and Linux).

#### `MainView.fxml`

- Layout:
  - **Top**: `MenuBar` with:
    - `File` → *Save Whiteboard*, *Share Whiteboard*, *Send File*.
    - `Board` → *Clear Board*.
  - **Center**: `SplitPane` (vertical):
    - Upper: Whiteboard toolbar + drawing canvas.
    - Lower: Chat area (non-editable `TextArea`) + input `TextField` + *Send* button.
  - **Right**: `VBox` with:
    - `Label` (“Connected Clients”)
    - `ListView` showing `id: name` for each client.
    - `Send File` button.
  - **Bottom**: `Label` for status messages.

This uses only **standard JavaFX controls** to ensure consistent behavior across OSes.

#### `MainController`

Responsible for:

- **Connecting to the server**
  - On `initialize()`:
    - Shows a dialog asking for `name@host:port` (host/port are optional).
    - Opens a `Socket` to the server.
    - Sends a `HELLO` message with the chosen display name.
    - Starts a background thread to read incoming messages.

- **Chat**
  - `onChatSend()` reads the text from the input field, creates a `CHAT` message with fields:
    - `"from"` – display name.
    - `"text"` – message text.
  - Sends via `sendAsync(...)` and locally appends “Me: ...” to the chat area.
  - Incoming `CHAT` messages are handled with `handleChat(...)` and appended as `name: text`.

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

### File Sharing and Unique IDs

#### Sending a file (client-side)

`onSendFile()` and `sendFileInternal(File, boolean)` implement file sharing:

1. User chooses a file with `FileChooser`.
2. The client reads the file’s bytes and encodes them as **Base64**.
3. Generates a **unique ID** using `System.currentTimeMillis()` (could be extended with client ID if needed).
4. Sends three messages:
   - `FILE_META`
     - `name` – original file name, e.g. `demo.txt`.
     - `uniqueId` – string, e.g. `"24343"` or timestamp.
     - `size` – byte length.
     - `isBoardSnapshot` – `true` if it was the whiteboard, otherwise `false`.
   - `FILE_CHUNK`
     - `data` – Base64 string of the file bytes.
   - `FILE_COMPLETE`
     - `uniqueId` – same unique ID as in `FILE_META`.

The server does not modify the data; it just **relays** these messages to all clients.

#### Receiving a file (client-side)

`handleFileMeta`, `handleFileChunk`, `handleFileComplete`:

1. **`FILE_META`**
   - Stores:
     - `currentFileBaseName` – original file name (e.g. `demo.txt`).
     - `currentUniqueId` – ID generated by the sender.
   - Initializes a `ByteArrayOutputStream` buffer and logs a line in chat.

2. **`FILE_CHUNK`**
   - Decodes the Base64 data and appends bytes to `currentFileBuffer`.

3. **`FILE_COMPLETE`**
   - Builds the **renamed file name** using `renameWithId(originalName, uniqueId)`:
     - If original is `demo.txt` and ID is `24343`, it becomes `demo_24343.txt`.
   - Prompts the user with a `FileChooser` to pick where to save, pre-filled with this new name.
   - Writes the bytes to disk using `FileOutputStream`.
   - Logs the saved file name in the chat area.

This logic guarantees the behavior you requested:  
> when sending `demo.txt`, a client may receive and save it as `demo_24343.txt`.

### Cross-Platform and Professional UI Choices

- The application uses **JavaFX** with:
  - Standard controls (`MenuBar`, `BorderPane`, `SplitPane`, `ListView`, `TextArea`, `Canvas`).
  - No custom window decorations or OS-specific APIs.
- This ensures:
  - The **title bar/top bar shows correctly** on Windows, macOS, and Linux.
  - Layout resizes nicely due to `BorderPane` + `SplitPane` + `VBox/HBox` usage.
- Colors, paddings, and fonts are kept simple and neutral for a professional, clean look.

### How to Explain the Project to Your Teacher

- **Concept**: “It’s a collaborative whiteboard and chat app with file sharing, built on plain TCP sockets. All clients connect to a central server.”
- **Technologies**: Java 17, JavaFX for UI, Gson for JSON, plain `java.net.Socket`/`ServerSocket` for networking.
- **Key Points to Highlight**:
  - No WebSockets – every message is a custom length-prefixed JSON frame over TCP.
  - Real-time drawing is done by sending small `DRAW_EVENT` messages for every mouse drag segment.
  - File sharing uses `FILE_META`, `FILE_CHUNK`, and `FILE_COMPLETE` messages with a unique ID that is appended to the filename when saving.
  - The UI is the same on Windows/Mac/Linux because it uses only standard JavaFX components with OS-managed window decorations.

Use this document together with the code to quickly point to each class when asked “where is this implemented?”.


