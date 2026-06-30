package com.wish.rd.engine.requirement;

import java.util.List;

/**
 * 需求实现计划。
 *
 * @param taskId                      任务 ID
 * @param implementationSteps         实现步骤
 * @param acceptanceCriteria          验收标准
 * @param suggestedValidationCommands 建议验证命令
 */
public record RequirementPlan(
        String taskId,
        List<String> implementationSteps,
        List<String> acceptanceCriteria,
        List<String> suggestedValidationCommands
) {

    public RequirementPlan {
        taskId = taskId == null ? "" : taskId.strip();
        implementationSteps = implementationSteps == null ? List.of() : List.copyOf(implementationSteps);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        suggestedValidationCommands = suggestedValidationCommands == null
                ? List.of()
                : List.copyOf(suggestedValidationCommands);
    }

    /**
     * 返回 JSON 表达。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return """
                {"taskId":%s,"implementationSteps":%s,"acceptanceCriteria":%s,"suggestedValidationCommands":%s}
                """.formatted(
                RequirementContextPackage.json(taskId),
                RequirementContextPackage.jsonArray(implementationSteps),
                RequirementContextPackage.jsonArray(acceptanceCriteria),
                RequirementContextPackage.jsonArray(suggestedValidationCommands)
        ).strip();
    }
}
