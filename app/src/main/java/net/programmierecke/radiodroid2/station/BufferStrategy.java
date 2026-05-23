package net.programmierecke.radiodroid2.station;

/**
 * Buffer strategy enum defining 3 buffering approaches for station playback.
 * Each strategy has specific ExoPlayer buffer parameters.
 */
public enum BufferStrategy {

    /**
     * Light buffer - suitable for stable network connections.
     * Minimal memory usage, low latency. Default option.
     * bufferForPlaybackMs=2.5s: start playing after 2.5s of buffering.
     * minBufferMs=2.5s: matches bufferForPlaybackMs for live streams.
     */
    LIGHT("light", 2500, 2500, 50000, 2500, 2500),

    /**
     * Enhanced buffer - suitable for occasionally unstable networks.
     * bufferForPlaybackMs=10s: delay playback by 10s to absorb network fluctuations.
     * minBufferMs=10s: matches bufferForPlaybackMs for live streams.
     */
    ENHANCED("enhanced", 10000, 10000, 120000, 10000, 10000),

    /**
     * Extreme buffer - suitable for moderately unstable networks.
     * bufferForPlaybackMs=30s: delay playback by 30s for maximum resilience.
     * minBufferMs=30s: matches bufferForPlaybackMs for live streams.
     * If playback still interrupts, consider using a proxy.
     */
    EXTREME("extreme", 30000, 30000, 300000, 30000, 30000);

    /** Preference storage key suffix */
    public final String key;
    /** Time to buffer before playback can start or resume (ms) */
    public final int bufferForPlaybackMs;
    /** Minimum buffer duration the player will maintain (ms) */
    public final int minBufferMs;
    /** Maximum buffer duration the player will maintain (ms) */
    public final int maxBufferMs;
    /** Time to buffer before playback can start after a rebuffer (ms) */
    public final int bufferForPlaybackAfterRebufferMs;
    /** Default rebuffer time (ms), same as bufferForPlaybackAfterRebufferMs for fixed strategies */
    public final int defaultRebufferMs;

    BufferStrategy(String key, int bufferForPlaybackMs, int minBufferMs, int maxBufferMs,
                   int bufferForPlaybackAfterRebufferMs, int defaultRebufferMs) {
        this.key = key;
        this.bufferForPlaybackMs = bufferForPlaybackMs;
        this.minBufferMs = minBufferMs;
        this.maxBufferMs = maxBufferMs;
        this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs;
        this.defaultRebufferMs = defaultRebufferMs;
    }

    /**
     * Get BufferStrategy from preference storage key.
     * @param key the key string stored in preferences
     * @return matching BufferStrategy, defaults to LIGHT if not found
     */
    public static BufferStrategy fromKey(String key) {
        if (key == null || key.isEmpty()) {
            return LIGHT;
        }
        for (BufferStrategy strategy : values()) {
            if (strategy.key.equals(key)) {
                return strategy;
            }
        }
        return LIGHT;
    }
}
