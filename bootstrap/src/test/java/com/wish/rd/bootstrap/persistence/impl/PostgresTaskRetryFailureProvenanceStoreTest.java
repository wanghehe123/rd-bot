package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.TaskFailureProvenanceRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskFailureProvenanceMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageFinalizationMapper;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresTaskRetryFailureProvenanceStoreTest {

    @Test
    void corroborationAcceptsBarePublicationCommandStageAndPublicationLedgerExists()
            throws NoSuchMethodException {
        Select select = RequirementStageFinalizationMapper.class
                .getMethod("countCorroboratedFailureProvenance", TaskFailureProvenanceRow.class)
                .getAnnotation(Select.class);
        String sql = select.value()[0];

        assertTrue(sql.contains("command.stage = 'PUBLICATION'"));
        assertTrue(sql.contains("command.stage = 'PUBLICATION:' || #{row.publicationOperationId}"));
        assertTrue(sql.contains("rd_requirement_publications"));
        assertTrue(sql.contains("publication.operation_id = #{row.publicationOperationId}"));
        assertTrue(sql.contains("publication.task_id = command.task_id"));
    }

    @Test
    void corroborationQueryTypesEveryNullableFailureIdentityCheck() throws NoSuchMethodException {
        Select select = RequirementStageFinalizationMapper.class
                .getMethod("countCorroboratedFailureProvenance", TaskFailureProvenanceRow.class)
                .getAnnotation(Select.class);
        String sql = select.value()[0];

        assertTrue(sql.contains("#{row.failedRetrievalRunId,jdbcType=BIGINT} IS NOT NULL"));
        assertTrue(sql.contains("#{row.failedStageRunId,jdbcType=BIGINT} IS NOT NULL"));
        assertTrue(sql.contains("#{row.sourcePolicyRunId,jdbcType=BIGINT} IS NULL"));
        assertTrue(sql.contains("#{row.sourcePolicyRunId,jdbcType=BIGINT} IS NOT NULL"));
        assertEquals(2, occurrences(sql, "#{row.failedStageRunId,jdbcType=BIGINT} IS NULL"));
        assertTrue(sql.contains("#{row.failedRetrievalRunId,jdbcType=BIGINT} IS NULL"));
        assertTrue(sql.contains("#{row.failedAiReviewRunId,jdbcType=BIGINT} IS NULL"));
    }

    /**
     * Production strand: DEAD_LETTERED retry command exhausted at attempt 3, but dispatch reused the
     * attempt-1 PREPARED marker via matchesCommandIdentity; provenance also carries checkpoint policy
     * while the pre-fix command row still has null policy_run_id. Corroboration must accept that
     * shape or findExact returns empty and TaskFailureRecoveryService throws RETRY_POINT_AMBIGUOUS.
     */
    @Test
    void corroborationAcceptsReusedPreparedMarkerAndCheckpointPolicyWhenCommandPolicyIsBlank()
            throws NoSuchMethodException {
        Select select = RequirementStageFinalizationMapper.class
                .getMethod("countCorroboratedFailureProvenance", TaskFailureProvenanceRow.class)
                .getAnnotation(Select.class);
        String sql = select.value()[0];

        assertTrue(sql.contains("marker.attempt_no <= #{row.failedCommandAttemptNo}"),
                "DEAD_LETTERED technical exhaustion reuses an earlier PREPARED marker; "
                        + "exact attempt equality rejects the live backfilled snapshot");
        assertTrue(sql.contains("command.policy_run_id IS NULL"),
                "checkpoint-bound exhaustion may leave command.policy_run_id blank while provenance "
                        + "carries checkpoint.source_policy_run_id");
        assertTrue(sql.contains("rd_task_retry_checkpoints"),
                "blank command.policy_run_id must be corroborated via the bound checkpoint policy");
        assertTrue(sql.contains("checkpoint.source_policy_run_id = #{row.sourcePolicyRunId}"));
    }

    @Test
    void corroborationAcceptsReusedFinalizedMarkerOnDeadLetteredPublicationCommand()
            throws NoSuchMethodException {
        Select select = RequirementStageFinalizationMapper.class
                .getMethod("countCorroboratedFailureProvenance", TaskFailureProvenanceRow.class)
                .getAnnotation(Select.class);
        String sql = select.value()[0];

        assertTrue(sql.contains("command.status = 'DEAD_LETTERED'"),
                "last-attempt publication finalize leaves the command DEAD_LETTERED");
        assertTrue(
                sql.contains("marker.state = 'FINALIZED'")
                        && sql.contains("marker.attempt_no <= #{row.failedCommandAttemptNo}")
                        && sql.contains("marker.outcome_status = #{row.outcomeStatus}"),
                "dispatch reuses the attempt-1 marker and last-attempt finalize stamps it FINALIZED; "
                        + "exact attempt equality plus SUCCEEDED-only FINALIZED would 409 the live snapshot");
    }

    @Test
    void readsOnlyTheProvenanceForTheExactDurableFailedTaskSnapshot() {
        TaskFailureProvenanceMapper mapper = mock(TaskFailureProvenanceMapper.class);
        TaskFailureProvenanceRow row = new TaskFailureProvenanceRow();
        row.id = 901L;
        row.taskId = 701L;
        row.failedStageCommandId = 801L;
        row.failedCommandAttemptNo = 2;
        row.failedStage = "DETERMINISTIC_REVIEW";
        row.failurePhase = "DETERMINISTIC_REVIEW";
        row.outcomeStatus = "REJECTED";
        row.failedTaskVersion = 31L;
        row.failedTaskFencingToken = 41L;
        row.failedStageRunId = 501L;
        row.failedRetrievalRunId = null;
        row.failedAiReviewRunId = null;
        row.sourcePolicyRunId = 601L;
        row.sourcePlanDigest = "sha256:" + "c".repeat(64);
        row.publicationOperationId = "";
        row.failureKind = "BUSINESS_REJECTED";
        row.recordedAt = OffsetDateTime.parse("2026-08-11T00:00:00Z");
        when(mapper.findExact(701L, "REJECTED", 31L, 41L)).thenReturn(row);
        RequirementStageFinalizationMapper finalizations = mock(RequirementStageFinalizationMapper.class);
        when(finalizations.countCorroboratedFailureProvenance(row)).thenReturn(1);
        PostgresTaskRetryFailureProvenanceStore store =
                new PostgresTaskRetryFailureProvenanceStore(mapper, finalizations);

        var provenance = store.findExact("701", RdTaskStatus.REJECTED, 31L, 41L);

        assertTrue(provenance.isPresent());
        assertEquals("801", provenance.orElseThrow().failedStageCommandId());
        assertEquals(TaskFailurePhase.DETERMINISTIC_REVIEW, provenance.orElseThrow().failurePhase());
        verify(mapper).findExact(701L, "REJECTED", 31L, 41L);
        verify(finalizations).countCorroboratedFailureProvenance(row);
    }

    @Test
    void rejectsUnknownPersistedEnumsInsteadOfGuessingAFailureIdentity() {
        TaskFailureProvenanceMapper mapper = mock(TaskFailureProvenanceMapper.class);
        TaskFailureProvenanceRow row = new TaskFailureProvenanceRow();
        row.id = 901L;
        row.taskId = 701L;
        row.failedStageCommandId = 801L;
        row.failedCommandAttemptNo = 2;
        row.failedStage = "ROLE_EXECUTION:CODING_AGENT";
        row.failurePhase = "UNKNOWN_PHASE";
        row.outcomeStatus = "FAILED_RETRYABLE";
        row.failedTaskVersion = 31L;
        row.failedTaskFencingToken = 41L;
        row.recordedAt = OffsetDateTime.parse("2026-08-11T00:00:00Z");
        when(mapper.findExact(701L, "FAILED_RETRYABLE", 31L, 41L)).thenReturn(row);
        RequirementStageFinalizationMapper finalizations = mock(RequirementStageFinalizationMapper.class);
        when(finalizations.countCorroboratedFailureProvenance(row)).thenReturn(1);
        PostgresTaskRetryFailureProvenanceStore store =
                new PostgresTaskRetryFailureProvenanceStore(mapper, finalizations);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.findExact("701", RdTaskStatus.FAILED_RETRYABLE, 31L, 41L));

        assertTrue(failure.getMessage().contains("failure_phase is invalid"));
    }

    @Test
    void rejectsAProvenanceRowThatItsCommandAndLedgersDoNotCorroborate() {
        TaskFailureProvenanceMapper mapper = mock(TaskFailureProvenanceMapper.class);
        RequirementStageFinalizationMapper finalizations = mock(RequirementStageFinalizationMapper.class);
        TaskFailureProvenanceRow row = new TaskFailureProvenanceRow();
        row.id = 901L;
        row.taskId = 701L;
        row.failedStageCommandId = 801L;
        row.failedCommandAttemptNo = 2;
        row.failedStage = "POLICY_APPLY";
        row.failurePhase = "POLICY";
        row.outcomeStatus = "FAILED_NEEDS_HUMAN";
        row.failedTaskVersion = 31L;
        row.failedTaskFencingToken = 41L;
        row.sourcePolicyRunId = 601L;
        row.sourcePlanDigest = "sha256:" + "c".repeat(64);
        row.recordedAt = OffsetDateTime.parse("2026-08-11T00:00:00Z");
        when(mapper.findExact(701L, "FAILED_NEEDS_HUMAN", 31L, 41L)).thenReturn(row);
        when(finalizations.countCorroboratedFailureProvenance(row)).thenReturn(0);
        PostgresTaskRetryFailureProvenanceStore store =
                new PostgresTaskRetryFailureProvenanceStore(mapper, finalizations);

        var provenance = store.findExact("701", RdTaskStatus.FAILED_NEEDS_HUMAN, 31L, 41L);

        assertTrue(provenance.isEmpty());
    }

    @Test
    void supportsFinalizedBusinessPreparedTechnicalAndMarkerlessPolicyWriterShapes() {
        TaskFailureProvenanceMapper mapper = mock(TaskFailureProvenanceMapper.class);
        RequirementStageFinalizationMapper finalizations = mock(RequirementStageFinalizationMapper.class);
        TaskFailureProvenanceRow business = row(
                901L, "DETERMINISTIC_REVIEW", "DETERMINISTIC_REVIEW", "REJECTED", "BUSINESS_REJECTED");
        TaskFailureProvenanceRow technical = row(
                902L, "ROLE_EXECUTION:CODING_AGENT", "AGENT_ROLE", "FAILED_RETRYABLE", "TECHNICAL_EXHAUSTED");
        technical.failedStageRunId = 501L;
        technical.sourcePolicyRunId = 601L;
        technical.sourcePlanDigest = "sha256:" + "c".repeat(64);
        TaskFailureProvenanceRow policy = row(
                903L, "POLICY_APPLY", "POLICY", "DEAD_LETTERED", "POLICY_CONTROL_EXHAUSTED");
        policy.sourcePolicyRunId = 601L;
        policy.sourcePlanDigest = "sha256:" + "c".repeat(64);
        when(mapper.findExact(701L, "REJECTED", 31L, 41L)).thenReturn(business);
        when(mapper.findExact(701L, "FAILED_RETRYABLE", 32L, 42L)).thenReturn(technical);
        when(mapper.findExact(701L, "DEAD_LETTERED", 33L, 43L)).thenReturn(policy);
        when(finalizations.countCorroboratedFailureProvenance(business)).thenReturn(1);
        when(finalizations.countCorroboratedFailureProvenance(technical)).thenReturn(1);
        when(finalizations.countCorroboratedFailureProvenance(policy)).thenReturn(1);
        PostgresTaskRetryFailureProvenanceStore store =
                new PostgresTaskRetryFailureProvenanceStore(mapper, finalizations);

        assertEquals("901", store.findExact("701", RdTaskStatus.REJECTED, 31L, 41L)
                .orElseThrow().provenanceId());
        assertEquals("902", store.findExact("701", RdTaskStatus.FAILED_RETRYABLE, 32L, 42L)
                .orElseThrow().provenanceId());
        assertEquals("903", store.findExact("701", RdTaskStatus.DEAD_LETTERED, 33L, 43L)
                .orElseThrow().provenanceId());
    }

    private static TaskFailureProvenanceRow row(
            long id,
            String stage,
            String phase,
            String outcome,
            String failureKind
    ) {
        TaskFailureProvenanceRow row = new TaskFailureProvenanceRow();
        row.id = id;
        row.taskId = 701L;
        row.failedStageCommandId = 800L + id;
        row.failedCommandAttemptNo = 2;
        row.failedStage = stage;
        row.failurePhase = phase;
        row.outcomeStatus = outcome;
        row.failedTaskVersion = id - 870L;
        row.failedTaskFencingToken = id - 860L;
        row.publicationOperationId = "";
        row.failureKind = failureKind;
        row.recordedAt = OffsetDateTime.parse("2026-08-11T00:00:00Z");
        return row;
    }

    private static int occurrences(String value, String fragment) {
        return value.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }
}
