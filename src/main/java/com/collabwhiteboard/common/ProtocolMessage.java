package com.collabwhiteboard.common;

import com.google.gson.JsonObject;

/**
 * Simple wrapper to represent protocol messages over TCP.
 * We use a length-prefixed JSON object per message:
 * [4-byte length][UTF-8 JSON payload].
 */
public class ProtocolMessage {

    private final MessageTypes type;
    private final JsonObject payload;

    public ProtocolMessage(MessageTypes type, JsonObject payload) {
        this.type = type;
        this.payload = payload;
    }

    public MessageTypes getType() {
        return type;
    }

    public JsonObject getPayload() {
        return payload;
    }
}


