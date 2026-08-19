package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Pure decision boundary for PI-v2 QA product remediation and one protocol retry. */
public final class PiQaRemediationPlanner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RECEIPT_KINDS = Set.of(
            "RESULT_MISSING_AFTER_RECOVERY",
            "AGENT_SETTLED_MISSING",
            "ROLE_SCHEMA_REJECTED_AFTER_RECOVERY"
    );

    public Optional<Decision> decide(
            String resultJson,
            RequirementExecutionProfileResolution sourceProfile,
            RequirementStageCommand sourceCommand,
            String sourceStageRunId,
            int sourceAttemptNo
    ) {
        if (sourceProfile == null || !sourceProfile.piQaRemediationV2Enabled()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(resultJson == null ? "" : resultJson);
            if (root == null || !root.isObject()) return Optional.empty();
            JsonNode metadata = root.path("dockerMetadata");
            boolean protocolReceiptPresent = !metadata.path("piProtocolFailureReceiptJson").asText("").isBlank()
                    || !metadata.path("piProtocolFailureReceiptHash").asText("").isBlank();
            Optional<Decision> protocol = protocolDecision(
                    root, sourceCommand, sourceStageRunId, sourceAttemptNo
            );
            if (protocolReceiptPresent) return protocol;
            if (verifiedProductRequest(root)) {
                return Optional.of(new Decision(
                        AgentRemediationKind.QA_PRODUCT_FIX, "", "", "", ""
                ));
            }
            return Optional.empty();
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static Optional<Decision> protocolDecision(
            JsonNode root,
            RequirementStageCommand command,
            String sourceStageRunId,
            int sourceAttemptNo
    ) {
        JsonNode metadata = root.path("dockerMetadata");
        String receiptJson = metadata.path("piProtocolFailureReceiptJson").asText("");
        String receiptHash = metadata.path("piProtocolFailureReceiptHash").asText("");
        if (receiptJson.isBlank() && receiptHash.isBlank()) return Optional.empty();
        if (command != null && command.remediationKind() == AgentRemediationKind.QA_PROTOCOL_RETRY) {
            return Optional.empty();
        }
        if (!receiptHash.matches("sha256:[0-9a-f]{64}")
                || !CanonicalJsonSha256.digest(receiptJson).equals(receiptHash)
                || !CanonicalJsonSha256.canonicalize(receiptJson).equals(receiptJson)) {
            return Optional.empty();
        }
        try {
            JsonNode receipt = MAPPER.readTree(receiptJson);
            String kind = receipt.path("kind").asText("");
            if (!"PiProtocolFailureReceipt/v1".equals(receipt.path("protocol").asText())
                    || !RECEIPT_KINDS.contains(kind)
                    || command == null
                    || !command.taskId().equals(receipt.path("taskId").asText())
                    || !sourceStageRunId.equals(receipt.path("stageRunId").asText())
                    || !"QA_AGENT".equals(receipt.path("role").asText())
                    || sourceAttemptNo != receipt.path("attemptNo").asInt(-1)
                    || !receipt.path("eventStreamTrusted").asBoolean(false)
                    || !receipt.path("containerTerminated").asBoolean(false)) {
                return Optional.empty();
            }
            if (!exactReceiptFacts(kind, receipt)) return Optional.empty();
            ObjectNode request = MAPPER.createObjectNode();
            request.put("protocol", "rd-pi-protocol-retry-request/v1");
            request.put("receiptKind", kind);
            request.put("receiptHash", receiptHash);
            request.put("instruction",
                    "仅重新完成 QA 协议收尾：复用上一轮真实验证证据，核对当前工作区后调用 rd_submit_result；不得创建或请求 Coding 修复。"
            );
            String requestJson = CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(request));
            return Optional.of(new Decision(
                    AgentRemediationKind.QA_PROTOCOL_RETRY,
                    requestJson,
                    CanonicalJsonSha256.digest(requestJson),
                    receiptJson,
                    receiptHash
            ));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static boolean exactReceiptFacts(String kind, JsonNode receipt) {
        Set<String> facts = new LinkedHashSet<>();
        receipt.path("missingFacts").forEach(value -> facts.add(value.asText("")));
        boolean recoveryAll = receipt.path("recoveryApplicable").asBoolean(false)
                && receipt.path("recoveryIssued").asBoolean(false)
                && receipt.path("recoveryExhausted").asBoolean(false);
        return switch (kind) {
            case "RESULT_MISSING_AFTER_RECOVERY" -> facts.equals(Set.of("AGENT_RESULT_SUBMITTED"))
                    && receipt.path("agentSettled").asBoolean(false)
                    && recoveryAll
                    && !"AGENT_RD_SUBMIT_RESULT".equals(receipt.path("resultSubmissionSource").asText());
            case "AGENT_SETTLED_MISSING" -> facts.equals(Set.of("AGENT_SETTLED"))
                    && !receipt.path("agentSettled").asBoolean(true)
                    && receipt.path("resultSubmitted").asBoolean(false)
                    && "AGENT_RD_SUBMIT_RESULT".equals(receipt.path("resultSubmissionSource").asText())
                    && receipt.path("roleSchemaAccepted").asBoolean(false)
                    && receipt.path("acceptedResultDigest").asText("").matches("sha256:[0-9a-f]{64}");
            case "ROLE_SCHEMA_REJECTED_AFTER_RECOVERY" -> facts.equals(Set.of(
                    "AGENT_RESULT_SUBMITTED", "ROLE_SCHEMA_ACCEPTED_RESULT"))
                    && receipt.path("agentSettled").asBoolean(false)
                    && recoveryAll
                    && "ROLE_SCHEMA".equals(receipt.path("lastRejectionKind").asText())
                    && receipt.path("lastRejectionDigest").asText("").matches("sha256:[0-9a-f]{64}");
            default -> false;
        };
    }

    private static boolean verifiedProductRequest(JsonNode root) {
        if (!"FAILED".equals(root.path("status").asText("").strip().toUpperCase())) return false;
        JsonNode request = root.path("remediationRequest");
        JsonNode findings = root.path("bugFindings");
        if (!request.path("requested").asBoolean(false)
                || !"CODING_AGENT".equals(request.path("targetRole").asText("").strip().toUpperCase())
                || request.path("reason").asText("").isBlank()
                || !request.path("bugFindingIds").isArray() || request.path("bugFindingIds").isEmpty()
                || !findings.isArray() || findings.isEmpty()) return false;
        Set<String> findingIds = new LinkedHashSet<>();
        findings.forEach(finding -> findingIds.add(finding.path("id").asText("")));
        for (JsonNode selected : request.path("bugFindingIds")) {
            if (selected.asText("").isBlank() || !findingIds.contains(selected.asText())) return false;
        }
        return true;
    }

    public record Decision(
            AgentRemediationKind kind,
            String requestJson,
            String requestHash,
            String receiptJson,
            String receiptHash
    ) { }
}
