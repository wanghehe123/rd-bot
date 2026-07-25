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

    /** 高危拦截关键词：全量扫描（含材料正文），宁可误杀不可放过。 */
    private static final String[] UNSAFE_PATTERNS = {"生产数据", "线上数据库", "导出密钥", "secret"};

    /** 人工审批高危短语：只扫任务主体，泛化词（配置/登录/token/auth/权限）已移除以消除误拦。 */
    private static final String[] APPROVAL_PATTERNS = {
            "支付", "payment", "删库", "drop table", "生产环境发布",
            "修改权限模型", "鉴权改造", "security policy", "私钥", "credentials"
    };

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
        String unsafeHit = firstMatch(corpus, UNSAFE_PATTERNS);
        if (unsafeHit != null) {
            return new RequirementPolicyDecision("UNSAFE", "HIGH", "需求包含生产数据或密钥相关高危操作：" + unsafeHit);
        }
        // 审批门禁只看任务主体：材料正文是大段需求文档/代码片段，是误拦噪声的最大来源。
        String approvalHit = firstMatch(taskCorpus(task), APPROVAL_PATTERNS);
        if (approvalHit != null) {
            return new RequirementPolicyDecision("WAITING_APPROVAL", "HIGH", "需求涉及高风险操作，等待人工审批：" + approvalHit);
        }
        return new RequirementPolicyDecision("ALLOWED", "LOW", "低风险需求，允许进入沙箱执行");
    }

    private String corpus(RdRequirementTask task, List<TaskMaterial> materials) {
        String materialText = materials == null
                ? ""
                : materials.stream()
                .map(TaskMaterial::contentPreview)
                .reduce("", (left, right) -> left + " " + right);
        return (taskCorpus(task) + " " + materialText).toLowerCase(Locale.ROOT);
    }

    private String taskCorpus(RdRequirementTask task) {
        String taskText = task == null
                ? ""
                : task.title() + " " + task.expectedResult() + " " + task.acceptanceCriteriaJson();
        return taskText.toLowerCase(Locale.ROOT);
    }

    private String firstMatch(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return pattern;
            }
        }
        return null;
    }
}
