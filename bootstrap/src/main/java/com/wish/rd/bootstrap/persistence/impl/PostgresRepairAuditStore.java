package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairAuditEventRow;
import com.wish.rd.bootstrap.persistence.mapper.RepairAuditEventMapper;
import com.wish.rd.engine.audit.model.RepairAuditEvent;
import com.wish.rd.engine.audit.model.RepairAuditEventType;
import com.wish.rd.engine.audit.RepairAuditQueryPort;
import com.wish.rd.engine.audit.RepairAuditSinkPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL audit sink/query adapter for repair workflow events.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRepairAuditStore implements RepairAuditSinkPort, RepairAuditQueryPort {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final RepairAuditEventMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresRepairAuditStore(RepairAuditEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(RepairAuditEvent event) {
        mapper.insertEvent(toRow(event));
    }

    @Override
    public List<RepairAuditEvent> events() {
        return mapper.selectList(new QueryWrapper<RepairAuditEventRow>().orderByDesc("created_at", "id"))
                .stream()
                .sorted(Comparator
                        .comparing((RepairAuditEventRow row) -> row.createdAt).reversed()
                        .thenComparing(row -> row.id, Comparator.reverseOrder()))
                .map(this::toEvent)
                .toList();
    }

    @Override
    public List<RepairAuditEvent> eventsByRepairRecordId(String repairRecordId) {
        return mapper.selectList(new QueryWrapper<RepairAuditEventRow>()
                        .eq("repair_record_id", repairRecordId == null ? "" : repairRecordId)
                        .orderByDesc("created_at", "id"))
                .stream()
                .map(this::toEvent)
                .toList();
    }

    private RepairAuditEventRow toRow(RepairAuditEvent event) {
        RepairAuditEvent safe = event == null
                ? RepairAuditEvent.now("", "", "", RepairAuditEventType.EXECUTION_FINISHED, "", "", Map.of())
                : event;
        RepairAuditEventRow row = new RepairAuditEventRow();
        row.repairRecordId = safe.repairRecordId();
        row.taskId = safe.taskId();
        row.ticketId = safe.ticketId();
        row.eventType = safe.type().name();
        row.externalSystem = safe.externalSystem();
        row.summary = safe.summary();
        row.metadataJson = writeJson(safe.metadata());
        row.createdAt = PostgresPersistenceSupport.toDateTime(safe.createdAtEpochMillis());
        return row;
    }

    private RepairAuditEvent toEvent(RepairAuditEventRow row) {
        return new RepairAuditEvent(
                PostgresPersistenceSupport.safe(row.repairRecordId),
                PostgresPersistenceSupport.safe(row.taskId),
                PostgresPersistenceSupport.safe(row.ticketId),
                parseType(row.eventType),
                PostgresPersistenceSupport.safe(row.externalSystem),
                PostgresPersistenceSupport.safe(row.summary),
                readMap(row.metadataJson),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private RepairAuditEventType parseType(String value) {
        try {
            return RepairAuditEventType.valueOf(PostgresPersistenceSupport.safe(value));
        } catch (IllegalArgumentException exception) {
            return RepairAuditEventType.EXECUTION_FINISHED;
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
