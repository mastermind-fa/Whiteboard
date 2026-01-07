package com.collabwhiteboard.common;

/**
 * Represents a data packet with sequence number for congestion control.
 */
public class Packet {
    private final int sequenceNumber;
    private final byte[] data;
    private final long timestamp;
    private boolean acknowledged;
    private int retransmitCount;

    public Packet(int sequenceNumber, byte[] data) {
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
        this.acknowledged = false;
        this.retransmitCount = 0;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public byte[] getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    public int getRetransmitCount() {
        return retransmitCount;
    }

    public void incrementRetransmitCount() {
        this.retransmitCount++;
    }

    public long getAge() {
        return System.currentTimeMillis() - timestamp;
    }
}

