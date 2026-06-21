package com.wish.rd.bootstrap.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

final class PostgresPersistenceSupport {

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private PostgresPersistenceSupport() {
    }

    static long parseId(String id) {
        return Long.parseLong(id);
    }

    static String idString(Long id) {
        return id == null ? "" : id.toString();
    }

    static OffsetDateTime toDateTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(SYSTEM_ZONE).toOffsetDateTime();
    }

    static OffsetDateTime nullableDateTime(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0L) {
            return null;
        }
        return toDateTime(epochMillis);
    }

    static long toEpochMillis(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return 0L;
        }
        return dateTime.toInstant().toEpochMilli();
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String checksum(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
