package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
