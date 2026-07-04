package com.wish.rd.exec.repair.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 校验 Claude Code result.json 的纯业务协议，不依赖 Docker、GitHub、Spring 或本地文件系统。
 */
public final class StructuredResultValidator {

    private static final Set<String> VALID_STATUSES = Set.of("SUCCESS", "FAILED", "NEED_INFO", "UNSAFE");
    private static final Set<String> VALID_TEST_STATUSES = Set.of("PASSED", "FAILED", "SKIPPED");
    private static final Set<String> VALID_RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    private final ObjectMapper objectMapper;

    /**
     * 使用默认 Jackson ObjectMapper 创建校验器。
     */
    public StructuredResultValidator() {
        this(new ObjectMapper());
    }

    /**
     * 使用指定 Jackson ObjectMapper 创建校验器。
     *
     * @param objectMapper JSON 解析器
     */
    public StructuredResultValidator(ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper.copy())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 解析并校验 agent 输出的 result.json 内容。
     *
     * @param json agent 输出 JSON
     * @return 校验结果；无效 JSON 和业务错误都会返回对象而不是抛出异常
     */
    public StructuredResultValidation validate(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            return new StructuredResultValidation(false, null, List.of("parse error: " + e.getOriginalMessage()));
        }
        if (root == null || !root.isObject()) {
            return new StructuredResultValidation(false, null, List.of("result.json root must be an object"));
        }
        List<String> typeErrors = validateFieldTypes(root);
        if (!typeErrors.isEmpty()) {
            return new StructuredResultValidation(false, null, typeErrors);
        }

        StructuredRepairResult result;
        try {
            result = objectMapper.treeToValue(root, StructuredRepairResult.class);
        } catch (JsonProcessingException e) {
            return new StructuredResultValidation(false, null, List.of("parse error: " + e.getOriginalMessage()));
        }

        List<String> errors = validateBusinessFields(root, result);
        return new StructuredResultValidation(errors.isEmpty(), result, errors);
    }

    private static List<String> validateFieldTypes(JsonNode root) {
        List<String> errors = new ArrayList<>();
        validateStringFieldType(root, "status", errors);
        validateStringFieldType(root, "summary", errors);
        validateStringFieldType(root, "prBody", errors);
        validateStringArrayFieldType(root, "changedFiles", errors);
        validateStringArrayFieldType(root, "testCommands", errors);
        validateStringFieldType(root, "testStatus", errors);
        validateStringFieldType(root, "riskLevel", errors);
        validateOptionalBooleanFieldType(root, "needHumanAction", errors);
        return errors;
    }

    private static void validateStringFieldType(JsonNode root, String fieldName, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value != null && !value.isNull() && !value.isTextual()) {
            errors.add(fieldName + " must be a string");
        }
    }

    private static void validateStringArrayFieldType(JsonNode root, String fieldName, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value == null) {
            return;
        }
        if (value.isNull() || !value.isArray()) {
            errors.add(fieldName + " must be an array");
            return;
        }
        for (JsonNode entry : value) {
            if (!entry.isTextual()) {
                errors.add(fieldName + " entries must be strings");
                return;
            }
            if (entry.asText().isBlank()) {
                errors.add(fieldName + " entries must not be blank");
                return;
            }
        }
    }

    private static void validateBooleanFieldType(JsonNode root, String fieldName, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value == null) {
            errors.add(fieldName + " must be present");
            return;
        }
        if (!value.isBoolean()) {
            errors.add(fieldName + " must be boolean");
        }
    }

    private static void validateOptionalBooleanFieldType(JsonNode root, String fieldName, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value == null) {
            return;
        }
        if (!value.isBoolean()) {
            errors.add(fieldName + " must be boolean");
        }
    }

    private static List<String> validateBusinessFields(JsonNode root, StructuredRepairResult result) {
        List<String> errors = new ArrayList<>();
        validateStatus(result, errors);
        validateSummary(result, errors);
        validatePrBody(result, errors);
        validateChangedFiles(root, result, errors);
        validateTestCommands(root, result, errors);
        validateTestStatus(result, errors);
        validateRiskLevel(result, errors);
        validateNeedHumanAction(result, errors);
        return errors;
    }

    private static void validateStatus(StructuredRepairResult result, List<String> errors) {
        if (!VALID_STATUSES.contains(result.status())) {
            errors.add("status must be one of SUCCESS, FAILED, NEED_INFO, UNSAFE");
        }
    }

    private static void validateSummary(StructuredRepairResult result, List<String> errors) {
        if (result.summary().isBlank()) {
            errors.add("summary must not be blank");
        }
    }

    private static void validatePrBody(StructuredRepairResult result, List<String> errors) {
        if ("SUCCESS".equals(result.status()) && result.prBody().isBlank()) {
            errors.add("prBody must not be blank when status is SUCCESS");
        }
    }

    private static void validateChangedFiles(JsonNode root, StructuredRepairResult result, List<String> errors) {
        if (!root.has("changedFiles")) {
            errors.add("changedFiles must be present");
            return;
        }
        if (result.changedFiles().isEmpty() && !"NEED_INFO".equals(result.status()) && !"FAILED".equals(result.status())) {
            errors.add("changedFiles may be empty only when status is NEED_INFO or FAILED");
        }
    }

    private static void validateTestCommands(JsonNode root, StructuredRepairResult result, List<String> errors) {
        if (!root.has("testCommands")) {
            errors.add("testCommands must be present");
            return;
        }
        if (result.testCommands().isEmpty() && !"SKIPPED".equals(result.testStatus())) {
            errors.add("testCommands may be empty only when testStatus is SKIPPED");
        }
    }

    private static void validateTestStatus(StructuredRepairResult result, List<String> errors) {
        if (!VALID_TEST_STATUSES.contains(result.testStatus())) {
            errors.add("testStatus must be one of PASSED, FAILED, SKIPPED");
        }
    }

    private static void validateRiskLevel(StructuredRepairResult result, List<String> errors) {
        if (!VALID_RISK_LEVELS.contains(result.riskLevel())) {
            errors.add("riskLevel must be one of LOW, MEDIUM, HIGH");
        }
    }

    private static void validateNeedHumanAction(StructuredRepairResult result, List<String> errors) {
        if ("NEED_INFO".equals(result.status()) && !result.needHumanAction()) {
            errors.add("needHumanAction must be true when status is NEED_INFO");
        }
        if ("UNSAFE".equals(result.status()) && !result.needHumanAction()) {
            errors.add("needHumanAction must be true when status is UNSAFE");
        }
        if ("FAILED".equals(result.testStatus()) && !result.needHumanAction()) {
            errors.add("needHumanAction must be true when testStatus is FAILED");
        }
    }
}
