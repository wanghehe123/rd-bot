package com.wish.rd.rag.project.alert;

import com.wish.rd.rag.project.alert.model.RdAlertDelivery;

import java.util.List;
import java.util.Optional;

/** Persistence port for project alert delivery audit records. */
public interface RdAlertDeliveryStore {

    RdAlertDelivery save(RdAlertDelivery delivery);

    RdAlertDelivery updateOutcome(RdAlertDelivery delivery);

    Optional<RdAlertDelivery> findByIdempotencyKey(String idempotencyKey);

    List<RdAlertDelivery> listByTask(String taskId);
}
