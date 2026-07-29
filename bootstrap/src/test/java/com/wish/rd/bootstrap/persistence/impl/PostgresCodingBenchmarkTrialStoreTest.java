package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialEventRow;
import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialRow;
import com.wish.rd.bootstrap.persistence.mapper.CodingBenchmarkTrialEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.CodingBenchmarkTrialMapper;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresCodingBenchmarkTrialStoreTest {

    @Test
    void shouldClaimExactlyOneQueuedTrialWithALeaseAndAppendEvents() {
        CodingBenchmarkTrialMapper trialMapper = mock(CodingBenchmarkTrialMapper.class);
        CodingBenchmarkTrialEventMapper eventMapper = mock(CodingBenchmarkTrialEventMapper.class);
        PostgresCodingBenchmarkTrialStore store = store(trialMapper, eventMapper);
        store.createAll("100", List.of(
                queued("trial-1", "case-01", CodingBenchmarkArm.A),
                queued("trial-2", "case-01", CodingBenchmarkArm.B)
        ));
        when(trialMapper.claimNext(eq(100L), eq("worker-a"), any(), any())).thenReturn(
                row("trial-1", CodingBenchmarkTrialStatus.PREPARING, 1L, "QUEUED"));
        when(trialMapper.claimNext(eq(100L), eq("worker-b"), any(), any())).thenReturn(
                row("trial-2", CodingBenchmarkTrialStatus.PREPARING, 1L, "QUEUED"));

        Optional<CodingBenchmarkTrial> first = store.claimNext("100", "worker-a", 1_000L, 2_000L);
        Optional<CodingBenchmarkTrial> second = store.claimNext("100", "worker-b", 1_001L, 2_000L);

        assertEquals(true, first.isPresent());
        assertEquals(true, second.isPresent());
        assertNotEquals(first.orElseThrow().trialId(), second.orElseThrow().trialId());
        assertEquals(CodingBenchmarkTrialStatus.PREPARING, first.orElseThrow().status());
        ArgumentCaptor<CodingBenchmarkTrialEventRow> eventCaptor = ArgumentCaptor.forClass(CodingBenchmarkTrialEventRow.class);
        verify(eventMapper, org.mockito.Mockito.times(4)).insertEvent(eventCaptor.capture());
        assertEquals("QUEUED", eventCaptor.getAllValues().get(0).toStatus);
        assertEquals("PREPARING", eventCaptor.getAllValues().get(2).toStatus);
        assertEquals("QUEUED", eventCaptor.getAllValues().get(2).fromStatus);
    }

    @Test
    void shouldRejectStaleCasTransitionWithoutAppendingAnEvent() {
        CodingBenchmarkTrialMapper trialMapper = mock(CodingBenchmarkTrialMapper.class);
        CodingBenchmarkTrialEventMapper eventMapper = mock(CodingBenchmarkTrialEventMapper.class);
        PostgresCodingBenchmarkTrialStore store = store(trialMapper, eventMapper);
        when(trialMapper.transition(eq("trial-1"), eq("PREPARING"), eq(4L), eq("RUNNING_AGENTS"),
                eq("PENDING"), eq(""), eq(""), any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> store.transition(
                "trial-1", CodingBenchmarkTrialStatus.PREPARING, 4L,
                CodingBenchmarkTrialStatus.RUNNING_AGENTS, CodingBenchmarkVerdict.PENDING,
                "", "", 3_000L
        ));

        verify(eventMapper, never()).insertEvent(any());
    }

    @Test
    void shouldOnlyAllowInfrastructureVerdictsToEnterTheSingleRetryPath() {
        CodingBenchmarkTrialMapper trialMapper = mock(CodingBenchmarkTrialMapper.class);
        CodingBenchmarkTrialEventMapper eventMapper = mock(CodingBenchmarkTrialEventMapper.class);
        PostgresCodingBenchmarkTrialStore store = store(trialMapper, eventMapper);

        assertThrows(IllegalStateException.class, () -> store.transition(
                "trial-1", CodingBenchmarkTrialStatus.RUNNING_AGENTS, 1L,
                CodingBenchmarkTrialStatus.RETRY_PENDING, CodingBenchmarkVerdict.TIMEOUT,
                "", "", 3_000L
        ));

        verify(trialMapper, never()).transition(any(), any(), any(), any(), any(), any(), any(), any());
        verify(eventMapper, never()).insertEvent(any());
    }

    private static PostgresCodingBenchmarkTrialStore store(
            CodingBenchmarkTrialMapper trialMapper,
            CodingBenchmarkTrialEventMapper eventMapper
    ) {
        SnowflakeIdGenerator generator = mock(SnowflakeIdGenerator.class);
        when(generator.nextIdString()).thenReturn("9001", "9002", "9003", "9004", "9005", "9006");
        return new PostgresCodingBenchmarkTrialStore(trialMapper, eventMapper, generator);
    }

    private static CodingBenchmarkTrial queued(String trialId, String caseId, CodingBenchmarkArm arm) {
        return CodingBenchmarkTrial.queued(trialId, "100", caseId, arm, 0, 100L);
    }

    private static CodingBenchmarkTrialRow row(
            String trialId,
            CodingBenchmarkTrialStatus status,
            long version,
            String previousStatus
    ) {
        CodingBenchmarkTrialRow row = new CodingBenchmarkTrialRow();
        row.id = trialId;
        row.campaignId = 100L;
        row.caseId = "case-01";
        row.arm = CodingBenchmarkArm.A.name();
        row.replicateNo = 0;
        row.status = status.name();
        row.verdict = CodingBenchmarkVerdict.PENDING.name();
        row.attemptNo = 1;
        row.version = version;
        row.leaseOwner = "worker";
        row.leaseExpiresAt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(3_000L), ZoneOffset.UTC);
        row.errorCategory = "";
        row.errorMessage = "";
        row.createdAt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(100L), ZoneOffset.UTC);
        row.updatedAt = row.createdAt;
        row.previousStatus = previousStatus;
        return row;
    }
}
