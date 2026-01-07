package com.collabwhiteboard.common;

import java.util.function.Consumer;

/**
 * Implements TCP congestion control algorithms (Tahoe and Reno).
 * Tracks congestion window, slow start threshold, and phase transitions.
 */
public class CongestionController {
    
    private final CongestionMode mode;
    private int cwnd;  // Congestion window (in packets/MSS)
    private int ssthresh;  // Slow start threshold
    private CongestionPhase phase;
    private long estimatedRTT;  // Estimated round trip time in milliseconds
    private int duplicateAckCount;  // For Reno fast recovery
    private int nextSequenceNumber;
    private int expectedAckNumber;  // Expected next ACK number for duplicate detection
    private double congestionAvoidanceCounter;  // Fractional counter for congestion avoidance
    private int transmissionRound;  // Current transmission round number
    private int acksInCurrentRound;  // ACKs received in current round
    
    // Statistics
    private int totalPacketsSent;
    private int totalAcksReceived;
    private int timeoutCount;
    private int duplicateAckCountTotal;
    
    // Callback for state changes (for UI updates)
    private Consumer<CongestionStats> statsCallback;
    
    // Constants
    private static final int INITIAL_CWND = 1;
    private static final int INITIAL_SSTHRESH = 64;
    private static final int INITIAL_RTT = 100;  // milliseconds
    private static final int MAX_CWND = 1000;
    private static final int MIN_CWND = 1;
    
    public CongestionController(CongestionMode mode) {
        this.mode = mode;
        this.cwnd = INITIAL_CWND;
        this.ssthresh = INITIAL_SSTHRESH;
        this.phase = CongestionPhase.SLOW_START;
        this.estimatedRTT = INITIAL_RTT;
        this.duplicateAckCount = 0;
        this.nextSequenceNumber = 1;
        this.expectedAckNumber = 1;
        this.congestionAvoidanceCounter = 0.0;
        this.transmissionRound = 0;
        this.acksInCurrentRound = 0;
        this.totalPacketsSent = 0;
        this.totalAcksReceived = 0;
        this.timeoutCount = 0;
        this.duplicateAckCountTotal = 0;
    }
    
    public void setStatsCallback(Consumer<CongestionStats> callback) {
        this.statsCallback = callback;
    }
    
    /**
     * Called when a packet is sent. Updates sequence number tracking.
     */
    public void onPacketSent() {
        totalPacketsSent++;
        nextSequenceNumber++;
        notifyStatsUpdate();
    }
    
    /**
     * Called when an ACK is received. Updates cwnd according to the algorithm.
     * Properly detects duplicate ACKs by comparing ackNumber with expectedAckNumber.
     */
    public void onAckReceived(int ackNumber, boolean isDuplicate) {
        totalAcksReceived++;
        
        // Proper duplicate ACK detection: ACK number less than expected means duplicate
        // OR explicitly marked as duplicate by caller
        boolean isActualDuplicate = isDuplicate || (ackNumber < expectedAckNumber);
        
        if (isActualDuplicate && ackNumber < expectedAckNumber) {
            // This is a duplicate ACK (ACK for already acknowledged data)
            duplicateAckCount++;
            duplicateAckCountTotal++;
            
            if (duplicateAckCount == 3 && phase != CongestionPhase.FAST_RECOVERY) {
                // 3rd duplicate ACK - both Tahoe and Reno react, but differently
                if (mode == CongestionMode.RENO) {
                    // Reno: Fast retransmit - halve cwnd and enter fast recovery
                    handleFastRetransmit();
                } else {
                    // Tahoe: Treat like timeout - drop cwnd to 1 and re-enter slow start
                    ssthresh = Math.max(cwnd / 2, 2);  // Set ssthresh to half of current cwnd
                    cwnd = MIN_CWND;  // Reset cwnd to 1
                    phase = CongestionPhase.SLOW_START;
                    duplicateAckCount = 0;
                    congestionAvoidanceCounter = 0.0;
                    acksInCurrentRound = 0;
                    notifyStatsUpdate();
                }
            } else if (mode == CongestionMode.RENO && phase == CongestionPhase.FAST_RECOVERY) {
                // In fast recovery (Reno only), increment cwnd for each duplicate ACK
                cwnd = Math.min(cwnd + 1, MAX_CWND);
            }
            // Tahoe ignores duplicate ACKs after the 3rd one (no fast recovery)
        } else if (ackNumber >= expectedAckNumber) {
            // New ACK received - acknowledges new data
            duplicateAckCount = 0;
            expectedAckNumber = ackNumber + 1;  // Update expected next ACK
            
            // Track transmission rounds: increment round when we've received ACKs for a full window
            acksInCurrentRound++;
            if (acksInCurrentRound >= cwnd) {
                transmissionRound++;
                acksInCurrentRound = 0;
            }
            
            if (phase == CongestionPhase.SLOW_START) {
                // Slow Start: cwnd += 1 for each ACK (exponential growth per RTT)
                cwnd = Math.min(cwnd + 1, MAX_CWND);
                
                // Transition to congestion avoidance when cwnd reaches ssthresh
                if (cwnd >= ssthresh) {
                    phase = CongestionPhase.CONGESTION_AVOIDANCE;
                    congestionAvoidanceCounter = 0.0;
                }
            } else if (phase == CongestionPhase.CONGESTION_AVOIDANCE) {
                // Congestion Avoidance: cwnd += 1/cwnd per ACK (linear growth: 1 packet per RTT)
                // Use fractional counter for accurate implementation
                congestionAvoidanceCounter += 1.0 / cwnd;
                if (congestionAvoidanceCounter >= 1.0) {
                    cwnd = Math.min(cwnd + 1, MAX_CWND);
                    congestionAvoidanceCounter -= 1.0;
                }
            } else if (phase == CongestionPhase.FAST_RECOVERY) {
                // Exit fast recovery on new ACK
                cwnd = ssthresh;
                phase = CongestionPhase.CONGESTION_AVOIDANCE;
                duplicateAckCount = 0;
                congestionAvoidanceCounter = 0.0;
            }
        }
        
        notifyStatsUpdate();
    }
    
