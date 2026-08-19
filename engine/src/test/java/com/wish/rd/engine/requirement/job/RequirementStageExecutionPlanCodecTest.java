package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.PiQaRemediationIntent;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequirementStageExecutionPlanCodecTest {

    private final RequirementStageExecutionPlanCodec codec = new RequirementStageExecutionPlanCodec();

    @Test
    void shouldDecodeLegacyV1AndRoundTripCanonicalV2Intent() {
        String legacy = "{\"schemaVersion\":1,\"taskId\":\"task-1\",\"expectedVersion\":7,"
                + "\"expectedFencingToken\":3,\"expectedStatus\":\"EXECUTING\",\"mutations\":[],"
                + "\"commandDisposition\":\"SUCCEEDED\",\"continuation\":{\"role\":\"\",\"stage\":\"\"},"
                + "\"externalEffectReceipt\":{\"kind\":\"NONE\",\"operationId\":\"\","
                + "\"durableState\":\"\",\"receiptJson\":\"{}\"}}";
        RequirementStageExecutionPlan decodedLegacy = codec.decodeAndVerify(
                legacy, RequirementStageExecutionPlanCodec.digest(legacy));
        assertEquals(1, decodedLegacy.schemaVersion());
        assertNull(decodedLegacy.piQaRemediationIntent());

        RequirementStageExecutionPlan v2 = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-1", 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none(),
                intent());
        String canonical = codec.encodeCanonical(v2);
        RequirementStageExecutionPlan roundTrip = codec.decodeAndVerify(
                "  " + canonical + "  ", RequirementStageExecutionPlanCodec.digest(canonical));

        assertEquals(v2, roundTrip);
        assertEquals(2, roundTrip.schemaVersion());
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, roundTrip.piQaRemediationIntent().kind());
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
}
