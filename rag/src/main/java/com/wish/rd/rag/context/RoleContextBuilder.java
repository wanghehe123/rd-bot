package com.wish.rd.rag.context;

import com.wish.rd.rag.runtime.RdRequirementTask;
import com.wish.rd.rag.runtime.TaskMaterial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 角色上下文构建器。
 *
 * <p>第一版只做确定性证据裁剪和预算控制，不在 RAG 层调用模型；后续可把检索结果、历史经验和
 * 知识库 chunk 作为同一种 {@link RoleContextEvidence} 输入。
 */
public final class RoleContextBuilder {

    /**
     * 构建需求交付任务的角色上下文包。
     *
     * @param packageId             上下文包 ID
     * @param task                  需求任务
     * @param materials             任务材料
     * @param role                  Agent 角色
     * @param maxChars              字符预算
     * @param createdAtEpochMillis  创建时间
     * @return 角色上下文包
     */
    public RoleContextPackage build(
            String packageId,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            String role,
            int maxChars,
            long createdAtEpochMillis
    ) {
        String normalizedRole = normalizeRole(role);
        List<TaskMaterial> safeMaterials = materials == null ? List.of() : materials;
        List<CandidateEvidence> candidates = rankCandidates(normalizedRole, safeMaterials, createdAtEpochMillis);
        List<RoleContextEvidence> selected = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        int usedChars = 0;
        int safeMaxChars = Math.max(0, maxChars);
        for (CandidateEvidence candidate : candidates) {
            RoleContextEvidence evidence = candidate.evidence();
            int evidenceChars = evidence.title().length() + evidence.summary().length();
            if (!selected.isEmpty() && safeMaxChars > 0 && usedChars + evidenceChars > safeMaxChars) {
                omitted.add(evidence.evidenceId());
                continue;
            }
            selected.add(evidence);
            usedChars += evidenceChars;
        }
        return new RoleContextPackage(
                packageId,
                task == null ? "" : task.taskId(),
                normalizedRole,
                1,
                selected,
                parseJsonArray(task == null ? "" : task.acceptanceCriteriaJson()),
                riskHints(normalizedRole),
                safeMaxChars,
                usedChars,
                omitted,
                createdAtEpochMillis
        );
    }

    private List<CandidateEvidence> rankCandidates(
            String role,
            List<TaskMaterial> materials,
            long collectedAtEpochMillis
    ) {
        List<CandidateEvidence> candidates = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            TaskMaterial material = materials.get(i);
            if (material == null || material.materialId().isBlank()) {
                continue;
            }
            RoleContextEvidence evidence = new RoleContextEvidence(
                    material.materialId(),
                    material.sourceType().name(),
                    material.sourceUri(),
                    material.title(),
                    material.contentHash(),
                    material.contentPreview(),
                    collectedAtEpochMillis
            );
            candidates.add(new CandidateEvidence(evidence, score(role, material), i));
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(CandidateEvidence::score).reversed()
                        .thenComparingInt(CandidateEvidence::index))
                .toList();
    }

    private int score(String role, TaskMaterial material) {
        String corpus = (material.title() + " " + material.contentPreview()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords(role)) {
            if (corpus.contains(keyword)) {
                score += 10;
            }
        }
        if (score == 0) {
            return 1;
        }
        return score;
    }

    private List<String> keywords(String role) {
        return switch (role) {
            case "REQUIREMENT_REVIEWER" -> List.of("需求", "验收", "产品", "用户", "场景", "边界");
            case "SOLUTION_ARCHITECT" -> List.of("方案", "架构", "接口", "数据库", "契约", "影响");
            case "CODING_AGENT" -> List.of("代码", "controller", "service", "类", "方法", "测试命令", "文件");
            case "QA_AGENT" -> List.of("qa", "测试", "用例", "失败", "日志", "验收记录");
            default -> List.of();
        };
    }

    private List<String> riskHints(String role) {
        return switch (role) {
            case "REQUIREMENT_REVIEWER" -> List.of("确认需求是否缺少验收标准、仓库信息或外部 API 字段");
            case "SOLUTION_ARCHITECT" -> List.of("确认方案是否保持接口兼容并覆盖真实测试路径");
            case "CODING_AGENT" -> List.of("只使用角色上下文内证据，禁止把密钥写入日志、prompt 或 PR");
            case "QA_AGENT" -> List.of("每条验收标准必须有真实命令、日志引用和通过/失败判定");
            default -> List.of();
        };
    }

    static List<String> parseJsonArray(String value) {
        String safe = value == null ? "" : value.strip();
        if (safe.length() < 2 || safe.charAt(0) != '[' || safe.charAt(safe.length() - 1) != ']') {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaping = false;
        for (int i = 1; i < safe.length() - 1; i++) {
            char ch = safe.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                if (inString) {
                    String item = current.toString().strip();
                    if (!item.isBlank()) {
                        values.add(item);
                    }
                    current.setLength(0);
                }
                inString = !inString;
            } else if (inString) {
                current.append(ch);
            }
        }
        return List.copyOf(values);
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "REQUIREMENT_REVIEWER" : normalized;
    }

    private record CandidateEvidence(RoleContextEvidence evidence, int score, int index) {
    }
}
