package com.wish.rd.engine.requirement;

import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.util.List;
import java.util.Locale;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementPlan;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;

/**
 * 规则化需求策略门禁。
 *
 * <p>用于第一版控制面保护：缺信息不执行，高风险需求等待人工确认，危险意图直接拦截。
 */
public final class RuleBasedRequirementPolicyGate {

    /**
     * 评估需求任务是否可进入执行器。
     *
     * @param task      需求任务
     * @param context   上下文包
     * @param plan      实现计划
     * @param materials 任务材料
     * @return 策略决策
     */
    public RequirementPolicyDecision decide(
            RdRequirementTask task,
            RequirementContextPackage context,
            RequirementPlan plan,
            List<TaskMaterial> materials
    ) {
        if (context == null || context.acceptanceCriteria().isEmpty()) {
            return new RequirementPolicyDecision("NEED_INFO", "MEDIUM", "需求任务缺少验收标准，不能进入自动编码");
        }
        String repositoryUrl = task == null ? "" : task.repositoryUrl();
        if (repositoryUrl.isBlank()) {
            return new RequirementPolicyDecision("NEED_INFO", "MEDIUM", "需求任务缺少代码仓库");
        }
        String corpus = corpus(task, materials);
        if (containsAny(corpus, "生产数据", "线上数据库", "导出密钥", "secret")) {
            return new RequirementPolicyDecision("UNSAFE", "HIGH", "需求包含生产数据或密钥相关高危操作");
        }
        // 单独出现 token 无法证明密钥泄露，保留在审批分支避免自动放行。
        if (containsAny(corpus, "auth", "security", "payment", "支付", "权限", "登录", "配置", "token")) {
            return new RequirementPolicyDecision("WAITING_APPROVAL", "HIGH", "需求涉及高风险模块，等待人工审批");
        }
        return new RequirementPolicyDecision("ALLOWED", "LOW", "低风险需求，允许进入沙箱执行");
    }

    private String corpus(RdRequirementTask task, List<TaskMaterial> materials) {
        String taskText = task == null
                ? ""
                : task.title() + " " + task.expectedResult() + " " + task.acceptanceCriteriaJson();
        String materialText = materials == null
                ? ""
                : materials.stream()
                .map(TaskMaterial::contentPreview)
                .reduce("", (left, right) -> left + " " + right);
        return (taskText + " " + materialText).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
