package com.wish.rd.engine.requirement.model;

import java.util.List;

/**
 * 需求任务上下文包。
 *
 * <p>第一版用规则化摘要承载材料、验收标准和建议验证命令；后续可替换为 RAG 检索结果。
 */
public record RequirementContextPackage(
        String taskId,
        String requirementSummary,
        List<String> acceptanceCriteria,
        List<String> constraints,
        List<String> suggestedValidationCommands,
        List<String> materialIds,
        String traceId
) {

    public RequirementContextPackage {
        taskId = safe(taskId);
        requirementSummary = safe(requirementSummary);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        suggestedValidationCommands = suggestedValidationCommands == null
                ? List.of()
                : List.copyOf(suggestedValidationCommands);
        materialIds = materialIds == null ? List.of() : List.copyOf(materialIds);
        traceId = safe(traceId);
    }

    /**
     * 返回供执行器 prompt 和管理台审计展示的 JSON。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return """
                {"taskId":%s,"requirementSummary":%s,"acceptanceCriteria":%s,"constraints":%s,"suggestedValidationCommands":%s,"materialIds":%s,"traceId":%s}
                """.formatted(
                json(taskId),
                json(requirementSummary),
                jsonArray(acceptanceCriteria),
                jsonArray(constraints),
                jsonArray(suggestedValidationCommands),
                jsonArray(materialIds),
                json(traceId)
        ).strip();
    }

    static String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(RequirementContextPackage::json)
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
    }

    public static String json(String value) {
        return "\"" + safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
