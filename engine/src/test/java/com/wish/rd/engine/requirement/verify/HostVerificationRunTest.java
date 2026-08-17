package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Constructor and terminal-state contract for {@link HostVerificationRun}. */
class HostVerificationRunTest {

    @Test
    void blankRunIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sample(" ", "task-1", "coding-1", "", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> sample(null, "task-1", "coding-1", "", 1, 0));
    }

    @Test
    void blankTaskIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sample("run-1", " ", "coding-1", "", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> sample("run-1", null, "coding-1", "", 1, 0));
    }

    @Test
    void blankCodingStageRunIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sample("run-1", "task-1", " ", "", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> sample("run-1", "task-1", null, "", 1, 0));
    }

    @Test
    void attemptNoBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sample("run-1", "task-1", "coding-1", "", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> sample("run-1", "task-1", "coding-1", "", -1, 0));
    }

    @Test
    void negativeRemediationCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sample("run-1", "task-1", "coding-1", "", 1, -1));
    }

    @Test
    void parentRunIdMayBeBlank() {
        HostVerificationRun root = sample("run-1", "task-1", "coding-1", "", 1, 0);
        assertEquals("", root.parentRunId());
        HostVerificationRun nullParent = sample("run-1", "task-1", "coding-1", null, 1, 0);
        assertEquals("", nullParent.parentRunId());
    }

    @Test
    void nullStatusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HostVerificationRun(
                "run-1",
                "task-1",
                "coding-1",
                "",
                1,
                null,
                false,
                "",
                "",
                0,
                1L,
                0L,
                0L));
    }

    @Test
    void optionalStringsAreNormalized() {
        HostVerificationRun run = new HostVerificationRun(
                " run-1 ",
                " task-1 ",
                " coding-1 ",
                "  parent-1  ",
                1,
                HostVerificationStatus.CREATED,
                false,
                "  PRODUCT_DEFECT  ",
                "  boom  ",
                0,
                1L,
                2L,
                3L);
        assertEquals("run-1", run.runId());
        assertEquals("task-1", run.taskId());
        assertEquals("coding-1", run.codingStageRunId());
        assertEquals("parent-1", run.parentRunId());
        assertEquals("PRODUCT_DEFECT", run.failureCategory());
        assertEquals("boom", run.errorMessage());
    }

    @Test
    void leavingCreatedSetsStartedAtAndEnteringTerminalSetsFinishedAt() {
        HostVerificationRun created = sample("run-1", "task-1", "coding-1", "", 1, 0);
        HostVerificationRun preparing = created.withStatus(HostVerificationStatus.PREPARING, "", "", 10L);
        assertEquals(HostVerificationStatus.PREPARING, preparing.status());
        assertEquals(10L, preparing.startedAtEpochMillis());
        assertEquals(0L, preparing.finishedAtEpochMillis());
        HostVerificationRun cancelled = created.withStatus(HostVerificationStatus.CANCELLED, "ENVIRONMENT", "stop", 11L);
        assertEquals(11L, cancelled.startedAtEpochMillis());
        assertEquals(11L, cancelled.finishedAtEpochMillis());
        assertEquals("ENVIRONMENT", cancelled.failureCategory());
        assertEquals("stop", cancelled.errorMessage());
    }

    @Test
    void allowedGraphMatchesStoreContract() {
        assertTrue(HostVerificationStatus.CREATED.canTransitionTo(HostVerificationStatus.PREPARING));
        assertTrue(HostVerificationStatus.CREATED.canTransitionTo(HostVerificationStatus.CANCELLED));
        assertTrue(HostVerificationStatus.PREPARING.canTransitionTo(HostVerificationStatus.SKIPPED_DOCS_ONLY));
        assertTrue(HostVerificationStatus.STATIC_CHECKING.canTransitionTo(HostVerificationStatus.SUCCEEDED));
        assertFalse(HostVerificationStatus.CREATED.canTransitionTo(HostVerificationStatus.SUCCEEDED));
        assertFalse(HostVerificationStatus.SUCCEEDED.canTransitionTo(HostVerificationStatus.CANCELLED));
    }

    @Test
    void isTerminalMatchesContract() {
        for (HostVerificationStatus status : HostVerificationStatus.values()) {
            boolean expected = status == HostVerificationStatus.SUCCEEDED
                    || status == HostVerificationStatus.FAILED_RETRYABLE
                    || status == HostVerificationStatus.FAILED_NEEDS_HUMAN
                    || status == HostVerificationStatus.SKIPPED_DOCS_ONLY
                    || status == HostVerificationStatus.CANCELLED;
            assertEquals(expected, status.isTerminal(), status.name());
            HostVerificationRun run = new HostVerificationRun(
                    "run-1",
                    "task-1",
                    "coding-1",
                    "",
                    1,
                    status,
                    false,
                    "",
                    "",
                    0,
                    1L,
                    0L,
                    0L);
            assertEquals(expected, run.status().isTerminal(), status.name());
        }
    }

    private static HostVerificationRun sample(
            String runId,
            String taskId,
            String codingStageRunId,
            String parentRunId,
            int attemptNo,
            int remediationCount
    ) {
        return new HostVerificationRun(
                runId,
                taskId,
                codingStageRunId,
                parentRunId,
                attemptNo,
                HostVerificationStatus.CREATED,
                false,
                "",
                "",
                remediationCount,
                1L,
                0L,
                0L);
    }
}
