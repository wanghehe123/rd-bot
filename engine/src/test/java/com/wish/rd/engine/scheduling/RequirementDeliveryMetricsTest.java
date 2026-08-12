package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
