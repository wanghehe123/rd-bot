package com.wish.rd.bootstrap.queue.impl;

import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.model.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory dead-letter repository for local operations and deterministic tests.
 *
 * <p>PostgreSQL remains the production repository; this adapter also retains malformed
 * Redis record IDs for memory-mode diagnostics without creating a synthetic ticket.
 */
@Component
@ConditionalOnMissingBean(RepairQueueDeadLetterRepository.class)
public class InMemoryRepairQueueDeadLetterRepository implements RepairQueueDeadLetterRepository {

    private static final List<String> MALFORMED_ALLOWED_FIELDS = List.of(
            "ticketId",
            "priority",
            "traceId",
            "attempt",
            "source",
            "eventId",
            "eventType",
            "createdAt"
    );

    private final AtomicLong sequence = new AtomicLong(1L);
    private final Map<String, RepairQueueDeadLetter> records = new LinkedHashMap<>();

    /**
     * Saves a regular exhausted-retry dead letter.
     *
     * @param message original thin message
     * @param reason bounded dead-letter reason
     * @return saved or existing dead-letter record
     */
    @Override
    public synchronized RepairQueueDeadLetter save(RepairTicketMessage message, String reason) {
        String dedupeKey = dedupeKey(message);
        Optional<RepairQueueDeadLetter> existing = records.values().stream()
                .filter(record -> dedupeKey.equals(dedupeKey(record)))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        RepairQueueDeadLetter record = new RepairQueueDeadLetter(
                nextId(),
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
        records.put(record.id(), record);
        return record;
    }

    /**
     * Saves a malformed Redis Stream record without inventing a valid ticket ID.
     *
     * @param redisRecordId original Redis Stream record ID
     * @param rawSafeFields safety-filtered thin transport fields
     * @param reason malformed-entry reason
     * @return saved or existing malformed dead-letter record
     */
    @Override
    public synchronized RepairQueueDeadLetter saveMalformed(
            String redisRecordId,
            Map<String, String> rawSafeFields,
            String reason
    ) {
        String safeRecordId = redisRecordId == null ? "" : redisRecordId;
        Optional<RepairQueueDeadLetter> existing = records.values().stream()
                .filter(record -> safeRecordId.equals(record.messageJson().get("redisRecordId")))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> suppliedFields = rawSafeFields == null ? Map.of() : rawSafeFields;
        for (String allowedField : MALFORMED_ALLOWED_FIELDS) {
            String value = suppliedFields.get(allowedField);
            if (value != null) {
                fields.put(allowedField, value);
            }
        }
        fields.put("redisRecordId", safeRecordId);
        RepairQueueDeadLetter record = new RepairQueueDeadLetter(
                nextId(),
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
        records.put(record.id(), record);
        return record;
    }

    /**
     * Lists saved dead letters in insertion order.
     *
     * @return immutable dead-letter list
     */
    @Override
    public synchronized List<RepairQueueDeadLetter> list() {
        return List.copyOf(records.values());
    }

    /**
     * Finds a dead letter by its storage identifier.
     *
     * @param id dead-letter ID
     * @return matching record when present
     */
    @Override
    public synchronized Optional<RepairQueueDeadLetter> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    /**
     * Marks a persisted dead letter as manually replayed.
     *
     * @param id dead-letter ID
     * @return updated record
     */
    @Override
    public synchronized RepairQueueDeadLetter markReplayed(String id) {
        RepairQueueDeadLetter existing = records.get(id);
        if (existing == null) {
            throw new NoSuchElementException("dead letter not found: " + id);
        }
        RepairQueueDeadLetter updated = new RepairQueueDeadLetter(
                existing.id(), existing.ticketId(), existing.traceId(), existing.source(), existing.eventId(),
                existing.eventType(), existing.originalAttempt(), existing.reason(), existing.messageJson(), true,
                existing.createdAtEpochMillis(), System.currentTimeMillis()
        );
        records.put(id, updated);
        return updated;
    }

    private String nextId() {
        return Long.toString(sequence.getAndIncrement());
    }

    private static String dedupeKey(RepairTicketMessage message) {
        if (message == null) {
            return "";
        }
        return String.join("|", message.ticketId(), message.source(), message.eventId(), message.eventType());
    }

    private static String dedupeKey(RepairQueueDeadLetter record) {
        return String.join("|", record.ticketId(), record.source(), record.eventId(), record.eventType());
    }

    private static Map<String, String> messageJson(RepairTicketMessage message) {
        if (message == null) {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ticketId", message.ticketId());
        fields.put("priority", message.priority());
        fields.put("traceId", message.traceId());
        fields.put("attempt", Integer.toString(message.attempt()));
        fields.put("source", message.source());
        fields.put("eventId", message.eventId());
        fields.put("eventType", message.eventType());
        fields.put("createdAt", message.createdAt().toString());
        return Map.copyOf(fields);
    }

    private static int parseAttempt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
