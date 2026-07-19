package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationTransitionPolicyTest {

    private final EvaluationTransitionPolicy policy = new EvaluationTransitionPolicy();

    @Test
    void shouldAllowCompleteEvaluationLifecycle() {
        assertTrue(policy.canTransition(EvaluationRunStatus.CREATED, EvaluationRunStatus.QUEUED));
        assertTrue(policy.canTransition(EvaluationRunStatus.QUEUED, EvaluationRunStatus.RECORDING));
        assertTrue(policy.canTransition(EvaluationRunStatus.RECORDING, EvaluationRunStatus.SCORING));
        assertTrue(policy.canTransition(EvaluationRunStatus.SCORING, EvaluationRunStatus.REPORTING));
        assertTrue(policy.canTransition(EvaluationRunStatus.REPORTING, EvaluationRunStatus.DIFFING));
        assertTrue(policy.canTransition(EvaluationRunStatus.DIFFING, EvaluationRunStatus.SUCCEEDED));
        assertTrue(policy.canTransition(EvaluationRunStatus.REPORTING, EvaluationRunStatus.SUCCEEDED));
    }

    @Test
    void shouldRejectTerminalMutationAndInvalidPhaseJump() {
        assertFalse(policy.canTransition(EvaluationRunStatus.SUCCEEDED, EvaluationRunStatus.QUEUED));
        assertFalse(policy.canTransition(EvaluationRunStatus.FAILED, EvaluationRunStatus.RECORDING));
        assertFalse(policy.canTransition(EvaluationRunStatus.CANCELLED, EvaluationRunStatus.CREATED));
        assertFalse(policy.canTransition(EvaluationRunStatus.CREATED, EvaluationRunStatus.SCORING));
    }

    @Test
    void shouldAllowCancellationFromEveryActiveExecutionPhase() {
        assertTrue(policy.canTransition(EvaluationRunStatus.QUEUED, EvaluationRunStatus.CANCEL_REQUESTED));
        assertTrue(policy.canTransition(EvaluationRunStatus.RECORDING, EvaluationRunStatus.CANCEL_REQUESTED));
        assertTrue(policy.canTransition(EvaluationRunStatus.SCORING, EvaluationRunStatus.CANCEL_REQUESTED));
        assertTrue(policy.canTransition(EvaluationRunStatus.REPORTING, EvaluationRunStatus.CANCEL_REQUESTED));
        assertTrue(policy.canTransition(EvaluationRunStatus.DIFFING, EvaluationRunStatus.CANCEL_REQUESTED));
        assertTrue(policy.canTransition(EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.CANCELLED));
    }
}
