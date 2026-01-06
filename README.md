## Collaborative Whiteboard & File Sharing (TCP, JavaFX)

This project is a **cross-platform collaborative whiteboard, chat, and file sharing application** built with **Java 17**, **JavaFX**, and a **custom TCP protocol** (no websockets at any point).

It is designed to run on **Windows, macOS, and Linux** with a consistent, professional UI.

### Features

- **Real-time collaborative whiteboard**
  - Freehand drawing with **color picker** and **stroke width** control.
  - **Clear board** for everyone.
  - **Save** the whiteboard locally as a PNG image.
  - **Share** the current board snapshot with all connected clients over TCP.

- **Integrated chat**
  - Chat with all connected clients in a single room.
  - Server broadcasts messages so everyone stays in sync.

- **File sharing**
  - Send any file to all connected clients.
  - Each received file is **renamed with a unique ID**, e.g. sending `demo.txt` might arrive as `demo_24343.txt`.

- **Multi-client server**
  - Central TCP server manages all connections, broadcasts whiteboard events, chat messages, and file transfers.
  - Simple JSON-over-TCP protocol with length-prefixed frames.

### Requirements

- Java 17 or later (JDK).
- Gradle (you already have it installed).
- JavaFX runtime available on your system.

### Running the Server

From the project root:

```bash
cd /Users/farhanaalam/Code/NetworkingProject/Whiteboard
./gradlew build
java -cp build/libs/CollaborativeWhiteboardTCP-1.0.0.jar com.collabwhiteboard.server.ServerMain 5050
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

The server listens on port **5050** by default (or another port you provide as an argument).

### Running the Client

You can run the JavaFX client using Gradle:

```bash
cd /Users/farhanaalam/Code/NetworkingProject/Whiteboard
./gradlew run
```

Make sure your JavaFX modules are on the module path. One common way is to set the JVM argument:

```bash
./gradlew run -Djavafx.module.path=/path/to/javafx-sdk/lib
```

On startup, the client asks for:

- A **display name**, and
- Optionally `@host:port` (default is `localhost:5050`).

Example:

- `Farhana` → connects as `Farhana` to `localhost:5050`.
- `Alice@192.168.0.10:5050` → connects as `Alice` to that host and port.

### Basic Usage

- **Whiteboard**
  - Draw by clicking and dragging on the canvas.
  - Change **color** and **stroke** from the toolbar.
  - Use **File → Save Whiteboard** to save as PNG.
  - Use **File → Share Whiteboard** (or the toolbar button) to send a snapshot to all clients.

- **Chat**
  - Type in the text field under the chat area and press **Enter** or click **Send**.

- **File Sharing**
  - Click **Send File** (menu or right-side button), choose a file.
  - All connected clients will be prompted to save it.
  - The file name will be automatically **renamed with a unique suffix** on receive (e.g. `demo_24343.txt`).

### Cross-Platform Notes

- The UI uses only **standard JavaFX controls** (`BorderPane`, `SplitPane`, `MenuBar`, etc.) so the **top bar and layout are consistent** across macOS, Windows, and Linux.
- No platform-specific APIs or styling are used; window decorations are handled by the OS to avoid missing title bars on any platform.

### More Details

See `understanding.md` for:

- Full protocol description,
- How each feature is implemented,
- Which classes are responsible for which parts of the system.


