package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiQaRemediationPlannerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PiQaRemediationPlanner planner = new PiQaRemediationPlanner();

    @Test
    void routesAllowlistedHostVerifiedReceiptOnceWithoutCoding() throws Exception {
        String receipt = receipt("RESULT_MISSING_AFTER_RECOVERY", "BRIDGE_SYNTHETIC");
        String result = MAPPER.writeValueAsString(Map.of(
                "status", "FAILED",
                "dockerMetadata", Map.of(
                        "piProtocolFailureReceiptJson", receipt,
                        "piProtocolFailureReceiptHash", CanonicalJsonSha256.digest(receipt)
                )
        ));

        PiQaRemediationPlanner.Decision decision = planner.decide(
                result, piProfile(), command(null), "201", 1
        ).orElseThrow();
        assertEquals(AgentRemediationKind.QA_PROTOCOL_RETRY, decision.kind());
        assertTrue(decision.requestJson().contains("不得创建或请求 Coding 修复"));
        assertTrue(planner.decide(
                result, piProfile(), command(AgentRemediationKind.QA_PROTOCOL_RETRY), "201", 1
        ).isEmpty());
    }

    @Test
    void rejectsSyntheticResultForAcceptedAgentResultKindAndIdentityMismatch() throws Exception {
        String receipt = receipt("AGENT_SETTLED_MISSING", "BRIDGE_SYNTHETIC");
        String result = MAPPER.writeValueAsString(Map.of(
                "dockerMetadata", Map.of(
                        "piProtocolFailureReceiptJson", receipt,
                        "piProtocolFailureReceiptHash", CanonicalJsonSha256.digest(receipt)
                )
        ));
        assertTrue(planner.decide(result, piProfile(), command(null), "201", 1).isEmpty());
        assertTrue(planner.decide(result.replace("\"201\"", "\"999\""),
                piProfile(), command(null), "201", 1).isEmpty());
    }

    @Test
    void protocolCompleteAcceptanceReportDetectsFailedCurrentGap() throws Exception {
        String qaJson = """
                {"status":"FAILED","failureCategory":"PRODUCT_DEFECT","retryRecommendation":"NONE",
                 "acceptanceResults":[
                   {"criteriaId":"AC-001","scope":"CURRENT","status":"PASSED","exitCode":0},
                   {"criteriaId":"AC-003","scope":"CURRENT","status":"FAILED","exitCode":1}
                 ]}
                """;
        String aggregate = MAPPER.writeValueAsString(Map.of(
                "status", "NEEDS_HUMAN",
                "stages", java.util.List.of(Map.of(
                        "role", "QA_AGENT",
                        "success", false,
                        "resultJson", qaJson.replaceAll("\\s+", "")
                ))
        ));
        assertTrue(PiQaRemediationPlanner.protocolCompleteAcceptanceReport(aggregate));
        assertFalse(PiQaRemediationPlanner.protocolCompleteAcceptanceReport(
                "{\"status\":\"FAILED\",\"summary\":\"no acceptances\"}"));
        String receipt = receipt("RESULT_MISSING_AFTER_RECOVERY", "BRIDGE_SYNTHETIC");
        assertFalse(PiQaRemediationPlanner.protocolCompleteAcceptanceReport(MAPPER.writeValueAsString(Map.of(
                "status", "FAILED",
                "acceptanceResults", java.util.List.of(Map.of("scope", "CURRENT", "status", "FAILED")),
                "dockerMetadata", Map.of(
                        "piProtocolFailureReceiptJson", receipt,
                        "piProtocolFailureReceiptHash", CanonicalJsonSha256.digest(receipt)
                )
        ))));
        assertFalse(
                PiQaRemediationPlanner.protocolCompleteAcceptanceReport(
                        """
                        {"status":"FAILED","failureCategory":"ENVIRONMENT","retryRecommendation":"HUMAN",
                         "acceptanceResults":[{"criteriaId":"AC-001","scope":"CURRENT","status":"FAILED"}]}
                        """),
                "environment failures stay FAILED_NEEDS_HUMAN; Manager does not own infra");
    }

    @Test
    void routesExplicitProductRequestOnRawQaResult() throws Exception {
        PiQaRemediationPlanner.Decision decision = planner.decide(
                productRequestJson(), piProfile(), command(null), "201", 1
        ).orElseThrow();
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, decision.kind());
    }

    @Test
    void routesExplicitProductRequestNestedInNeedsHumanAggregate() throws Exception {
        String aggregate = needsHumanAggregate(productRequestJson());

        PiQaRemediationPlanner.Decision decision = planner.decide(
                aggregate, piProfile(), command(null), "201", 1
        ).orElseThrow();
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, decision.kind());
    }

    @Test
    void routesExplicitProductRequestFromQaRoleResultSibling() throws Exception {
        ObjectNode aggregate = MAPPER.createObjectNode();
        aggregate.put("status", "NEEDS_HUMAN");
        aggregate.set("qaRoleResult", MAPPER.readTree(productRequestJson()));

        PiQaRemediationPlanner.Decision decision = planner.decide(
                MAPPER.writeValueAsString(aggregate), piProfile(), command(null), "201", 1
        ).orElseThrow();
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, decision.kind());
    }

    @Test
    void prefersQaRoleResultSiblingWhenNestedResultJsonIsGarbage() throws Exception {
        ObjectNode aggregate = MAPPER.createObjectNode();
        aggregate.put("status", "NEEDS_HUMAN");
        ObjectNode stage = aggregate.putArray("stages").addObject();
        stage.put("role", "QA_AGENT");
        stage.put("success", false);
        stage.put("resultJson", "{status:FAILED");
        aggregate.set("qaRoleResult", MAPPER.readTree(productRequestJson()));

        PiQaRemediationPlanner.Decision decision = planner.decide(
                MAPPER.writeValueAsString(aggregate), piProfile(), command(null), "201", 1
        ).orElseThrow();
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, decision.kind());
    }

    @Test
    void needsHumanAggregateWithoutNestedFailedQaDoesNotRouteCoding() throws Exception {
        String aggregate = MAPPER.writeValueAsString(Map.of(
                "status", "NEEDS_HUMAN",
                "stages", java.util.List.of(Map.of(
                        "role", "QA_AGENT",
                        "success", false,
                        "resultJson", "{\"status\":\"NEEDS_HUMAN\"}"
                ))
        ));
        assertTrue(planner.decide(aggregate, piProfile(), command(null), "201", 1).isEmpty());
    }

    @Test
    void contradictoryProtocolReceiptCannotFallThroughToCodingRemediation() throws Exception {
        String receipt = receipt("RESULT_MISSING_AFTER_RECOVERY", "BRIDGE_SYNTHETIC");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "FAILED");
        ObjectNode metadata = result.putObject("dockerMetadata");
        metadata.put("piProtocolFailureReceiptJson", receipt);
        metadata.put("piProtocolFailureReceiptHash", "sha256:" + "0".repeat(64));
        ObjectNode request = result.putObject("remediationRequest");
        request.put("requested", true);
        request.put("targetRole", "CODING_AGENT");
        request.put("reason", "agent supplied product defect");
        request.putArray("bugFindingIds").add("bug-1");
        result.putArray("bugFindings").addObject().put("id", "bug-1");

        assertTrue(planner.decide(
                MAPPER.writeValueAsString(result), piProfile(), command(null), "201", 1
        ).isEmpty(), "an invalid Host protocol receipt must fail closed instead of routing Coding");
    }

    private static RequirementExecutionProfileResolution piProfile() {
        return RequirementExecutionProfileResolution.of("snapshot-1", """
                {"runtimeType":"PI","capabilities":["PI_QA_REMEDIATION_V2"]}
                """);
    }

    private static String productRequestJson() throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "FAILED");
        result.put("failureCategory", "PRODUCT_DEFECT");
        result.put("retryRecommendation", "CODING_AGENT");
        ObjectNode request = result.putObject("remediationRequest");
        request.put("requested", true);
        request.put("targetRole", "CODING_AGENT");
        request.put("reason", "reproducible backend 500 blocks AC-003");
        request.putArray("bugFindingIds").add("bug-1");
        ObjectNode finding = result.putArray("bugFindings").addObject();
        finding.put("id", "bug-1");
        finding.put("severity", "HIGH");
        finding.put("acceptanceCriteriaId", "AC-003");
        finding.putArray("reproductionSteps").add("PATCH /api/merchants/manage/status");
        finding.put("expected", "HTTP 200");
        finding.put("actual", "HTTP 500");
        finding.putArray("evidenceArtifactIds").add("qa-evidence/commands/bug-repro.log");
        finding.putArray("suspectedFiles").add("server/src/routes/merchants.ts");
        return MAPPER.writeValueAsString(result);
    }

    private static String needsHumanAggregate(String qaResultJson) throws Exception {
        ObjectNode aggregate = MAPPER.createObjectNode();
        aggregate.put("status", "NEEDS_HUMAN");
        aggregate.put("pullRequestUrl", "");
        ObjectNode stage = aggregate.putArray("stages").addObject();
        stage.put("role", "QA_AGENT");
        stage.put("success", false);
        stage.put("summary", "QA_AGENT failed acceptance");
        stage.put("pullRequestUrl", "");
        stage.put("errorMessage", "QA_AGENT failed acceptance");
        stage.put("resultJson", qaResultJson);
        return MAPPER.writeValueAsString(aggregate);
    }

    private static RequirementStageCommand command(AgentRemediationKind kind) {
        RequirementStageCommand base = RequirementStageCommand.pending(
                "101", "100", 3L, 4L, "QA_AGENT", "ROLE_EXECUTION:QA_AGENT",
                0, 3, 9999999999999L, com.wish.rd.engine.scheduling.model.ScheduleResourceClass.BROWSER_QA,
                java.util.Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.BROWSER_QA),
                "project", "provider", "P1", "", 1L
        );
        return kind == null ? base : RequirementStageCommand.remediationPending(
                "101", "100", 3L, 4L, "QA_AGENT", "ROLE_EXECUTION:QA_AGENT",
                3, 9999999999999L, com.wish.rd.engine.scheduling.model.ScheduleResourceClass.BROWSER_QA,
                java.util.Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.BROWSER_QA),
                "project", "provider", "P1", "", "301", kind, 1,
                "source-previous", "{\"reason\":\"previous\"}",
                com.wish.rd.engine.requirement.policy.CanonicalJsonSha256.digest("{\"reason\":\"previous\"}"), 1L
        );
    }

    private static String receipt(String kind, String source) throws Exception {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("protocol", "PiProtocolFailureReceipt/v1");
        value.put("kind", kind);
        value.putArray("missingFacts").add("RESULT_MISSING_AFTER_RECOVERY".equals(kind)
                ? "AGENT_RESULT_SUBMITTED" : "AGENT_SETTLED");
        value.put("resultSubmitted", true);
        value.put("resultSubmissionSource", source);
        value.put("roleSchemaAccepted", "AGENT_RD_SUBMIT_RESULT".equals(source));
        value.put("acceptedResultDigest", "AGENT_RD_SUBMIT_RESULT".equals(source)
                ? "sha256:" + "a".repeat(64) : "");
        value.put("agentSettled", !"AGENT_SETTLED_MISSING".equals(kind));
        value.put("eventStreamTrusted", true);
        value.put("containerTerminated", true);
        value.put("recoveryApplicable", "RESULT_MISSING_AFTER_RECOVERY".equals(kind));
        value.put("recoveryIssued", "RESULT_MISSING_AFTER_RECOVERY".equals(kind));
        value.put("recoveryExhausted", "RESULT_MISSING_AFTER_RECOVERY".equals(kind));
        value.put("lastRejectionKind", "NONE");
        value.put("lastRejectionDigest", "");
        value.putArray("diagnosticArtifactIds").add("agent-events.jsonl");
        value.put("taskId", "100");
        value.put("stageRunId", "201");
        value.put("role", "QA_AGENT");
        value.put("attemptNo", 1);
        value.put("generatedAt", "2026-08-18T00:00:00Z");
        return CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(value));
    }
}
