package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;

/**
 * 需求交付复核器。
 *
 * <p>该组件只复核控制面产物完整性，不调用外部 provider，也不修改代码或创建 PR。
 */
@Component
public class RequirementDeliveryReviewer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 复核多 Agent 交付结果是否可以发布 PR。
     *
     * @param taskId             RD 任务 ID
     * @param deliveryResultJson 多 Agent 聚合结果 JSON
     * @return 复核结果
     */
    public RequirementDeliveryReviewResult review(String taskId, String deliveryResultJson) {
        String normalizedTaskId = safe(taskId);
        if (normalizedTaskId.isBlank()) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "taskId must not be blank");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(deliveryResultJson == null ? "" : deliveryResultJson);
        } catch (JsonProcessingException exception) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "invalid delivery result json");
        }
        if (root == null || !root.isObject()) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "delivery result json must be an object");
        }
        if (!"SUCCESS".equals(text(root.path("multiAgentStatus")))) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "multiAgentStatus must be SUCCESS");
        }
        JsonNode stages = root.path("multiAgentStages");
        if (!stages.isArray() || stages.isEmpty()) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "multiAgentStages must not be empty");
        }
        Map<AgentRole, JsonNode> byRole = stagesByRole(stages);
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            JsonNode stage = byRole.get(role);
            if (stage == null) {
                return RequirementDeliveryReviewResult.rejected(
                        normalizedTaskId,
                        "required agent stage missing: " + role.name()
                );
            }
            if (!text(stage.path("pullRequestUrl")).isBlank()) {
                return RequirementDeliveryReviewResult.rejected(
                        normalizedTaskId,
                        "agent stage must not contain pullRequestUrl before delivery review: " + role.name()
                );
            }
            if (!stage.path("success").asBoolean(false)) {
                return RequirementDeliveryReviewResult.rejected(
                        normalizedTaskId,
                        "required agent stage failed: " + role.name()
                );
            }
        }
        JsonNode codingStage = byRole.get(AgentRole.CODING_AGENT);
        JsonNode codingResult = codingStage.path("resultJson");
        if (!hasCodingDeliveryEvidence(codingResult)) {
            return RequirementDeliveryReviewResult.rejected(
                    normalizedTaskId,
                    "CODING_AGENT delivery evidence is incomplete"
            );
        }
        JsonNode qaStage = byRole.get(AgentRole.QA_AGENT);
        JsonNode qaResult = qaStage.path("resultJson");
        if (!hasQaDeliveryEvidence(qaResult)) {
            return RequirementDeliveryReviewResult.rejected(
                    normalizedTaskId,
                    "QA_AGENT delivery evidence is incomplete"
            );
        }
        return RequirementDeliveryReviewResult.approved(normalizedTaskId);
    }

    /**
     * 兼容旧调用方；PR URL 不再参与复核，PR 发布发生在复核通过之后。
     */
    public RequirementDeliveryReviewResult review(String taskId, String pullRequestUrl, String deliveryResultJson) {
        return review(taskId, deliveryResultJson);
    }

    private boolean hasCodingDeliveryEvidence(JsonNode codingResult) {
        JsonNode parsed = parseResultObject(codingResult);
        if (parsed == null) {
            return false;
        }
        return !text(parsed.path("prBody")).isBlank()
                || !text(parsed.path("changedFiles")).isBlank()
                || !text(parsed.path("testSummary")).isBlank();
    }

    private boolean hasQaDeliveryEvidence(JsonNode qaResult) {
        JsonNode parsed = parseResultObject(qaResult);
        if (parsed == null) {
            return false;
        }
        if (!"PASSED".equalsIgnoreCase(text(parsed.path("status")))) {
            return false;
        }
        JsonNode acceptanceResults = parsed.path("acceptanceResults");
        if (!acceptanceResults.isArray() || acceptanceResults.isEmpty()) {
            return false;
        }
        for (JsonNode acceptanceResult : acceptanceResults) {
            if (!acceptanceResult.isObject()
                    || text(acceptanceResult.path("criteria")).isBlank()
                    || text(acceptanceResult.path("command")).isBlank()
                    || !"PASSED".equalsIgnoreCase(text(acceptanceResult.path("status")))
                    || text(acceptanceResult.path("logArtifactId")).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private JsonNode parseResultObject(JsonNode result) {
        if (result == null || result.isMissingNode()) {
            return null;
        }
        String raw = text(result);
        JsonNode parsed = result;
        if (!raw.isBlank()) {
            try {
                parsed = OBJECT_MAPPER.readTree(raw);
            } catch (JsonProcessingException exception) {
                return null;
            }
        }
        if (parsed == null || !parsed.isObject()) {
            return null;
        }
        return parsed;
    }

    private Map<AgentRole, JsonNode> stagesByRole(JsonNode stages) {
        Map<AgentRole, JsonNode> byRole = new EnumMap<>(AgentRole.class);
        stages.forEach(stage -> {
            AgentRole role = parseRole(text(stage.path("role")));
            if (role != null) {
                byRole.putIfAbsent(role, stage);
            }
        });
        return byRole;
    }

    private AgentRole parseRole(String value) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return AgentRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String text(JsonNode node) {
        return node == null || !node.isTextual() ? "" : safe(node.asText());
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
