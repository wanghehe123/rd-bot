package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T09/W7：公开 record/enum 必须落在 .model 包；本测试同时是「禁止新增未审查违规」的守卫。
 * 历史违规按计划 W7 逐类列出豁免（每类一条理由，禁止 wildcard/整域排除）。
 * 违规行以 `路径 -> FQCN` 形式匹配豁免键；豁免键删除或源文件移动都会让守卫失败。
 */
class ModelPackageIsolationPolicyTest {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);");
    private static final Pattern MODEL_TYPE_PATTERN = Pattern.compile("(?m)^public\\s+(record|enum)\\s+([A-Za-z0-9_]+)");

private static final Map<String, String> LEGACY_MODEL_PACKAGE_EXEMPTIONS = Map.ofEntries(
            Map.entry("com.wish.rd.bootstrap.verify.HostVerificationCandidatePatch",
                    "RULE.md §3.5.3 逐字锚点类（CleanHostVerificationWorkspaceFactory/HostVerificationExecutorAdapter 等）；包名即合同，保留"),
            Map.entry("com.wish.rd.rag.project.memory.ProjectMemoryProjectionMode",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.rag.project.memory.ProjectMemorySearchResult",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAction",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationCapability",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmToken",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipal",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.project.memory.LegacyExperienceInventoryItem",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.project.memory.LegacyExperienceInventoryRequest",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.project.memory.LegacyExperienceInventoryResult",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.project.memory.ProjectMemoryCaptureEvidence",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.project.memory.ProjectMemoryPromotionEvidence",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.project.memory.ProjectMemoryReconciliationCandidate",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.project.memory.ProjectMemoryReconciliationResult",
                    "memory 域非首发支持面，遗留布局；不为开源首发迁移"),
            Map.entry("com.wish.rd.engine.requirement.answer.AnswerRequirementCommand",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.answer.RequirementUserAnswerResult",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.answer.RequirementUserAnswerResumeResult",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditCompletion",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditIntegrity",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditMutation",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditRun",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditedContractRef",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditedRecord",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditedRecordKind",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditedRecordStatus",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditedStateMutation",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditedTaskState",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.AuditedWritebackGateMode",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.CompletionBinding",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.CompletionGateDecision",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.ContractAuditVerdict",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.EvidenceRef",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.EvidenceSourceKind",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.HostVerifySubject",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.QaCurrentAcceptance",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.QaSubject",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.RoleClaim",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.RoleClaimSubject",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.RoleFact",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.audit.WorkspaceFingerprintReceipt",
                    "审计写回域既有平铺布局（RULE.md §3.5.3 AuditedTaskState/AuditedRecord 锚点）；独立重构 change 再收敛"),
            Map.entry("com.wish.rd.engine.requirement.manager.ManagerDecision",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.manager.ManagerRoute",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.query.CodingMeaResponse",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.query.CodingMeaSnapshot",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.query.StageResultSnapshot",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.requirement.query.StageResultView",
                    "Manager 决策/用户问答/Coding MEA 读模型域（RULE.md 与冻结 spec 以类名锚定）；保留"),
            Map.entry("com.wish.rd.engine.retrieval.iterative.IterativeRetrievalPolicy",
                    "检索迭代域遗留布局（本 change 已移出 state/impl 两个明确违规）；保留"),
            Map.entry("com.wish.rd.engine.retrieval.iterative.RetrievalIterationLimits",
                    "检索迭代域遗留布局（本 change 已移出 state/impl 两个明确违规）；保留"),
            Map.entry("com.wish.rd.engine.retrieval.iterative.RetrievalRoundAudit",
                    "检索迭代域遗留布局（本 change 已移出 state/impl 两个明确违规）；保留"),
            Map.entry("com.wish.rd.engine.retrieval.iterative.RetrievalStopReason",
                    "检索迭代域遗留布局（本 change 已移出 state/impl 两个明确违规）；保留")
    );

    @Test
    void everyExemptionMustStillPointAtAnExistingSourceFile() {
        for (String fq : LEGACY_MODEL_PACKAGE_EXEMPTIONS.keySet()) {
            String relative = fq.replace(".", "/") + ".java";
            boolean exists = false;
            for (Path root : MODULE_ROOTS) {
                if (Files.exists(root.resolve(relative))) {
                    exists = true;
                    break;
                }
            }
            assertTrue(exists, "stale exemption (source moved or deleted): " + fq);
        }
    }

    @Test
    void publicDomainRecordsAndEnumsShouldLiveUnderModelPackages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : MODULE_ROOTS) {
            try (var files = Files.walk(root.normalize())) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collectViolation(path, violations));
            }
        }

        List<String> unreviewed = violations.stream()
                .filter(line -> LEGACY_MODEL_PACKAGE_EXEMPTIONS.keySet().stream()
                        .noneMatch(line::endsWith))
                .toList();
        assertTrue(unreviewed.isEmpty(), () ->
                "new records/enums must live in model packages (or be reviewed into "
                        + "LEGACY_MODEL_PACKAGE_EXEMPTIONS with a per-class reason):\n"
                        + String.join("\n", unreviewed));
    }

    private void collectViolation(Path path, List<String> violations) {
        try {
            String content = Files.readString(path);
            Matcher typeMatcher = MODEL_TYPE_PATTERN.matcher(content);
            if (!typeMatcher.find()) {
                return;
            }
            String packageName = packageName(content);
            if (isAllowedModelPackage(packageName)) {
                return;
            }
            String qualifiedName = packageName + "." + typeMatcher.group(2);
            if (LEGACY_MODEL_PACKAGE_EXEMPTIONS.containsKey(qualifiedName)) {
                return;
            }
            violations.add(path.normalize() + " -> " + qualifiedName);
        } catch (IOException exception) {
            violations.add(path.normalize() + " -> unreadable: " + exception.getMessage());
        }
    }

    private String packageName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean isAllowedModelPackage(String packageName) {
        return packageName.endsWith(".model")
                || packageName.contains(".model.")
                || packageName.contains(".controller.request")
                || packageName.contains(".controller.vo");
    }

    private static final List<Path> MODULE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("..", "rag", "src", "main", "java"),
            Path.of("..", "engine", "src", "main", "java"),
            Path.of("..", "exec", "src", "main", "java"),
            Path.of("..", "skill", "src", "main", "java")
    );
}
