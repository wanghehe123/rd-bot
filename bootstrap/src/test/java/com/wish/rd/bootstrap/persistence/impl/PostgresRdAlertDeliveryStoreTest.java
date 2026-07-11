package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.RdAlertDeliveryRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAlertDeliveryMapper;
import com.wish.rd.rag.project.alert.model.RdAlertDelivery;
import com.wish.rd.rag.project.alert.model.RdAlertDeliveryStatus;
import com.wish.rd.rag.project.alert.model.RdAlertRecipientType;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresRdAlertDeliveryStoreTest {

    @Test
    void shouldReserveBeforeDeliveryAndUpdateOutcomeByIdempotencyKey() {
        RdAlertDeliveryMapper mapper = mock(RdAlertDeliveryMapper.class);
        PostgresRdAlertDeliveryStore store = new PostgresRdAlertDeliveryStore(mapper);
        RdAlertDelivery pending = delivery(RdAlertDeliveryStatus.PENDING, "", "DELIVERY_PENDING");

        store.save(pending);

        ArgumentCaptor<RdAlertDeliveryRow> insertCaptor = ArgumentCaptor.forClass(RdAlertDeliveryRow.class);
        verify(mapper).insertIfAbsent(insertCaptor.capture());
        assertEquals("PENDING", insertCaptor.getValue().status);

        RdAlertDelivery sent = delivery(RdAlertDeliveryStatus.SENT, "om_123", "");
        store.updateOutcome(sent);

        ArgumentCaptor<RdAlertDeliveryRow> updateCaptor = ArgumentCaptor.forClass(RdAlertDeliveryRow.class);
        verify(mapper).updateOutcome(updateCaptor.capture());
        assertEquals("SENT", updateCaptor.getValue().status);
        assertEquals("om_123", updateCaptor.getValue().providerMessageId);
        assertEquals("task-1:TASK_COMPLETED:record-1:CHAT_ID:oc_chat", updateCaptor.getValue().idempotencyKey);
    }

    private static RdAlertDelivery delivery(
            RdAlertDeliveryStatus status,
            String messageId,
            String failureCode
    ) {
        return new RdAlertDelivery(
                "7482000000000000501",
                "7482000000000000502",
                "7482000000000000503",
                RdProjectAlertEventType.TASK_COMPLETED,
                RdAlertRecipientType.CHAT_ID,
                "oc_chat",
                status,
                messageId,
                failureCode,
                "",
                "task-1:TASK_COMPLETED:record-1:CHAT_ID:oc_chat",
                1_783_200_000_000L
        );
    }
}
