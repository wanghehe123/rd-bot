package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.RequirementDeliveryJobRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementDeliveryJobMapper;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRequirementDeliveryJobStoreTest {

    @Test
    void shouldMapAtomicClaimResult() {
        RequirementDeliveryJobMapper mapper = mock(RequirementDeliveryJobMapper.class);
        RequirementDeliveryJobRow row = row("RUNNING", 1, "worker-a");
        when(mapper.claim(any(), any(), any(), any())).thenReturn(row);
        PostgresRequirementDeliveryJobStore store = new PostgresRequirementDeliveryJobStore(mapper);

        Optional<RequirementDeliveryJob> claimed = store.claim("201", "worker-a", 1_000L, 500L);

        assertEquals(RequirementDeliveryJobStatus.RUNNING, claimed.orElseThrow().status());
        assertEquals(1, claimed.orElseThrow().attemptNo());
        verify(mapper).claim(any(), any(), any(), any());
    }

    @Test
    void shouldMapListInFlightQuery() {
        RequirementDeliveryJobMapper mapper = mock(RequirementDeliveryJobMapper.class);
        RequirementDeliveryJobRow row = row("RUNNING", 1, "worker-a");
        when(mapper.listInFlight(any())).thenReturn(java.util.List.of(row));
        PostgresRequirementDeliveryJobStore store = new PostgresRequirementDeliveryJobStore(mapper);

        java.util.List<RequirementDeliveryJob> inFlight = store.listInFlight(1_000L);

        assertEquals(1, inFlight.size());
        assertEquals(RequirementDeliveryJobStatus.RUNNING, inFlight.getFirst().status());
        verify(mapper).listInFlight(any());
    }

    private RequirementDeliveryJobRow row(String status, int attemptNo, String owner) {
        RequirementDeliveryJobRow row = new RequirementDeliveryJobRow();
        row.id = 101L;
        row.taskId = 201L;
        row.status = status;
        row.attemptNo = attemptNo;
        row.maxAttempts = 3;
        row.leaseOwner = owner;
        row.leaseUntil = OffsetDateTime.now().plusMinutes(1);
        row.errorMessage = "";
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = row.createdAt;
        return row;
    }
}
