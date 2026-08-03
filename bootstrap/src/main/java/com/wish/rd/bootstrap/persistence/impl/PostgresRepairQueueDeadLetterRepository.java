package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairQueueDeadLetterRow;
import com.wish.rd.bootstrap.persistence.mapper.RepairQueueDeadLetterMapper;
import com.wish.rd.engine.ticket.model.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL dead-letter repository for repair queue recovery.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRepairQueueDeadLetterRepository implements RepairQueueDeadLetterRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> MALFORMED_ALLOWED_FIELDS = Set.of(
            "ticketId",
            "priority",
            "traceId",
            "attempt",
            "source",
            "eventId",
            "eventType",
            "createdAt"
    );
    private static final String VALID_DEAD_LETTER_ID_PREFIX = "redis-stream-valid-";
    private static final String MALFORMED_DEAD_LETTER_ID_PREFIX = "redis-stream-malformed-";

    private final RepairQueueDeadLetterMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresRepairQueueDeadLetterRepository(
            RepairQueueDeadLetterMapper mapper,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public RepairQueueDeadLetter save(RepairTicketMessage message, String reason) {
        RepairQueueDeadLetter deadLetter = new RepairQueueDeadLetter(
                validDeadLetterId(message),
                message == null ? "" : message.ticketId(),
                message == null ? "" : message.traceId(),
                message == null ? "" : message.source(),
                message == null ? "" : message.eventId(),
                message == null ? "" : message.eventType(),
                message == null ? 0 : message.attempt(),
                reason,
                messageJson(message),
                false,
                System.currentTimeMillis(),
                0L
        );
        return insertIfAbsent(deadLetter, "valid Redis Stream dead letter");
    }

    /**
     * Persists a malformed Redis Stream record using its original record ID as the idempotency key.
     *
     * <p>The current {@code repair_queue_dead_letters} schema has no transport-record column, so the
     * safety-filtered record ID is retained inside the existing {@code message_json} payload. This keeps
     * the malformed entry recoverable without a schema migration and avoids accepting unfiltered fields.
     *
     * @param redisRecordId original Redis Stream record ID
     * @param rawSafeFields safety-filtered thin transport fields
     * @param reason bounded malformed-entry reason
     * @return persisted or previously persisted dead-letter record
     */
    @Override
    public RepairQueueDeadLetter saveMalformed(
            String redisRecordId,
            Map<String, String> rawSafeFields,
            String reason
    ) {
        String safeRecordId = redisRecordId == null ? "" : redisRecordId;
        Map<String, String> fields = malformedMessageJson(safeRecordId, rawSafeFields);
        RepairQueueDeadLetter deadLetter = new RepairQueueDeadLetter(
                malformedDeadLetterId(safeRecordId),
                fields.getOrDefault("ticketId", ""),
                fields.getOrDefault("traceId", ""),
                fields.getOrDefault("source", ""),
                fields.getOrDefault("eventId", ""),
                fields.getOrDefault("eventType", ""),
                parseAttempt(fields.get("attempt")),
                reason,
                fields,
                false,
                System.currentTimeMillis(),
                0L
        );
        return insertIfAbsent(deadLetter, "malformed Redis Stream dead letter: " + safeRecordId);
    }

    @Override
    public List<RepairQueueDeadLetter> list() {
        return mapper.selectList(new QueryWrapper<RepairQueueDeadLetterRow>().orderByDesc("created_at", "id"))
                .stream()
                .sorted(Comparator
                        .comparing((RepairQueueDeadLetterRow row) -> row.createdAt).reversed()
                        .thenComparing(row -> row.id, Comparator.reverseOrder()))
                .map(this::toDeadLetter)
                .toList();
    }

    @Override
    public Optional<RepairQueueDeadLetter> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDeadLetter);
    }

    @Override
    public RepairQueueDeadLetter markReplayed(String id) {
        RepairQueueDeadLetterRow row = mapper.selectById(id);
        if (row == null) {
            throw new NoSuchElementException("dead letter not found: " + id);
        }
        row.replayed = true;
        row.replayedAt = OffsetDateTime.now();
        mapper.updateReplayState(row);
        return toDeadLetter(row);
    }

    private RepairQueueDeadLetter insertIfAbsent(RepairQueueDeadLetter deadLetter, String description) {
        if (mapper.insertDeadLetterIfAbsent(toRow(deadLetter)) == 1) {
            return deadLetter;
        }
        RepairQueueDeadLetterRow existing = mapper.selectById(deadLetter.id());
        if (existing != null) {
            return toDeadLetter(existing);
        }
        throw new IllegalStateException(description + " conflicted without a persisted row: " + deadLetter.id());
    }

    private RepairQueueDeadLetterRow toRow(RepairQueueDeadLetter deadLetter) {
        RepairQueueDeadLetterRow row = new RepairQueueDeadLetterRow();
        row.id = deadLetter.id();
        row.ticketId = deadLetter.ticketId();
        row.traceId = deadLetter.traceId();
        row.source = deadLetter.source();
        row.eventId = deadLetter.eventId();
        row.eventType = deadLetter.eventType();
        row.originalAttempt = deadLetter.originalAttempt();
        row.reason = deadLetter.reason();
        row.messageJson = writeJson(deadLetter.messageJson());
        row.replayed = deadLetter.replayed();
        row.createdAt = PostgresPersistenceSupport.toDateTime(deadLetter.createdAtEpochMillis());
        row.replayedAt = PostgresPersistenceSupport.nullableDateTime(deadLetter.replayedAtEpochMillis());
        return row;
    }

    private RepairQueueDeadLetter toDeadLetter(RepairQueueDeadLetterRow row) {
        return new RepairQueueDeadLetter(
                PostgresPersistenceSupport.safe(row.id),
                PostgresPersistenceSupport.safe(row.ticketId),
                PostgresPersistenceSupport.safe(row.traceId),
                PostgresPersistenceSupport.safe(row.source),
                PostgresPersistenceSupport.safe(row.eventId),
                PostgresPersistenceSupport.safe(row.eventType),
                row.originalAttempt == null ? 0 : row.originalAttempt,
                PostgresPersistenceSupport.safe(row.reason),
                readMap(row.messageJson),
                Boolean.TRUE.equals(row.replayed),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.replayedAt)
        );
    }

    private Map<String, String> messageJson(RepairTicketMessage message) {
        if (message == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ticketId", message.ticketId());
        values.put("priority", message.priority());
        values.put("traceId", message.traceId());
        values.put("attempt", Integer.toString(message.attempt()));
        values.put("source", message.source());
        values.put("eventId", message.eventId());
        values.put("eventType", message.eventType());
        values.put("createdAt", message.createdAt().toString());
        return Map.copyOf(values);
    }

    private static Map<String, String> malformedMessageJson(
            String redisRecordId,
            Map<String, String> rawSafeFields
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> suppliedFields = rawSafeFields == null ? Map.of() : rawSafeFields;
        for (String allowedField : MALFORMED_ALLOWED_FIELDS) {
            String value = suppliedFields.get(allowedField);
            if (value != null) {
                values.put(allowedField, value);
            }
        }
        values.put("redisRecordId", redisRecordId);
        return Map.copyOf(values);
    }

    private static String malformedDeadLetterId(String redisRecordId) {
        return deterministicDeadLetterId(MALFORMED_DEAD_LETTER_ID_PREFIX, redisRecordId);
    }

    private static String validDeadLetterId(RepairTicketMessage message) {
        return deterministicDeadLetterId(
                VALID_DEAD_LETTER_ID_PREFIX,
                lengthPrefixed(message == null ? "" : message.ticketId()),
                lengthPrefixed(message == null ? "" : message.source()),
                lengthPrefixed(message == null ? "" : message.eventId()),
                lengthPrefixed(message == null ? "" : message.eventType())
        );
    }

    private static String deterministicDeadLetterId(String prefix, String... identityParts) {
        try {
            String identity = String.join("", identityParts);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for Redis Stream dead-letter deduplication", exception);
        }
    }

    private static String lengthPrefixed(String value) {
        String safeValue = value == null ? "" : value;
        return safeValue.length() + ":" + safeValue;
    }

    private static int parseAttempt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String writeJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private Map<String, String> readMap(String value) {
        try {
            return objectMapper.readValue(PostgresPersistenceSupport.safe(value).isBlank() ? "{}" : value, STRING_MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
