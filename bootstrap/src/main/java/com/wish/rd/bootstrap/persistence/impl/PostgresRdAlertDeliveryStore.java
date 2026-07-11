package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RdAlertDeliveryRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAlertDeliveryMapper;
import com.wish.rd.rag.project.alert.RdAlertDeliveryStore;
import com.wish.rd.rag.project.alert.model.RdAlertDelivery;
import com.wish.rd.rag.project.alert.model.RdAlertDeliveryStatus;
import com.wish.rd.rag.project.alert.model.RdAlertRecipientType;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** PostgreSQL alert delivery audit store with idempotent inserts. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRdAlertDeliveryStore implements RdAlertDeliveryStore {

    private final RdAlertDeliveryMapper mapper;

    public PostgresRdAlertDeliveryStore(RdAlertDeliveryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RdAlertDelivery save(RdAlertDelivery delivery) {
        mapper.insertIfAbsent(toRow(delivery));
        return findByIdempotencyKey(delivery.idempotencyKey()).orElse(delivery);
    }

    @Override
    public RdAlertDelivery updateOutcome(RdAlertDelivery delivery) {
        mapper.updateOutcome(toRow(delivery));
        return findByIdempotencyKey(delivery.idempotencyKey()).orElse(delivery);
    }

    @Override
    public Optional<RdAlertDelivery> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(mapper.findByIdempotencyKey(idempotencyKey)).map(this::toDelivery);
    }

    @Override
    public List<RdAlertDelivery> listByTask(String taskId) {
        return mapper.listByTask(PostgresPersistenceSupport.parseId(taskId)).stream().map(this::toDelivery).toList();
    }

    private RdAlertDeliveryRow toRow(RdAlertDelivery delivery) {
        RdAlertDeliveryRow row = new RdAlertDeliveryRow();
        row.id = PostgresPersistenceSupport.parseId(delivery.deliveryId());
        row.taskId = PostgresPersistenceSupport.parseId(delivery.taskId());
        row.projectId = PostgresPersistenceSupport.parseId(delivery.projectId());
        row.alertType = delivery.alertType().name();
        row.recipientType = delivery.recipientType().name();
        row.recipientId = delivery.recipientId();
        row.status = delivery.status().name();
        row.providerMessageId = delivery.providerMessageId();
        row.failureCode = delivery.failureCode();
        row.failureMessage = delivery.failureMessage();
        row.idempotencyKey = delivery.idempotencyKey();
        row.createdAt = PostgresPersistenceSupport.toDateTime(delivery.createTimeEpochMillis());
        return row;
    }

    private RdAlertDelivery toDelivery(RdAlertDeliveryRow row) {
        return new RdAlertDelivery(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.projectId),
                RdProjectAlertEventType.valueOf(row.alertType),
                RdAlertRecipientType.valueOf(row.recipientType),
                row.recipientId,
                RdAlertDeliveryStatus.valueOf(row.status),
                row.providerMessageId,
                row.failureCode,
                row.failureMessage,
                row.idempotencyKey,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }
}
