package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryMetricsTest {

    @Test
    void snapshotTracksQueueAgeRetriesLeaseLossAndResourceClaims() {
        RequirementDeliveryMetrics metrics = new RequirementDeliveryMetrics();
        RequirementStageCommand command = RequirementStageCommand.pending(
                "metric-command", "metric-task", 1L, 1L,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3, 0L,
                ScheduleResourceClass.DOCKER, "project-a", "", "P1", 100L);

        metrics.recordEnqueued(command, 1_100L);
        RequirementStageCommand claimed = command.claimed("worker", 1_300L, 1_200L);
        metrics.recordClaimed(claimed, 1_200L);
        metrics.recordRetry();
        metrics.recordLeaseLost();
        metrics.recordQueueRejected();
        metrics.recordCompleted(claimed, 2_300L);
        metrics.recordInFlight(
                java.util.Map.of(ScheduleResourceClass.DOCKER, 1),
                new FairScheduleLimits(2, 2, 2, 3, 10, 500L));

        RequirementDeliveryMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.enqueued());
        assertEquals(1L, snapshot.claimed());
        assertEquals(1L, snapshot.completed());
        assertEquals(1L, snapshot.retries());
        assertEquals(1L, snapshot.leaseLost());
        assertEquals(1L, snapshot.queueRejections());
        assertEquals(1L, snapshot.resourceClaims().get(ScheduleResourceClass.DOCKER));
        assertEquals(1_000L, snapshot.queueAgeP50Millis());
        assertTrue(snapshot.oldestQueueAgeMillis() >= snapshot.queueAgeP50Millis());
        assertTrue(snapshot.projectWaitRatio() > 0D && snapshot.projectWaitRatio() < 1D);
        assertEquals(0.5D, snapshot.resourceUtilization().get(ScheduleResourceClass.DOCKER));
        assertEquals(1, snapshot.currentInFlightByResource().get(ScheduleResourceClass.DOCKER));
        assertEquals(2, snapshot.currentCapacityByResource().get(ScheduleResourceClass.DOCKER));
        assertFalse(snapshot.processWindowWarmUp());
        assertEquals(2L, snapshot.queueWaitSampleCount());
        assertEquals(RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length + 1,
                snapshot.queueWaitBuckets().size());
    }

    @Test
    void recordsEveryResourceConsumedByACombinedStageCommand() {
        RequirementDeliveryMetrics metrics = new RequirementDeliveryMetrics();
        RequirementStageCommand command = RequirementStageCommand.pending(
                "combined-metric-command", "metric-task", 1L, 1L,
                "QA_AGENT", "ROLE_EXECUTION:QA_AGENT", 0, 3, 60_000L,
                ScheduleResourceClass.BROWSER_QA,
                Set.of(
                        ScheduleResourceClass.PROVIDER,
                        ScheduleResourceClass.DOCKER,
                        ScheduleResourceClass.BROWSER_QA),
                "project-a", "provider-a", "P1", 100L);

        metrics.recordClaimed(command.claimed("worker", 10_000L, 200L), 200L);

        RequirementDeliveryMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.resourceClaims().get(ScheduleResourceClass.PROVIDER));
        assertEquals(1L, snapshot.resourceClaims().get(ScheduleResourceClass.DOCKER));
        assertEquals(1L, snapshot.resourceClaims().get(ScheduleResourceClass.BROWSER_QA));
    }

    /**
     * Frozen WP-2 replacement: process histograms keep a fixed bucket set and must not
     * retain per-project maps. Seconds: 0.05 .. 3600 plus +Inf.
     */
    static final double[] DURATION_BUCKET_SECONDS = {
            0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, 600, 1800, 3600
    };
    static final int MAX_QUEUE_DURATION_BUCKETS = DURATION_BUCKET_SECONDS.length + 1;
    static final int MAX_PROCESS_PROJECT_DIMENSIONS = 0;

    @Test
    void longLivedSamplingMustNotRetainAnUnboundedQueueAgeList() {
        RequirementDeliveryMetrics metrics = new RequirementDeliveryMetrics();
        for (int i = 0; i < 10_000; i++) {
            RequirementStageCommand command = RequirementStageCommand.pending(
                    "queue-" + i, "task-" + i, 1L, 1L,
                    "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3, 0L,
                    ScheduleResourceClass.DOCKER, "project-a", "", "P1", i * 10L);
            metrics.recordEnqueued(command, i * 10L + 1_000L);
        }

        assertEquals(MAX_QUEUE_DURATION_BUCKETS, metrics.queueWaitBucketCount());
        RequirementDeliveryMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(MAX_QUEUE_DURATION_BUCKETS, snapshot.queueWaitBuckets().size());
        assertEquals(10_000L, snapshot.queueWaitSampleCount());
    }

    @Test
    void highProjectCardinalityMustNotGrowAProcessClaimMap() {
        RequirementDeliveryMetrics metrics = new RequirementDeliveryMetrics();
        for (int i = 0; i < 5_000; i++) {
            RequirementStageCommand command = RequirementStageCommand.pending(
                    "claim-" + i, "task-" + i, 1L, 1L,
                    "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3, 0L,
                    ScheduleResourceClass.GENERIC, "project-" + i, "", "P1", 100L);
            metrics.recordClaimed(command.claimed("worker", 10_000L, 200L), 200L);
        }

        RequirementDeliveryMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(MAX_PROCESS_PROJECT_DIMENSIONS, snapshot.projectClaims().size(),
                "process metrics must not retain per-project dimensions");
        assertEquals(5_000L, snapshot.claimed());
    }

    @Test
    void snapshotExposesProcessStartWarmUpAndCurrentCapacity() {
        RequirementDeliveryMetrics metrics = new RequirementDeliveryMetrics(1_700_000_000_000L);
        RequirementDeliveryMetrics.Snapshot empty = metrics.snapshot();
        assertEquals(1_700_000_000_000L, empty.processStartedAtEpochMillis());
        assertTrue(empty.processWindowWarmUp());
        assertEquals(0L, empty.queueWaitSampleCount());
        assertEquals(0L, empty.queueAgeP50Millis());

        RequirementStageCommand command = RequirementStageCommand.pending(
                "warm", "task-warm", 1L, 1L,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3, 0L,
                ScheduleResourceClass.DOCKER, "project-a", "", "P1", 0L);
        metrics.recordEnqueued(command, 2_000L);
        metrics.recordInFlight(
                Map.of(ScheduleResourceClass.DOCKER, 2, ScheduleResourceClass.PROVIDER, 1),
                new FairScheduleLimits(2, 4, 2, 8, 10, 500L));

        RequirementDeliveryMetrics.Snapshot snapshot = metrics.snapshot();
        assertFalse(snapshot.processWindowWarmUp());
        assertEquals(1L, snapshot.enqueued());
        assertEquals(2, snapshot.currentInFlightByResource().get(ScheduleResourceClass.DOCKER));
        assertEquals(4, snapshot.currentCapacityByResource().get(ScheduleResourceClass.DOCKER));
        assertEquals(3L, snapshot.inFlightSamples());
        assertTrue(snapshot.queueWaitBuckets().get(2.5D) >= 1L);
    }
}
