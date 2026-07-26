package com.wish.rd.bootstrap.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Opt-in retention and size policy for Pi raw events and session archives.
 * Production values are intentionally not guessed; enabling the publisher
 * without explicit bucket and retention values fails closed.
 */
@Component
@ConfigurationProperties(prefix = "rd.executor.pi.artifact-archive")
public class PiAgentArtifactArchiveProperties {

    private boolean enabled;
    private String rawBucket = "";
    private String sessionBucket = "";
    private long rawRetentionMillis;
    private long sessionRetentionMillis;
    private long maxRawBytes = 16L * 1024L * 1024L;
    private long maxSessionFileBytes = 16L * 1024L * 1024L;
    private int maxSessionFiles = 128;
    private int cleanupBatchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRawBucket() {
        return rawBucket;
    }

    public void setRawBucket(String rawBucket) {
        this.rawBucket = normalize(rawBucket);
    }

    public String getSessionBucket() {
        return sessionBucket;
    }

    public void setSessionBucket(String sessionBucket) {
        this.sessionBucket = normalize(sessionBucket);
    }

    public long getRawRetentionMillis() {
        return rawRetentionMillis;
    }

    public void setRawRetentionMillis(long rawRetentionMillis) {
        this.rawRetentionMillis = rawRetentionMillis;
    }

    public long getSessionRetentionMillis() {
        return sessionRetentionMillis;
    }

    public void setSessionRetentionMillis(long sessionRetentionMillis) {
        this.sessionRetentionMillis = sessionRetentionMillis;
    }

    public long getMaxRawBytes() {
        return maxRawBytes;
    }

    public void setMaxRawBytes(long maxRawBytes) {
        this.maxRawBytes = maxRawBytes;
    }

    public long getMaxSessionFileBytes() {
        return maxSessionFileBytes;
    }

    public void setMaxSessionFileBytes(long maxSessionFileBytes) {
        this.maxSessionFileBytes = maxSessionFileBytes;
    }

    public int getMaxSessionFiles() {
        return maxSessionFiles;
    }

    public void setMaxSessionFiles(int maxSessionFiles) {
        this.maxSessionFiles = maxSessionFiles;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public void validateForPublishing() {
        if (!enabled) {
            return;
        }
        if (rawBucket.isBlank() || sessionBucket.isBlank()) {
            throw new IllegalStateException(
                    "Pi private artifact archive requires rawBucket and sessionBucket"
            );
        }
        if (rawRetentionMillis <= 0L || sessionRetentionMillis <= 0L) {
            throw new IllegalStateException(
                    "Pi private artifact archive requires positive raw and session retention values"
            );
        }
        if (maxRawBytes <= 0L || maxSessionFileBytes <= 0L || maxSessionFiles <= 0 || cleanupBatchSize <= 0) {
            throw new IllegalStateException("Pi private artifact archive size limits must be positive");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
