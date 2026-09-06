package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedStateMutation;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.PiQaRemediationIntent;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.manager.ManagerRoute;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequirementStageExecutionPlanCodecTest {

    private final RequirementStageExecutionPlanCodec codec = new RequirementStageExecutionPlanCodec();

    @Test
    void shouldDecodeLegacyV1AndRoundTripCanonicalCurrentIntent() {
        String legacy = "{\"schemaVersion\":1,\"taskId\":\"task-1\",\"expectedVersion\":7,"
                + "\"expectedFencingToken\":3,\"expectedStatus\":\"EXECUTING\",\"mutations\":[],"
                + "\"commandDisposition\":\"SUCCEEDED\",\"continuation\":{\"role\":\"\",\"stage\":\"\"},"
                + "\"externalEffectReceipt\":{\"kind\":\"NONE\",\"operationId\":\"\","
                + "\"durableState\":\"\",\"receiptJson\":\"{}\"}}";
        RequirementStageExecutionPlan decodedLegacy = codec.decodeAndVerify(
                legacy, RequirementStageExecutionPlanCodec.digest(legacy));
        assertEquals(1, decodedLegacy.schemaVersion());
        assertNull(decodedLegacy.piQaRemediationIntent());
        assertNull(decodedLegacy.auditedStateMutation());

        RequirementStageExecutionPlan current = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-1", 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none(),
                intent());
        String canonical = codec.encodeCanonical(current);
        RequirementStageExecutionPlan roundTrip = codec.decodeAndVerify(
                "  " + canonical + "  ", RequirementStageExecutionPlanCodec.digest(canonical));

        assertEquals(current, roundTrip);
        assertEquals(3, roundTrip.schemaVersion());
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, roundTrip.piQaRemediationIntent().kind());
        assertNull(roundTrip.auditedStateMutation());
    }

    @Test
    void shouldEncodeV3WritebackAndDecodeV2JsonWithoutMutation() {
        RequirementStageExecutionPlan without = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-1", 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none());
        AuditedStateMutation mutation = auditedMutation("task-1", "cmd-audit", "AC-001");
        RequirementStageExecutionPlan withWriteback = without.withAuditedStateMutation(mutation);
        String withCanonical = codec.encodeCanonical(withWriteback);
        String withoutCanonical = codec.encodeCanonical(without);
        assertNotEquals(RequirementStageExecutionPlanCodec.digest(withoutCanonical),
                RequirementStageExecutionPlanCodec.digest(withCanonical));
        RequirementStageExecutionPlan decodedV3 = codec.decodeAndVerify(
                withCanonical, RequirementStageExecutionPlanCodec.digest(withCanonical));
        assertEquals(mutation.nextState().stateHash(), decodedV3.auditedStateMutation().nextState().stateHash());
        assertEquals(mutation.auditRun().auditRunId(), decodedV3.auditedStateMutation().auditRun().auditRunId());

        String v2 = "{\"schemaVersion\":2,\"taskId\":\"task-1\",\"expectedVersion\":7,"
                + "\"expectedFencingToken\":3,\"expectedStatus\":\"EXECUTING\",\"mutations\":[],"
                + "\"commandDisposition\":\"SUCCEEDED\",\"continuation\":{\"role\":\"\",\"stage\":\"\"},"
                + "\"externalEffectReceipt\":{\"kind\":\"NONE\",\"operationId\":\"\","
                + "\"durableState\":\"\",\"receiptJson\":\"{}\"},\"piQaRemediationIntent\":null}";
        RequirementStageExecutionPlan decodedV2 = codec.decodeAndVerify(
                v2, RequirementStageExecutionPlanCodec.digest(v2));
        assertEquals(2, decodedV2.schemaVersion());
        assertNull(decodedV2.auditedStateMutation());
        assertNull(decodedV2.managerDecision());
    }

    @Test
    void shouldRoundTripManagerDecisionAndKeepMissingFieldNull() {
        RequirementStageExecutionPlan without = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-1", 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none());
        ManagerDecision decision = ManagerDecision.of(
                "task-1", 2, "qa-command-1", 7L, "sha256:" + "d".repeat(64),
                ManagerRoute.EXECUTE, List.of("AC-001"), "只修复 AC-001", "CODING_AGENT",
                "pending blocking acceptance");
        RequirementStageExecutionPlan withDecision = without.withManagerDecision(decision);
        String canonical = codec.encodeCanonical(withDecision);
        RequirementStageExecutionPlan roundTrip = codec.decodeAndVerify(
                canonical, RequirementStageExecutionPlanCodec.digest(canonical));
        assertEquals(decision, roundTrip.managerDecision());
        assertNotEquals(
                RequirementStageExecutionPlanCodec.digest(codec.encodeCanonical(without)),
                RequirementStageExecutionPlanCodec.digest(canonical));

        String v3 = "{\"schemaVersion\":3,\"taskId\":\"task-1\",\"expectedVersion\":7,"
                + "\"expectedFencingToken\":3,\"expectedStatus\":\"EXECUTING\",\"mutations\":[],"
                + "\"commandDisposition\":\"SUCCEEDED\",\"continuation\":{\"role\":\"\",\"stage\":\"\"},"
                + "\"externalEffectReceipt\":{\"kind\":\"NONE\",\"operationId\":\"\","
                + "\"durableState\":\"\",\"receiptJson\":\"{}\"},\"piQaRemediationIntent\":null,"
                + "\"auditedStateMutation\":null}";
        RequirementStageExecutionPlan decoded = codec.decodeAndVerify(
                v3, RequirementStageExecutionPlanCodec.digest(v3));
        assertNull(decoded.managerDecision());
    }

    @Test
    void shouldRejectV1IntentAndTamperedNestedOrPlanHashes() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                1, "task-1", 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none(),
                intent()));

        PiQaRemediationIntent valid = intent();
        assertThrows(IllegalArgumentException.class, () -> new PiQaRemediationIntent(
                valid.protocol(), valid.sourceTaskId(), valid.sourceStageRunId(), valid.sourceCommandId(),
                valid.sourceResultHash(), valid.sourceTaskVersion(), valid.sourceFencingToken(),
                valid.kind(), valid.remediationNo(), valid.roundId(), valid.requestJson(), "sha256:" + "f".repeat(64),
                valid.targetCodingStageRunId(), valid.targetCodingAttemptNo(), valid.targetQaStageRunId(),
                valid.targetQaAttemptNo(), valid.firstCommandId(), valid.sourceProfile(),
                valid.codingProfile(), valid.qaProfile(), valid.protocolFailureReceiptJson(),
                valid.protocolFailureReceiptHash()));

        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                2, "task-1", 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none(), intent());
        String canonical = codec.encodeCanonical(plan);
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeAndVerify(canonical, "sha256:" + "0".repeat(64)));
    }

    private static PiQaRemediationIntent intent() {
        String request = "{\"bugFindingIds\":[\"BUG-1\"],\"reason\":\"verified defect\"}";
        PiQaRemediationIntent.ExecutionProfileClaim source = new PiQaRemediationIntent.ExecutionProfileClaim(
                "qa-profile", 4L, "PI", List.of("PI_QA_REMEDIATION_V2"));
        String codingJson = snapshot("coding-stage-2", "CODING_AGENT", 2, "coding-profile", 5L);
        String qaJson = snapshot("qa-stage-2", "QA_AGENT", 2, "qa-profile", 4L);
        PiQaRemediationIntent.PreparedProfileSnapshot coding = new PiQaRemediationIntent.PreparedProfileSnapshot(
                "snapshot-coding-2", "coding-stage-2", "CODING_AGENT", 2,
                new PiQaRemediationIntent.ExecutionProfileClaim(
                        "coding-profile", 5L, "PI", List.of("PI_QA_REMEDIATION_V2")),
                codingJson, sha256Raw(codingJson));
        PiQaRemediationIntent.PreparedProfileSnapshot qa = new PiQaRemediationIntent.PreparedProfileSnapshot(
                "snapshot-qa-2", "qa-stage-2", "QA_AGENT", 2, source, qaJson, sha256Raw(qaJson));
        return new PiQaRemediationIntent(
                PiQaRemediationIntent.PROTOCOL, "task-1", "qa-stage-1", "qa-command-1",
                "sha256:" + "a".repeat(64), 7L, 3L,
                AgentRemediationKind.QA_PRODUCT_FIX, 1, "9001",
                request, RequirementStageExecutionPlanCodec.digest(request),
                "coding-stage-2", 2, "qa-stage-2", 2, "coding-command-2",
                source, coding, qa, "", "");
    }

    private static String snapshot(String stage, String role, int attempt, String profile, long version) {
        return "{\"attemptNo\":" + attempt + ",\"capabilities\":[\"PI_QA_REMEDIATION_V2\"],"
                + "\"profileId\":\"" + profile + "\",\"profileVersion\":" + version + ","
                + "\"role\":\"" + role + "\",\"runtimeType\":\"PI\","
                + "\"stageRunId\":\"" + stage + "\",\"taskId\":\"task-1\"}";
    }

    private static String sha256Raw(String value) {
        return RequirementStageExecutionPlanCodec.digest(value).substring("sha256:".length());
    }

    private static AuditedStateMutation auditedMutation(String taskId, String commandId, String recordId) {
        AuditedTaskState next = new AuditedTaskStateCodec().seal(new AuditedTaskState(
                taskId,
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                List.of(new AuditedRecord(
                        recordId,
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        "criterion " + recordId,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                "audit-" + commandId));
        AuditRun run = new AuditRun(
                "audit-" + commandId,
                taskId,
                "stage-1",
                "HOST_VERIFY",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(recordId),
                List.of(),
                List.of(),
                List.of(),
                1_700_000_000_000L);
        return new AuditedStateMutation(run, next, next.stateVersion());
    }
}
