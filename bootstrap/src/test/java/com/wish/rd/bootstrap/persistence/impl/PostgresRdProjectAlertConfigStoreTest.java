package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RdProjectAlertConfigRow;
import com.wish.rd.bootstrap.persistence.mapper.RdProjectAlertConfigMapper;
import com.wish.rd.rag.project.alert.model.RdAlertRecipient;
import com.wish.rd.rag.project.alert.model.RdAlertRecipientType;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRdProjectAlertConfigStoreTest {

    @Test
    void shouldRoundTripJsonRecipientsAndEvents() {
        RdProjectAlertConfigMapper mapper = mock(RdProjectAlertConfigMapper.class);
        PostgresRdProjectAlertConfigStore store = new PostgresRdProjectAlertConfigStore(mapper, new ObjectMapper());
        RdProjectAlertConfig config = new RdProjectAlertConfig(
                "7482000000000000201",
                true,
                List.of(new RdAlertRecipient(RdAlertRecipientType.OPEN_ID, "ou_user")),
                Set.of(RdProjectAlertEventType.TASK_COMPLETED, RdProjectAlertEventType.QA_FAILED),
                new BigDecimal("54.0000"),
                3,
                1_783_200_000_000L,
                1_783_200_001_000L
        );

        store.save(config);
        verify(mapper).upsert(any(RdProjectAlertConfigRow.class));

        RdProjectAlertConfigRow row = new RdProjectAlertConfigRow();
        row.projectId = 7_482_000_000_000_000_201L;
        row.enabled = true;
        row.recipientsJson = "[{\"type\":\"OPEN_ID\",\"value\":\"ou_user\"}]";
        row.eventTypesJson = "[\"TASK_COMPLETED\",\"QA_FAILED\"]";
        row.budgetThresholdCny = new BigDecimal("54.0000");
        row.failureThreshold = 3;
        row.createdAt = OffsetDateTime.parse("2026-07-10T02:00:00Z");
        row.updatedAt = OffsetDateTime.parse("2026-07-10T02:01:00Z");
        when(mapper.findByProjectId(row.projectId)).thenReturn(row);

        RdProjectAlertConfig loaded = store.findByProjectId("7482000000000000201").orElseThrow();
        assertEquals(config.recipients(), loaded.recipients());
        assertEquals(config.eventTypes(), loaded.eventTypes());
        assertEquals(new BigDecimal("54.0000"), loaded.budgetThresholdCny());
    }
}
