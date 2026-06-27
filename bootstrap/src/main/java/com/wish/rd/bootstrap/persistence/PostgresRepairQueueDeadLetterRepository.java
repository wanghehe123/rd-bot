package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairQueueDeadLetterRow;
import com.wish.rd.bootstrap.persistence.mapper.RepairQueueDeadLetterMapper;
import com.wish.rd.engine.ticket.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * PostgreSQL dead-letter repository for repair queue recovery.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRepairQueueDeadLetterRepository implements RepairQueueDeadLetterRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final RepairQueueDeadLetterMapper mapper;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator idGenerator;

    public PostgresRepairQueueDeadLetterRepository(
            RepairQueueDeadLetterMapper mapper,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator idGenerator
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public RepairQueueDeadLetter save(RepairTicketMessage message, String reason) {
        Optional<RepairQueueDeadLetter> existing = findExisting(message);
        if (existing.isPresent()) {
            return existing.get();
        }
        RepairQueueDeadLetter deadLetter = new RepairQueueDeadLetter(
                idGenerator.nextIdString(),
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
        mapper.insertDeadLetter(toRow(deadLetter));
        return deadLetter;
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

    private Optional<RepairQueueDeadLetter> findExisting(RepairTicketMessage message) {
        if (message == null) {
            return Optional.empty();
        }
        return mapper.selectList(new QueryWrapper<RepairQueueDeadLetterRow>()
                        .eq("ticket_id", message.ticketId())
                        .eq("source", message.source())
                        .eq("event_id", message.eventId())
                        .eq("event_type", message.eventType()))
                .stream()
                .findFirst()
                .map(this::toDeadLetter);
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
