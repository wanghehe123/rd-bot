package com.wish.rd.engine.admin.observability.model;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Allowlisted delivery observability windows.
 */
public enum DeliveryObservabilityWindow {
    ONE_HOUR("1h", Duration.ofHours(1)),
    ONE_DAY("24h", Duration.ofHours(24)),
    SEVEN_DAYS("7d", Duration.ofDays(7)),
    THIRTY_DAYS("30d", Duration.ofDays(30));

    private static final Set<String> ALLOWLIST = new LinkedHashSet<>(
            Arrays.stream(values()).map(DeliveryObservabilityWindow::token).toList());

    private final String token;
    private final Duration duration;

    DeliveryObservabilityWindow(String token, Duration duration) {
        this.token = token;
        this.duration = duration;
    }

    /**
     * @return canonical token such as {@code 24h}
     */
    public String token() {
        return token;
    }

    /**
     * @return window length
     */
    public Duration duration() {
        return duration;
    }

    /**
     * Parses an allowlisted window token. Blank defaults to 24h.
     *
     * @param raw token
     * @return window
     */
    public static DeliveryObservabilityWindow parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ONE_DAY;
        }
        String token = raw.strip().toLowerCase(Locale.ROOT);
        for (DeliveryObservabilityWindow window : values()) {
            if (window.token.equals(token)) {
                return window;
            }
        }
        throw new IllegalArgumentException("unsupported observability window: " + raw);
    }

    /**
     * @return allowlisted tokens
     */
    public static Set<String> allowlist() {
        return Set.copyOf(ALLOWLIST);
    }
}
