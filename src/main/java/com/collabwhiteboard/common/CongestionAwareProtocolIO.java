package com.collabwhiteboard.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Enhanced ProtocolIO with congestion control simulation.
 * Simulates packet-based transmission with TCP congestion control algorithms.
 * This is a CLIENT-SIDE SIMULATION that doesn't change the actual protocol.
 * Messages are still sent as regular ProtocolIO messages to maintain backward compatibility.
 * The simulation tracks congestion control metrics for visualization purposes.
 */
public class CongestionAwareProtocolIO {
    
    private static final Gson GSON = new Gson();
    private static final int MSS = 1460;  // Maximum Segment Size (bytes)
    private static final double DEFAULT_LOSS_RATE = 0.02;  // 2% packet loss (simulated)
    private static final long DEFAULT_DELAY = 50;  // 50ms network delay (simulated)
    
    private final Socket socket;
    private final CongestionController controller;
    
    // Simulated packet tracking (for visualization only)
    private static class SimulatedPacket {
        final int seqNum;
        long sendTime;  // Not final so it can be reset on retransmission
        boolean acked = false;
        int retransmitCount = 0;
        
        SimulatedPacket(int seqNum, long sendTime) {
            this.seqNum = seqNum;
            this.sendTime = sendTime;
        }
        
        long getAge() {
            return System.currentTimeMillis() - sendTime;
        }
    }
    
    private final Map<Integer, SimulatedPacket> simulatedPackets = new ConcurrentHashMap<>();
    private final Queue<ProtocolMessage> messageQueue = new ConcurrentLinkedQueue<>();
    private int nextSimulatedSeqNum = 1;
    
    // Network simulation parameters
    private volatile double packetLossRate = DEFAULT_LOSS_RATE;
    private volatile long networkDelay = DEFAULT_DELAY;
    
    // Threading
    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(1);
    private volatile boolean running = true;
    
    // Statistics callback
    private Consumer<CongestionController.CongestionStats> statsCallback;
    
    // Track message send times for RTT simulation
    private final Map<Integer, Long> messageSendTimes = new ConcurrentHashMap<>();
    private int messageIdCounter = 0;
    
    // Track expected ACK numbers for proper duplicate ACK detection
    private int expectedNextAck = 1;
    private final Map<Integer, Integer> packetToAckMap = new ConcurrentHashMap<>();  // seqNum -> expected ack number
    
    public CongestionAwareProtocolIO(Socket socket, CongestionMode mode) throws IOException {
        this.socket = socket;
        this.controller = new CongestionController(mode);
        
        // Set up stats callback
        controller.setStatsCallback(stats -> {
            if (statsCallback != null) {
                statsCallback.accept(stats);
            }
        });
        
        // Start timeout checker for simulated packets
        timeoutExecutor.scheduleAtFixedRate(this::checkTimeouts, 100, 100, TimeUnit.MILLISECONDS);
        
        // Periodically simulate ACKs and update stats
        timeoutExecutor.scheduleAtFixedRate(this::simulateAcks, 200, 200, TimeUnit.MILLISECONDS);
        timeoutExecutor.scheduleAtFixedRate(this::updateStats, 100, 100, TimeUnit.MILLISECONDS);
    }
    
    public void setStatsCallback(Consumer<CongestionController.CongestionStats> callback) {
        this.statsCallback = callback;
    }
    
    public void setPacketLossRate(double rate) {
        this.packetLossRate = Math.max(0.0, Math.min(1.0, rate));
    }
    
    public void setNetworkDelay(long delayMs) {
        this.networkDelay = Math.max(0, delayMs);
    }
    
    public CongestionController getController() {
        return controller;
    }
    
