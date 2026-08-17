package com.wish.rd.bootstrap.observability;

import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper.DeliveryObservabilityEventRow;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper.DeliveryObservabilityStageRow;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper.DeliveryObservabilityTaskRow;
import com.wish.rd.engine.admin.observability.DeliveryObservabilityQueryService;
import com.wish.rd.engine.admin.observability.DeliveryPhaseDurationCalculator;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresDeliveryObservabilitySnapshotAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    void phaseDurationsUseAdjacentEnteredAtAndIgnoreZeroDurationMs() {
        DeliveryObservabilityMapper mapper = mock(DeliveryObservabilityMapper.class);
        DeliveryObservabilityTaskRow task = new DeliveryObservabilityTaskRow();
        task.taskId = "1001";
        task.projectId = "project-a";
        task.title = "success";
        task.status = "COMPLETED";
        task.createdAt = Instant.parse("2026-08-14T01:00:00Z");
        task.updatedAt = Instant.parse("2026-08-14T02:10:00Z");
        task.pullRequestUrl = "https://github.example/pr/1";

        DeliveryObservabilityEventRow created = event("1001", "CREATED", "2026-08-14T01:00:00Z", 0L);
        DeliveryObservabilityEventRow materialReady = event("1001", "MATERIAL_READY", "2026-08-14T01:05:00Z", 0L);
        DeliveryObservabilityEventRow contextReady = event("1001", "CONTEXT_READY", "2026-08-14T01:08:00Z", 0L);
        DeliveryObservabilityEventRow completed = event("1001", "COMPLETED", "2026-08-14T02:10:00Z", 0L);

        DeliveryObservabilityStageRow coding = new DeliveryObservabilityStageRow();
        coding.stageRunId = "coding-1";
        coding.taskId = "1001";
        coding.role = "CODING_AGENT";
        coding.status = "SUCCEEDED";
        coding.attemptNo = 1;
        coding.startedAt = Instant.parse("2026-08-14T01:20:00Z");
        coding.finishedAt = Instant.parse("2026-08-14T01:50:00Z");
        coding.errorCategory = "";

        when(mapper.listTasks(isNull(), any(), any())).thenReturn(List.of(task));
        when(mapper.listStatusEvents(isNull(), any(), any()))
                .thenReturn(List.of(created, materialReady, contextReady, completed));
        when(mapper.listStages(isNull(), any(), any())).thenReturn(List.of(coding));
        when(mapper.listWaitingCommands(isNull())).thenReturn(List.of());

        PostgresDeliveryObservabilitySnapshotAdapter adapter =
                new PostgresDeliveryObservabilitySnapshotAdapter(mapper, mock(ObjectProvider.class));
        DeliveryObservabilityQuery query = DeliveryObservabilityQuery.overview("", "24h", NOW);
        DeliveryLedgerSnapshot ledger = adapter.loadLedger(query);

        assertTrue(ledger.available());
        assertEquals(0L, ledger.tasks().getFirst().statusEvents().get(1).durationMs());
        DeliveryPhaseDurationCalculator.PhaseSplit split =
                new DeliveryPhaseDurationCalculator().split(ledger.tasks().getFirst().statusEvents());
        assertEquals(180_000L, split.phaseDurationsMillis().get("context"));

        DeliveryObservabilityOverview overview = new DeliveryObservabilityQueryService(
                adapter, DeliveryObservabilitySettings.defaults()).overview(query, NOW);
        assertEquals(180.0D, overview.phaseLatency().get("context").p50Seconds(), 0.001D);
        assertTrue(overview.endToEndLatency().sampleCount() >= 1L);
    }

    @Test
    void mapperFailureReturnsUnavailableSnapshotInsteadOfZeros() {
        DeliveryObservabilityMapper mapper = mock(DeliveryObservabilityMapper.class);
        when(mapper.listTasks(any(), any(), any())).thenThrow(new IllegalStateException("sql down"));
        PostgresDeliveryObservabilitySnapshotAdapter adapter =
                new PostgresDeliveryObservabilitySnapshotAdapter(mapper, mock(ObjectProvider.class));

        DeliveryLedgerSnapshot ledger = adapter.loadLedger(
                DeliveryObservabilityQuery.overview("project-a", "24h", NOW));
        assertFalse(ledger.available());
        assertTrue(ledger.tasks().isEmpty());
    }

    @Test
    void schedulerSnapshotFailureReturnsNullInsteadOfChangingLedgerTruth() {
        DeliveryObservabilityMapper mapper = mock(DeliveryObservabilityMapper.class);
        when(mapper.listTasks(isNull(), any(), any())).thenReturn(List.of());
        when(mapper.listStatusEvents(isNull(), any(), any())).thenReturn(List.of());
        when(mapper.listStages(isNull(), any(), any())).thenReturn(List.of());
        when(mapper.listWaitingCommands(isNull())).thenReturn(List.of());
        RequirementDeliveryDispatchService dispatcher = mock(RequirementDeliveryDispatchService.class);
        when(dispatcher.metricsSnapshot()).thenThrow(new IllegalStateException("metrics boom"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RequirementDeliveryDispatchService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatcher);

        PostgresDeliveryObservabilitySnapshotAdapter adapter =
                new PostgresDeliveryObservabilitySnapshotAdapter(mapper, provider);

        assertTrue(adapter.loadLedger(DeliveryObservabilityQuery.overview("", "24h", NOW)).available());
        org.junit.jupiter.api.Assertions.assertNull(adapter.loadScheduler());
    }

    private static DeliveryObservabilityEventRow event(
            String taskId, String status, String enteredAt, long durationMs
    ) {
        DeliveryObservabilityEventRow row = new DeliveryObservabilityEventRow();
        row.taskId = taskId;
        row.status = status;
        row.enteredAt = Instant.parse(enteredAt);
        row.durationMs = durationMs;
        return row;
    }
}
