package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.scheduling.model.StageScheduleCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairScheduleSelectorTest {

    private final FairScheduleSelector selector = new FairScheduleSelector();

    @Test
    void shouldPreferIdleProjectOverSaturatedProject() {
        FairScheduleLimits limits = new FairScheduleLimits(1, 4, 2, 8, 2, 60_000L);
        List<StageScheduleCandidate> selected = selector.select(
                List.of(
                        candidate("a", "proj-busy", 0, 1_000L, ScheduleResourceClass.GENERIC),
                        candidate("b", "proj-idle", 1, 1_100L, ScheduleResourceClass.GENERIC)
                ),
                Map.of("proj-busy", 1),
                Map.of(),
                limits,
                2_000L
        );

        assertEquals(1, selected.size());
        assertEquals("b", selected.getFirst().commandId());
        assertEquals("proj-idle", selected.getFirst().projectId());
    }

    @Test
    void shouldAgeLowPriorityPastFreshHighPriority() {
        FairScheduleLimits limits = new FairScheduleLimits(4, 4, 2, 8, 1, 60_000L);
        long now = 300_000L;
        List<StageScheduleCandidate> selected = selector.select(
                List.of(
                        candidate("fresh-p0", "p1", 0, now - 1_000L, ScheduleResourceClass.GENERIC),
                        candidate("old-p2", "p1", 2, now - 180_000L, ScheduleResourceClass.GENERIC)
                ),
                Map.of(),
                Map.of(),
                limits,
                now
        );

        // age 180s / 60s = 3.0 aging boost → effective priority -1 beats fresh P0 (0)
        assertEquals("old-p2", selected.getFirst().commandId());
    }

    @Test
    void shouldRespectDockerTokenLimit() {
        FairScheduleLimits limits = new FairScheduleLimits(4, 1, 2, 8, 3, 60_000L);
        List<StageScheduleCandidate> selected = selector.select(
                List.of(
                        candidate("d1", "p1", 0, 1L, ScheduleResourceClass.DOCKER),
                        candidate("d2", "p2", 0, 2L, ScheduleResourceClass.DOCKER),
                        candidate("g1", "p3", 0, 3L, ScheduleResourceClass.GENERIC)
                ),
                Map.of(),
                Map.of(ScheduleResourceClass.DOCKER, 0),
                limits,
                10L
        );

        assertEquals(2, selected.size());
        assertEquals(1, selected.stream().filter(c -> c.resourceClass() == ScheduleResourceClass.DOCKER).count());
        assertTrue(selected.stream().anyMatch(c -> "g1".equals(c.commandId())));
    }

    @Test
    void shouldHonorBatchSize() {
        FairScheduleLimits limits = new FairScheduleLimits(4, 8, 8, 8, 2, 60_000L);
        List<StageScheduleCandidate> selected = selector.select(
                List.of(
                        candidate("1", "p", 0, 1L, ScheduleResourceClass.GENERIC),
                        candidate("2", "p", 0, 2L, ScheduleResourceClass.GENERIC),
                        candidate("3", "p", 0, 3L, ScheduleResourceClass.GENERIC)
                ),
                Map.of(),
                Map.of(),
                limits,
                10L
        );
        assertEquals(2, selected.size());
    }

    private static StageScheduleCandidate candidate(
            String id,
            String projectId,
            int priorityRank,
            long enqueuedAt,
            ScheduleResourceClass resourceClass
    ) {
        return new StageScheduleCandidate(
                id,
                "task-" + id,
                projectId,
                priorityRank,
                enqueuedAt,
                resourceClass,
                1L
        );
    }
}