    /**
     * Sends a message using congestion control simulation.
     * Simulates packet-based transmission but actually sends regular ProtocolIO messages.
     */
    public void sendMessage(ProtocolMessage message) throws IOException {
        if (!running) {
            throw new IOException("Connection closed");
        }
        
        // Simulate packet-based transmission
        JsonObject root = new JsonObject();
        root.addProperty("type", message.getType().name());
        root.add("payload", message.getPayload());
        byte[] data = GSON.toJson(root).getBytes("UTF-8");
        
        // Simulate splitting into packets
        int numPackets = (int) Math.ceil((double) data.length / MSS);
        
        // Create simulated packets
        List<Integer> packetSeqNums = new ArrayList<>();
        for (int i = 0; i < numPackets; i++) {
            int seqNum = nextSimulatedSeqNum++;
            SimulatedPacket simPacket = new SimulatedPacket(seqNum, System.currentTimeMillis());
            simulatedPackets.put(seqNum, simPacket);
            packetSeqNums.add(seqNum);
            
            // Map sequence number to expected ACK number
            // ACK number is the next expected sequence number after this packet
            packetToAckMap.put(seqNum, seqNum + 1);
            
            // Update congestion controller (simulate sending)
            controller.onPacketSent();
        }
        
        // Actually send the message using regular ProtocolIO (backward compatible)
        messageQueue.offer(message);
        senderExecutor.submit(() -> {
            try {
                // Simulate congestion window limiting
                int cwnd = controller.getCwnd();
                int packetsInFlight = (int) simulatedPackets.values().stream()
                    .filter(p -> !p.acked).count();
                
                // Wait if we're at the congestion window limit
                if (packetsInFlight >= cwnd) {
                    // Would wait in real implementation, but for simulation we just proceed
                }
                
                // Simulate network delay
                if (networkDelay > 0) {
                    try {
                        Thread.sleep(Math.min(networkDelay, 100)); // Cap at 100ms for responsiveness
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                
                // Actually send using regular ProtocolIO
                ProtocolMessage msg = messageQueue.poll();
                if (msg != null) {
                    int msgId = messageIdCounter++;
                    messageSendTimes.put(msgId, System.currentTimeMillis());
                    ProtocolIO.sendMessage(socket, msg);
                    
                    // Simulate packet loss
                    if (Math.random() < packetLossRate) {
                        // Packet lost - will timeout
                        for (int seqNum : packetSeqNums) {
                            SimulatedPacket pkt = simulatedPackets.get(seqNum);
                            if (pkt != null) {
                                pkt.retransmitCount++;
                            }
                        }
                        controller.onTimeout();
                    }
                }
            } catch (IOException e) {
                running = false;
            }
        });
    }
    
    /**
     * Simulates ACK reception for packets that haven't been lost.
     */
    private void simulateAcks() {
        if (!running) return;
        
        List<SimulatedPacket> toAck = new ArrayList<>();
        
        // Simulate ACKs for packets that haven't been lost and are old enough
        for (SimulatedPacket pkt : simulatedPackets.values()) {
            if (!pkt.acked && pkt.getAge() > networkDelay) {
                // Simulate packet loss
                if (Math.random() >= packetLossRate || pkt.retransmitCount > 0) {
                    // Packet not lost (or retransmitted), simulate ACK
                    toAck.add(pkt);
                }
            }
        }
        
        // Process ACKs
        for (SimulatedPacket pkt : toAck) {
            if (!pkt.acked) {
                pkt.acked = true;
                
                // Get the expected ACK number for this packet
                int ackNumber = packetToAckMap.getOrDefault(pkt.seqNum, pkt.seqNum + 1);
                
                // Proper duplicate ACK detection: ACK number less than expected means duplicate
                // This happens when we receive an ACK for data we've already acknowledged
                boolean isDuplicate = (ackNumber < expectedNextAck);
                
                // Update expected next ACK if this is a new ACK
                if (!isDuplicate && ackNumber >= expectedNextAck) {
                    expectedNextAck = ackNumber + 1;
                }
                
                // Update congestion controller
                long rtt = pkt.getAge();
                controller.updateRTT(rtt);
                controller.onAckReceived(ackNumber, isDuplicate);
                
                // Remove old acked packets
                if (pkt.getAge() > 5000) { // Keep for 5 seconds for visualization
                    simulatedPackets.remove(pkt.seqNum);
                    packetToAckMap.remove(pkt.seqNum);
                }
            }
        }
    }
    
    /**
     * Checks for timed-out packets and triggers retransmission simulation.
     */
    private void checkTimeouts() {
        if (!running) return;
        
        long timeoutThreshold = controller.getTimeoutThreshold();
        List<SimulatedPacket> timedOutPackets = new ArrayList<>();
        
        for (SimulatedPacket pkt : simulatedPackets.values()) {
            if (pkt.getAge() > timeoutThreshold && !pkt.acked) {
                timedOutPackets.add(pkt);
            }
        }
        
        for (SimulatedPacket pkt : timedOutPackets) {
            // Timeout occurred
            controller.onTimeout();
            
            // Simulate retransmission
            pkt.retransmitCount++;
            pkt.sendTime = System.currentTimeMillis(); // Reset send time
            
            // Update controller
            controller.onPacketSent();
        }
    }
    
    /**
     * Periodically updates and publishes congestion control statistics.
     */
    private void updateStats() {
        if (!running) return;
        
        // The controller already has the stats, just trigger the callback
        CongestionController.CongestionStats stats = controller.getStats();
        if (statsCallback != null) {
            statsCallback.accept(stats);
        }
    }
    
    /**
     * Shuts down the congestion-aware protocol handler.
     */
    public void shutdown() {
        running = false;
        senderExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
    }
}