    /**
     * Called when a timeout occurs. Implements timeout handling for both algorithms.
     * Both Tahoe and Reno handle timeout identically.
     */
    public void onTimeout() {
        timeoutCount++;
        
        // Both Tahoe and Reno handle timeout the same way
        ssthresh = Math.max(cwnd / 2, 2);  // Set ssthresh to half of current cwnd (at least 2)
        cwnd = MIN_CWND;  // Reset cwnd to 1
        phase = CongestionPhase.SLOW_START;
        duplicateAckCount = 0;
        congestionAvoidanceCounter = 0.0;
        acksInCurrentRound = 0;  // Reset round tracking
        
        // Force immediate update to capture the drastic drop in the graph
        notifyStatsUpdate();
    }
    
    /**
     * Handles fast retransmit (Reno only).
     * Triggered when 3 duplicate ACKs are received.
     * According to TCP Reno: ssthresh = cwnd/2, then cwnd = ssthresh (enter fast recovery).
     */
    private void handleFastRetransmit() {
        ssthresh = Math.max(cwnd / 2, 2);  // Set ssthresh to half of current cwnd
        cwnd = ssthresh;  // Set cwnd to ssthresh (cwnd/2) - enter fast recovery
        phase = CongestionPhase.FAST_RECOVERY;
        congestionAvoidanceCounter = 0.0;
        acksInCurrentRound = 0;  // Reset round tracking for fast recovery
        
        // Force immediate update to capture the drop in the graph
        notifyStatsUpdate();
    }
    
    /**
     * Updates the estimated RTT based on a sample.
     */
    public void updateRTT(long sampleRTT) {
        // Simple exponential weighted moving average
        estimatedRTT = (long) (0.875 * estimatedRTT + 0.125 * sampleRTT);
    }
    
    /**
     * Gets the current congestion window size.
     */
    public int getCwnd() {
        return cwnd;
    }
    
    /**
     * Gets the slow start threshold.
     */
    public int getSsthresh() {
        return ssthresh;
    }
    
    /**
     * Gets the current phase.
     */
    public CongestionPhase getPhase() {
        return phase;
    }
    
    /**
     * Gets the estimated RTT.
     */
    public long getEstimatedRTT() {
        return estimatedRTT;
    }
    
    /**
     * Gets the next sequence number and increments it.
     */
    public int getNextSequenceNumber() {
        return nextSequenceNumber++;
    }
    
    /**
     * Gets the timeout threshold (typically 2 * RTT).
     */
    public long getTimeoutThreshold() {
        return estimatedRTT * 2;
    }
    
    /**
     * Gets the current transmission round number.
     */
    public int getTransmissionRound() {
        return transmissionRound;
    }
    
    /**
     * Gets current statistics.
     */
    public CongestionStats getStats() {
        return new CongestionStats(
            mode,
            cwnd,
            ssthresh,
            phase,
            estimatedRTT,
            totalPacketsSent,
            totalAcksReceived,
            timeoutCount,
            duplicateAckCountTotal,
            duplicateAckCount,
            transmissionRound
        );
    }
    
    private void notifyStatsUpdate() {
        if (statsCallback != null) {
            // Always notify on state changes to capture all transitions in the graph
            statsCallback.accept(getStats());
        }
    }
    
    /**
     * Force a stats update (useful for capturing immediate events like timeout/fast retransmit).
     */
    public void forceStatsUpdate() {
        notifyStatsUpdate();
    }
    
    /**
     * Data class for congestion control statistics.
     */
    public static class CongestionStats {
        public final CongestionMode mode;
        public final int cwnd;
        public final int ssthresh;
        public final CongestionPhase phase;
        public final long rtt;
        public final int totalPacketsSent;
        public final int totalAcksReceived;
        public final int timeoutCount;
        public final int totalDuplicateAcks;
        public final int currentDuplicateAckCount;
        public final int transmissionRound;
        
        public CongestionStats(CongestionMode mode, int cwnd, int ssthresh, CongestionPhase phase,
                              long rtt, int totalPacketsSent, int totalAcksReceived,
                              int timeoutCount, int totalDuplicateAcks, int currentDuplicateAckCount,
                              int transmissionRound) {
            this.mode = mode;
            this.cwnd = cwnd;
            this.ssthresh = ssthresh;
            this.phase = phase;
            this.rtt = rtt;
            this.totalPacketsSent = totalPacketsSent;
            this.totalAcksReceived = totalAcksReceived;
            this.timeoutCount = timeoutCount;
            this.totalDuplicateAcks = totalDuplicateAcks;
            this.currentDuplicateAckCount = currentDuplicateAckCount;
            this.transmissionRound = transmissionRound;
        }
    }
}

