# Collaborative Whiteboard and File Sharing Application

A real-time collaborative whiteboard application with integrated chat and file sharing capabilities, built using pure TCP protocol (no WebSockets). This cross-platform application enables multiple users to collaborate simultaneously on a shared whiteboard, communicate through an integrated chat system, and share files securely.

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
- [Project Structure](#project-structure)
- [Protocol Specification](#protocol-specification)
- [Cross-Platform Support](#cross-platform-support)
- [Contributing](#contributing)
- [License](#license)

## Overview

This project implements a client-server architecture where a central TCP server manages multiple client connections. All communication occurs over standard TCP sockets using a custom protocol with JSON-based message encoding. The application provides:

- **Real-time collaborative whiteboard** with synchronized drawing across all clients
- **Integrated chat system** for team communication
- **File sharing** with automatic unique file naming
- **Cross-platform compatibility** (Windows, macOS, Linux)

## Features

### Collaborative Whiteboard

- **Freehand Drawing**: Draw with customizable colors and stroke widths (1-15 pixels)
- **Real-time Synchronization**: All drawing strokes appear instantly on all connected clients
- **Board Management**: 
  - Clear board functionality (affects all clients)
  - Save whiteboard locally as PNG image
  - Share whiteboard snapshot to all connected clients
- **History Preservation**: New clients automatically receive existing board state upon connection

### Integrated Chat System

- **Real-time Messaging**: Broadcast messages to all connected clients
- **Message History**: New clients receive complete chat history upon connection
- **User Identification**: Clear sender identification with "Me" label for own messages
- **Server Notifications**: Connection status updates and system messages

### File Sharing

- **Selective Recipients**: Send files to one or multiple specific clients
- **Automatic File Naming**: Received files are automatically renamed with unique identifiers (e.g., `demo.txt` → `demo_24343.txt`)
- **Any File Type**: Support for all file types and sizes
- **User Notifications**: Clear notifications when files are received and saved

### Client Management

- **Dynamic Client List**: Real-time list of all connected clients
- **Unique Client IDs**: Automatic assignment of unique identifiers
- **Connection Status**: Visual indication of connection state
- **Graceful Disconnection**: Proper handling of client disconnections

## Architecture

### System Architecture

The application follows a **centralized client-server model**:

- **Server Side**: 
  - `ServerMain`: Entry point for the TCP server
  - `ServerCore`: Manages client connections, message routing, and state synchronization
  - `ClientSession`: Represents individual client connections with dedicated threads

- **Protocol Layer**:
  - `ProtocolIO`: Handles low-level TCP message transmission (length-prefixed JSON)
  - `ProtocolMessage`: Message wrapper with type and payload
  - `MessageTypes`: Enumeration of all message types

- **Client Side**:
  - `ClientApp`: JavaFX application entry point
  - `MainController`: Manages UI interactions and network communication

### Communication Flow

1. **Client Connection**: Client establishes TCP connection to server
2. **Handshake**: Client sends HELLO message with display name
3. **State Synchronization**: Server sends welcome message, client list, and history (chat + board)
4. **Real-time Collaboration**: All actions (drawing, chat, files) are broadcast to relevant clients
5. **State Preservation**: Server maintains history for new client connections

## Requirements

### Software Requirements

- **Java Development Kit (JDK)**: Version 25 or later
- **Gradle**: Build tool (version 9.2.0 or compatible)
- **JavaFX SDK**: Version 21.0.9 or compatible (for client application)

### Platform Support

- **Windows**: 10/11 (64-bit)
- **macOS**: 10.15 or later (Intel and Apple Silicon)
- **Linux**: Most modern distributions (64-bit)

## Installation

### 1. Clone the Repository

```bash
git clone <repository-url>
cd Whiteboard
```

### 2. Set Up JavaFX

Download JavaFX SDK 21.0.9 for your platform from [Gluon](https://openjfx.io/) and extract it.

**macOS/Linux:**
```bash
export PATH_TO_FX=/path/to/javafx-sdk-21.0.9/lib
```

**Windows (PowerShell):**
```powershell
$env:PATH_TO_FX="C:\path\to\javafx-sdk-21.0.9\lib"
```

To make this permanent, add the export command to your shell profile (`~/.zshrc`, `~/.bashrc`, or Windows Environment Variables).

### 3. Build the Project

```bash
gradle build
```

On Windows:
```powershell
gradle.bat build
```

## Usage

### Starting the Server

The server must be running before clients can connect.

```bash
gradle runServer --args="5050"
```

On Windows:
```powershell
gradle.bat runServer --args="5050"
```

The server will start listening on port 5050 (or the port you specify). You should see:
```
Server listening on port 5050
```

### Starting Clients

Open a new terminal for each client instance:

```bash
gradle runClient
```

On Windows:
```powershell
gradle.bat runClient
```

When prompted, enter:
- **Display name only** (e.g., `Alice`): Connects to `localhost:5050`
- **Display name with host:port** (e.g., `Bob@192.168.1.100:5050`): Connects to specified server

### Using the Application

#### Whiteboard

1. **Drawing**: Click and drag on the canvas to draw
2. **Color Selection**: Use the color picker in the toolbar
3. **Stroke Width**: Adjust using the slider (1-15 pixels)
4. **Clear Board**: Click "Clear" button or use **Board → Clear Board** (affects all clients)
5. **Save Board**: Use **File → Save Whiteboard** to save locally as PNG
6. **Share Board**: Use **File → Share Whiteboard** to send snapshot to all clients

#### Chat

1. Type your message in the text field at the bottom of the chat area
2. Press **Enter** or click **Send** button
3. Messages appear in all connected clients' chat areas
4. Your own messages are labeled as "Me: [message]"

#### File Sharing

1. Click **Send File** button (right panel) or use **File → Send File**
2. Select the file you want to send
3. In the recipient selection dialog:
   - Choose one or multiple clients from the list
   - Click **OK** to send
4. Recipients will:
   - See a notification in chat: `[FILE] Incoming file: filename.ext (id xxxxx)`
   - Be prompted to save the file with a unique name (e.g., `filename_24343.ext`)
   - Receive confirmation: `[FILE] You received a file. Saved as filename_24343.ext at /path/to/file`

#### Client List

The right panel shows all currently connected clients with their IDs and display names. The list updates automatically as clients connect or disconnect.

### Connecting from Different Devices

To connect clients from different devices on the same network:

1. **Find Server IP**: On the server machine, find its local IP address:
   - **macOS/Linux**: `ipconfig getifaddr en0` or `ifconfig`
   - **Windows**: `ipconfig` (look for IPv4 Address)

2. **Start Server**: Ensure server is running and firewall allows port 5050

3. **Connect Clients**: When starting clients on other devices, use:
   ```
   YourName@SERVER_IP:5050
   ```
   Example: `Alice@192.168.1.100:5050`

## Project Structure

```
Whiteboard/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── collabwhiteboard/
│   │   │           ├── client/
│   │   │           │   ├── ClientApp.java          # JavaFX application entry
│   │   │           │   └── MainController.java      # UI and network controller
│   │   │           ├── server/
│   │   │           │   ├── ServerMain.java         # Server entry point
│   │   │           │   ├── ServerCore.java         # Server logic and routing
│   │   │           │   └── ClientSession.java      # Individual client session
│   │   │           └── common/
│   │   │               ├── MessageTypes.java       # Message type enumeration
│   │   │               ├── ProtocolMessage.java    # Message wrapper class
│   │   │               └── ProtocolIO.java         # TCP I/O utilities
│   │   └── resources/
│   │       ├── fxml/
│   │       │   └── MainView.fxml                   # UI layout definition
│   │       └── css/
│   │           └── app.css                         # Application stylesheet
│   └── test/
│       └── java/                                    # Test files
├── build.gradle                                     # Build configuration
├── settings.gradle                                  # Gradle settings
└── README.md                                        # This file
```

## Protocol Specification

### Message Format

All messages are transmitted as:
1. **4-byte integer** (big-endian): Length of JSON payload
2. **UTF-8 JSON string**: Message content

### JSON Structure

```json
{
  "type": "MESSAGE_TYPE",
  "payload": {
    // Type-specific payload data
  }
}
```

### Message Types

- `HELLO`: Client handshake with display name
- `WELCOME`: Server assigns client ID
- `CLIENT_LIST`: List of all connected clients
- `CHAT`: Chat message with sender and text
- `DRAW_EVENT`: Whiteboard stroke (coordinates, color, width)
- `CLEAR_BOARD`: Clear whiteboard command
- `BOARD_SNAPSHOT`: Share board image
- `CHAT_HISTORY`: Previous chat messages for new clients
- `BOARD_HISTORY`: Previous drawing events for new clients
- `FILE_META`: File transfer metadata (name, size, unique ID, recipients)
- `FILE_CHUNK`: File data chunk (Base64 encoded)
- `FILE_COMPLETE`: File transfer completion signal
- `SERVER_INFO`: Server status messages

### Connection Flow

1. Client establishes TCP connection
2. Server accepts and assigns unique client ID
3. Client sends `HELLO` with display name
4. Server responds with `WELCOME` containing client ID
5. Server sends `CLIENT_LIST`, `CHAT_HISTORY`, and `BOARD_HISTORY`
6. Client enters active state and can send/receive messages

## Cross-Platform Support

The application is designed to work identically across Windows, macOS, and Linux:

- **UI Consistency**: Uses standard JavaFX controls ensuring identical layout and behavior
- **Window Management**: OS-native window decorations (title bar, controls)
- **File System**: Platform-agnostic file operations
- **Network**: Standard Java networking APIs work across all platforms

### Platform-Specific Notes

- **macOS**: May show minor console warnings (harmless)
- **Windows**: Ensure firewall allows TCP connections on port 5050
- **Linux**: May require JavaFX dependencies installation via package manager

## Technical Details

### Networking Concepts Applied

- **TCP Socket Programming**: Direct use of `java.net.Socket` and `ServerSocket`
- **Reliable Data Transfer**: Length-prefixed messages ensure complete message delivery
- **Message Framing**: Handles TCP's stream nature with proper framing
- **Flow Control**: Separate threads for reading and writing prevent blocking
- **Connection Management**: Proper lifecycle management for client connections
- **State Synchronization**: Server maintains and distributes state to new clients

### Threading Model

- **Server**: Uses thread pool (`ExecutorService`) for concurrent client handling
- **Client**: Separate executors for reading (blocking) and sending (non-blocking)
- **UI Thread**: All UI updates occur on JavaFX application thread

### State Management

- **Server History**: Maintains chat and board history in memory
- **Client State**: Each client maintains local UI state synchronized with server
- **Connection State**: Server tracks all active connections and their metadata

## Troubleshooting

### Server Won't Start

- **Port Already in Use**: Check if port 5050 is available:
  ```bash
  lsof -i :5050  # macOS/Linux
  netstat -ano | findstr :5050  # Windows
  ```
- **Firewall**: Ensure firewall allows incoming connections on port 5050

### Client Can't Connect

- **Server Not Running**: Ensure server is started before clients
- **Wrong Host/Port**: Verify server address and port number
- **Network Issues**: Check network connectivity and firewall settings

### JavaFX Errors

- **Module Path**: Ensure `PATH_TO_FX` environment variable is set correctly
- **JavaFX Version**: Use JavaFX 21.x compatible with your JDK version
- **JDK Version**: Ensure JDK 25 is installed and active

### Drawing Not Syncing

- **All Clients Connected**: Verify all clients are connected to the same server
- **Check Status Bar**: Each client should show "Connected as [Name] (ID X)"
- **Server Logs**: Check server console for connection messages

## Contributing

This is an academic project. For improvements or bug fixes:

1. Ensure code follows existing style and structure
2. Test on multiple platforms (Windows, macOS, Linux)
3. Maintain backward compatibility with existing protocol
4. Update documentation for any protocol or API changes

## License

This project is developed as part of the Computer Networking Lab course at the University of Dhaka, Department of Computer Science & Engineering.

## Acknowledgments

- **Course**: CSE-3111: Computer Networking Lab
- **Institution**: University of Dhaka
- **Department**: Department of Computer Science & Engineering
- **Technology Stack**: Java, JavaFX, TCP/IP Networking

---

**Note**: This application uses pure TCP protocol without any WebSocket dependencies, demonstrating fundamental networking concepts including socket programming, reliable data transfer, and protocol design.
