package com.collabwhiteboard.common;

/**
 * Enumeration of congestion control phases.
 */
public enum CongestionPhase {
    SLOW_START,
    CONGESTION_AVOIDANCE,
    FAST_RECOVERY  // Reno only
}

