package com.wish.rd.rag.retrieval.run;

import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetrievalRunTransitionPolicyTest {

    @Test
    void allowsTheEvidenceRefinementLoopAndTerminalPackaging() {
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.CREATED, RetrievalRunStatus.PLANNING));
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.PLANNING, RetrievalRunStatus.RETRIEVING));
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.RETRIEVING, RetrievalRunStatus.EVALUATING));
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.EVALUATING, RetrievalRunStatus.PLANNING));
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.EVALUATING, RetrievalRunStatus.PACKAGING));
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.PACKAGING, RetrievalRunStatus.SUCCEEDED));
    }

    @Test
    void rejectsReopeningATerminalRunInPlace() {
        assertThrows(IllegalStateException.class, () -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.WAITING_INPUT, RetrievalRunStatus.PLANNING));
        assertThrows(IllegalStateException.class, () -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.SUCCEEDED, RetrievalRunStatus.RECOVERING));
    }

    @Test
    void allowsLeaseRecoveryOnlyFromActiveStates() {
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.RETRIEVING, RetrievalRunStatus.RECOVERING));
        assertDoesNotThrow(() -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.RECOVERING, RetrievalRunStatus.PLANNING));
        assertThrows(IllegalStateException.class, () -> RetrievalRunTransitionPolicy.ensureTransition(
                RetrievalRunStatus.FAILED_RETRYABLE, RetrievalRunStatus.RECOVERING));
    }
}
