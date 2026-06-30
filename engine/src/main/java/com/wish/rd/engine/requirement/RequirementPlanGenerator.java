package com.wish.rd.engine.requirement;

import com.wish.rd.rag.runtime.RdRequirementTask;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则化需求计划生成器。
 */
public final class RequirementPlanGenerator {

    /**
     * 基于上下文生成第一版实现计划。
     *
     * @param task    需求任务
     * @param context 上下文包
     * @return 实现计划
     */
    public RequirementPlan generate(RdRequirementTask task, RequirementContextPackage context) {
        List<String> steps = new ArrayList<>();
        steps.add("阅读需求材料并定位与预期结果相关的代码路径");
        steps.add("按最小改动实现需求，保持既有接口兼容");
        steps.add("补充或更新与验收标准匹配的测试");
        steps.add("运行建议验证命令并整理 PR 改动介绍");
        return new RequirementPlan(
                task == null ? "" : task.taskId(),
                steps,
                context == null ? List.of() : context.acceptanceCriteria(),
                context == null ? List.of() : context.suggestedValidationCommands()
        );
    }
}
