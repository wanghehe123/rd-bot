package com.wish.rd.engine.requirement.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque command-page cursor bound to one task and selected Coding stage. */
public final class CodingMeaCursor {

    private CodingMeaCursor() {
    }

    /**
     * Encodes the last command on a page.
     *
     * @param taskId owning task
     * @param codingStageRunId selected Coding stage, or blank
     * @param createdAtEpochMillis last command createdAt
     * @param commandId last command id
     * @return opaque cursor
     */
    public static String encode(
            String taskId, String codingStageRunId, long createdAtEpochMillis, String commandId
    ) {
        String payload = "v1|" + safe(taskId) + "|" + safe(codingStageRunId) + "|"
                + createdAtEpochMillis + "|" + safe(commandId);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and validates a cursor against the current selection.
     *
     * @param cursor opaque cursor
     * @param taskId expected task
     * @param codingStageRunId expected Coding stage
     * @return decoded position
     */
    public static Position decode(String cursor, String taskId, String codingStageRunId) {
        String raw = cursor == null ? "" : cursor.strip();
        if (raw.isBlank()) {
            throw new CodingMeaBadRequestException("cursor is required");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new CodingMeaBadRequestException("cursor is not decodable");
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 5 || !"v1".equals(parts[0])) {
            throw new CodingMeaBadRequestException("cursor schema is unsupported");
        }
        if (!safe(taskId).equals(parts[1]) || !safe(codingStageRunId).equals(parts[2])) {
            throw new CodingMeaBadRequestException("cursor does not match task or coding stage");
        }
        try {
            return new Position(Long.parseLong(parts[3]), parts[4]);
        } catch (NumberFormatException invalid) {
            throw new CodingMeaBadRequestException("cursor createdAt is invalid");
        }
    }

    /**
     * Last-seen command on the previous page.
     *
     * @param createdAtEpochMillis createdAt
     * @param commandId command id
     */
    public record Position(long createdAtEpochMillis, String commandId) {
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
