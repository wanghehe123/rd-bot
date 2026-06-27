package com.wish.rd.bootstrap.rocketmq;

import com.wish.rd.engine.ticket.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zero-config in-memory dead-letter repository for local operations and tests.
 */
@Component
@ConditionalOnMissingBean(RepairQueueDeadLetterRepository.class)
public class InMemoryRepairQueueDeadLetterRepository implements RepairQueueDeadLetterRepository {

    private final AtomicLong sequence = new AtomicLong(1L);
    private final Map<String, RepairQueueDeadLetter> records = new LinkedHashMap<>();

    @Override
    public synchronized RepairQueueDeadLetter save(RepairTicketMessage message, String reason) {
        String dedupeKey = dedupeKey(message);
        Optional<RepairQueueDeadLetter> existing = records.values().stream()
                .filter(record -> dedupeKey.equals(dedupeKey(record)))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = Long.toString(sequence.getAndIncrement());
        RepairQueueDeadLetter record = new RepairQueueDeadLetter(
                id,
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
        records.put(id, record);
        return record;
    }

    @Override
    public synchronized List<RepairQueueDeadLetter> list() {
        return List.copyOf(records.values());
    }

    @Override
    public synchronized Optional<RepairQueueDeadLetter> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized RepairQueueDeadLetter markReplayed(String id) {
        RepairQueueDeadLetter existing = records.get(id);
        if (existing == null) {
            throw new NoSuchElementException("dead letter not found: " + id);
        }
        RepairQueueDeadLetter updated = new RepairQueueDeadLetter(
                existing.id(),
                existing.ticketId(),
                existing.traceId(),
                existing.source(),
                existing.eventId(),
                existing.eventType(),
                existing.originalAttempt(),
                existing.reason(),
                existing.messageJson(),
                true,
                existing.createdAtEpochMillis(),
                System.currentTimeMillis()
        );
        records.put(id, updated);
        return updated;
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
}
