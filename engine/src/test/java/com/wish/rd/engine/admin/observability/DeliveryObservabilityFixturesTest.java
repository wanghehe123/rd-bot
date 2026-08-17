package com.wish.rd.engine.admin.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryObservabilityFixturesTest {

    @Test
    void catalogCoversRequiredObservationShapes() {
        DeliveryObservabilityFixtures.Catalog catalog = DeliveryObservabilityFixtures.catalog();
        assertEquals(6, catalog.tasks().size());
        assertTrue(catalog.tasks().stream().anyMatch(task -> "COMPLETED".equals(task.status())
                && !task.pullRequestUrl().isBlank()));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "EXECUTING".equals(task.status())
                && task.terminalAt() == null));
        assertTrue(catalog.tasks().stream().anyMatch(task -> task.stageRuns().stream()
                .anyMatch(stage -> stage.attemptNo() > 1)));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "FAILED_NEEDS_HUMAN".equals(task.status())
                && task.errorCategory().isBlank()));
        assertTrue(catalog.tasks().stream().anyMatch(task -> task.stageRuns().stream()
                .anyMatch(stage -> stage.finishedAt() != null && stage.finishedAt().isBefore(stage.startedAt()))));
        assertTrue(catalog.tasks().stream().anyMatch(task -> task.stageRuns().stream()
                .anyMatch(stage -> stage.providerAttempts().stream()
                        .anyMatch(attempt -> !attempt.usageAvailable()))));
    }

    @Test
    void hostFinalizerDurationsAreZeroAndMustNotBeTrusted() {
        DeliveryObservabilityFixtures.TaskFixture success = DeliveryObservabilityFixtures.successTask();
        assertTrue(success.statusEvents().stream().allMatch(event -> event.durationMs() == 0L));
        assertTrue(success.statusEvents().get(2).enteredAt()
                .isAfter(success.statusEvents().get(1).enteredAt()));
    }

    @Test
    void emptyWindowHasNoTerminalSamples() {
        DeliveryObservabilityFixtures.Catalog empty = DeliveryObservabilityFixtures.emptyWindow();
        assertTrue(empty.tasks().isEmpty());
        assertFalse(DeliveryObservabilityFixtures.runningTask().terminal());
        assertNull(DeliveryObservabilityFixtures.runningTask().terminalAt());
    }
}
