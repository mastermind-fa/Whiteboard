package com.collabwhiteboard.common;

public enum MessageTypes {
    HELLO,              // initial handshake, includes client display name
    WELCOME,            // server -> client: sends assigned client id
    CLIENT_LIST,        // server -> clients: current connected clients
    CHAT,               // chat messages
    CHAT_HISTORY,       // server -> client: existing chat history on join
    DRAW_EVENT,         // whiteboard drawing stroke
    BOARD_HISTORY,      // server -> client: existing board strokes on join
    CLEAR_BOARD,        // clear whiteboard
    BOARD_SNAPSHOT,     // share full board image
    FILE_META,          // file transfer metadata
    FILE_CHUNK,         // file transfer chunk
    FILE_COMPLETE,      // file transfer finished
    SERVER_INFO,        // server informational messages
    ERROR               // error messages
}


