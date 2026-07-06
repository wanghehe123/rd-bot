package com.wish.rd.engine.bugfix.acceptance.model;

/**
 * 验收计划步骤。
 *
 * @param stepType    步骤类型
 * @param tool        建议工具
 * @param description 步骤说明
 * @param inputJson   输入 JSON
 */
public record AcceptancePlanStep(
        String stepType,
        String tool,
        String description,
        String inputJson
) {

    public AcceptancePlanStep {
        stepType = stepType == null ? "" : stepType.strip();
        tool = tool == null ? "" : tool.strip();
        description = description == null ? "" : description.strip();
        inputJson = inputJson == null || inputJson.isBlank() ? "{}" : inputJson.strip();
    }
}
