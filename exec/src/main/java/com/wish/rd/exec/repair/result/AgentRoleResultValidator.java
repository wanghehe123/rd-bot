package com.wish.rd.exec.repair.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多角色 Agent 产物协议校验器。
 *
 * <p>用于交付复核前检查需求评审、方案、编码和 QA 输出是否具备可审计字段；
 * 编码角色复用现有 {@link StructuredResultValidator}。
 */
public final class AgentRoleResultValidator {

    private static final Set<String> REVIEW_DECISIONS = Set.of("APPROVED", "NEED_INFO", "REJECTED");
    private static final Set<String> FEASIBILITY_VALUES = Set.of("CAN_DO", "NEED_INFO", "UNSAFE");
    private static final Set<String> QA_STATUSES = Set.of("PASSED", "FAILED", "SKIPPED");

    private final ObjectMapper objectMapper;
    private final StructuredResultValidator structuredResultValidator;

    /**
     * 使用默认 JSON 解析器创建校验器。
     */
    public AgentRoleResultValidator() {
        this(new ObjectMapper(), new StructuredResultValidator());
    }

    /**
     * 使用指定依赖创建校验器。
     *
     * @param objectMapper              JSON 解析器
     * @param structuredResultValidator 编码结果校验器
     */
    public AgentRoleResultValidator(
            ObjectMapper objectMapper,
            StructuredResultValidator structuredResultValidator
    ) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
        this.structuredResultValidator = structuredResultValidator == null
                ? new StructuredResultValidator()
                : structuredResultValidator;
    }

    /**
     * 校验指定角色的 JSON 产物。
     *
     * @param role Agent 角色
     * @param json 产物 JSON
     * @return 校验结果
     */
    public AgentRoleResultValidation validate(String role, String json) {
        String normalizedRole = normalizeRole(role);
        if ("CODING_AGENT".equals(normalizedRole)) {
            StructuredResultValidation validation = structuredResultValidator.validate(json);
            return new AgentRoleResultValidation(validation.valid(), validation.errors());
        }
        JsonNode root = parseObject(json);
        if (root == null) {
            return new AgentRoleResultValidation(false, List.of("result json root must be an object"));
        }
        List<String> errors = switch (normalizedRole) {
            case "REQUIREMENT_REVIEWER" -> validateRequirementReview(root);
            case "SOLUTION_ARCHITECT" -> validateSolutionPlan(root);
            case "QA_AGENT" -> validateQaReport(root);
            default -> List.of("unsupported agent role: " + normalizedRole);
        };
        return new AgentRoleResultValidation(errors.isEmpty(), errors);
    }

    private JsonNode parseObject(String json) {
        try {
            JsonNode root = objectMapper.readTree(json == null ? "" : json);
            if (root == null || !root.isObject()) {
                return null;
            }
            return root;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private List<String> validateRequirementReview(JsonNode root) {
        List<String> errors = new ArrayList<>();
        validateEnum(root, "decision", REVIEW_DECISIONS, errors);
        validateEnum(root, "feasibility", FEASIBILITY_VALUES, errors);
        validateArray(root, "missingInformation", false, errors);
        validateArray(root, "risks", false, errors);
        validateArray(root, "acceptanceCoverage", true, errors);
        return List.copyOf(errors);
    }

    private List<String> validateSolutionPlan(JsonNode root) {
        List<String> errors = new ArrayList<>();
        validateString(root, "summary", errors);
        validateArray(root, "affectedFiles", true, errors);
        validateArray(root, "implementationSteps", true, errors);
        validateArray(root, "acceptanceMapping", true, errors);
        validateArray(root, "testPlan", true, errors);
        return List.copyOf(errors);
    }

    private List<String> validateQaReport(JsonNode root) {
        List<String> errors = new ArrayList<>();
        validateEnum(root, "status", QA_STATUSES, errors);
        validateString(root, "summary", errors);
        JsonNode acceptanceResults = root.get("acceptanceResults");
        if (acceptanceResults == null || !acceptanceResults.isArray() || acceptanceResults.isEmpty()) {
            errors.add("acceptanceResults must be a non-empty array");
            return List.copyOf(errors);
        }
        String overallStatus = root.path("status").asText("").strip().toUpperCase(Locale.ROOT);
        int failedResultCount = 0;
        int nonPassedResultCount = 0;
        int index = 0;
        for (JsonNode result : acceptanceResults) {
            if (!result.isObject()) {
                errors.add("acceptanceResults[" + index + "] must be an object");
            } else {
                validateString(result, "criteria", "acceptanceResults[" + index + "].criteria", errors);
                validateString(result, "command", "acceptanceResults[" + index + "].command", errors);
                validateEnum(result, "status", "acceptanceResults[" + index + "].status", QA_STATUSES, errors);
                validateString(result, "logArtifactId", "acceptanceResults[" + index + "].logArtifactId", errors);
                String resultStatus = result.path("status").asText("").strip().toUpperCase(Locale.ROOT);
                if ("FAILED".equals(resultStatus)) {
                    failedResultCount++;
                }
                if (!"PASSED".equals(resultStatus)) {
                    nonPassedResultCount++;
                }
            }
            index++;
        }
        if ("PASSED".equals(overallStatus) && nonPassedResultCount > 0) {
            errors.add("status PASSED requires all acceptanceResults to be PASSED");
        }
        if ("FAILED".equals(overallStatus) && failedResultCount == 0) {
            errors.add("status FAILED requires at least one FAILED acceptanceResults item");
        }
        return List.copyOf(errors);
    }

    private static void validateString(JsonNode root, String fieldName, List<String> errors) {
        validateString(root, fieldName, fieldName, errors);
    }

    private static void validateString(JsonNode root, String fieldName, String displayName, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText("").isBlank()) {
            errors.add(displayName + " must not be blank");
        }
    }

    private static void validateEnum(
            JsonNode root,
            String fieldName,
            Set<String> allowedValues,
            List<String> errors
    ) {
        validateEnum(root, fieldName, fieldName, allowedValues, errors);
    }

    private static void validateEnum(
            JsonNode root,
            String fieldName,
            String displayName,
            Set<String> allowedValues,
            List<String> errors
    ) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText("").isBlank()) {
            errors.add(displayName + " must not be blank");
            return;
        }
        if (!allowedValues.contains(value.asText("").strip().toUpperCase(Locale.ROOT))) {
            errors.add(displayName + " must be one of " + String.join(", ", allowedValues));
        }
    }

    private static void validateArray(JsonNode root, String fieldName, boolean nonEmpty, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isArray()) {
            errors.add(fieldName + (nonEmpty ? " must be a non-empty array" : " must be an array"));
            return;
        }
        if (nonEmpty && value.isEmpty()) {
            errors.add(fieldName + " must be a non-empty array");
        }
    }

    private static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }
}
