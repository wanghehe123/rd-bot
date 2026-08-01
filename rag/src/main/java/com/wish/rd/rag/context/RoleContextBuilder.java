package com.wish.rd.rag.context;

import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;

/**
 * 角色上下文构建器。
 *
 * <p>第一版只做确定性证据裁剪和预算控制，不在 RAG 层调用模型；后续可把检索结果、历史经验和
 * 知识库 chunk 作为同一种 {@link RoleContextEvidence} 输入。
 */
public final class RoleContextBuilder {

    /** Default char budget when callers pass {@code 0} or a negative limit. */
    public static final int DEFAULT_MAX_CHARS = 18_000;

    /**
     * Resolves the effective char budget. {@code 0} and negative values are illegal as unlimited
     * budgets and fall back to {@link #DEFAULT_MAX_CHARS}.
     */
    public static int resolveMaxChars(int maxChars) {
      return maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
    }

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
      return build(packageId, task, materials, role, maxChars, 1, createdAtEpochMillis);
    }

    /** Builds a role context package with an explicit immutable version. */
    public RoleContextPackage build(
        String packageId,
        RdRequirementTask task,
        List<TaskMaterial> materials,
        String role,
        int maxChars,
        int packageVersion,
        long createdAtEpochMillis
    ) {
      String normalizedRole = normalizeRole(role);
      List<TaskMaterial> safeMaterials = materials == null ? List.of() : materials;
      List<CandidateEvidence> candidates = rankCandidates(normalizedRole, safeMaterials, createdAtEpochMillis);
      List<RoleContextEvidence> selected = new ArrayList<>();
      List<String> omitted = new ArrayList<>();
      int safeMaxChars = resolveMaxChars(maxChars);
      int usedChars = 0;
      for (CandidateEvidence candidate : candidates) {
        RoleContextEvidence evidence = candidate.evidence();
        RoleContextEvidence fitted = fitEvidenceToBudget(evidence, safeMaxChars - usedChars);
        if (fitted == null) {
          omitted.add(evidence.evidenceId());
          continue;
        }
        selected.add(fitted);
        usedChars += fitted.title().length() + fitted.summary().length();
      }
      return new RoleContextPackage(
          packageId,
          task == null ? "" : task.taskId(),
          normalizedRole,
          packageVersion,
          selected,
          parseJsonArray(task == null ? "" : task.acceptanceCriteriaJson()),
          riskHints(normalizedRole),
          safeMaxChars,
          usedChars,
          omitted,
          createdAtEpochMillis
      );
    }

    /**
     * Builds an immutable context from the evidence explicitly selected by a RetrievalRun.
     * Unscored historical experience and structurally invalid evidence are omitted rather than
     * silently promoted into every role context.
     */
    public RoleContextPackage buildFromEvidence(
        String packageId,
        RdRequirementTask task,
        List<RoleContextEvidence> selectedEvidence,
        String role,
        int maxChars,
        int packageVersion,
        String retrievalRunId,
        long createdAtEpochMillis
    ) {
      String normalizedRole = normalizeRole(role);
      int safeMaxChars = resolveMaxChars(maxChars);
      int usedChars = 0;
      List<RoleContextEvidence> included = new ArrayList<>();
      List<String> omitted = new ArrayList<>();
      LinkedHashSet<String> seen = new LinkedHashSet<>();
      for (RoleContextEvidence evidence : selectedEvidence == null ? List.<RoleContextEvidence>of() : selectedEvidence) {
        if (evidence == null || evidence.evidenceId().isBlank()) {
          continue;
        }
        boolean invalidReference = evidence.sourceUri().isBlank() || evidence.contentHash().isBlank();
        boolean zeroScoreExperience = "WORKFLOW_EXPERIENCE".equalsIgnoreCase(evidence.sourceType())
            && evidence.relevanceScore() <= 0.0d;
        boolean irrelevantExperience = irrelevantWorkflowExperience(task, evidence);
        String dedupeKey = evidence.contentHash().isBlank() ? evidence.evidenceId() : evidence.contentHash();
        if (invalidReference || zeroScoreExperience || irrelevantExperience || !seen.add(dedupeKey)) {
          omitted.add(evidence.evidenceId());
          continue;
        }
        RoleContextEvidence fitted = fitEvidenceToBudget(evidence, safeMaxChars - usedChars);
        if (fitted == null) {
          omitted.add(evidence.evidenceId());
          continue;
        }
        included.add(fitted);
        usedChars += fitted.title().length() + fitted.summary().length();
      }
      return new RoleContextPackage(
          packageId,
          task == null ? "" : task.taskId(),
          normalizedRole,
          packageVersion,
          included,
          parseJsonArray(task == null ? "" : task.acceptanceCriteriaJson()),
          riskHints(normalizedRole),
          safeMaxChars,
          usedChars,
          omitted,
          retrievalRunId,
          createdAtEpochMillis
      );
    }

    private RoleContextEvidence fitEvidenceToBudget(RoleContextEvidence evidence, int budgetRemaining) {
      if (budgetRemaining <= 0) {
        return null;
      }
      String title = evidence.title();
      String summary = evidence.summary();
      if (title.length() + summary.length() <= budgetRemaining) {
        return evidence;
      }
      String referenceSummary = referenceOnlySummary(evidence);
      int summaryBudget = budgetRemaining - title.length();
      if (summaryBudget > 0 && summary.length() > summaryBudget) {
        return withSummary(evidence, summary.substring(0, summaryBudget));
      }
      if (title.length() + referenceSummary.length() <= budgetRemaining) {
        return withSummary(evidence, referenceSummary);
      }
      int titleBudget = budgetRemaining - referenceSummary.length();
      if (titleBudget <= 0) {
        return null;
      }
      String truncatedTitle = title.length() <= titleBudget ? title : title.substring(0, titleBudget);
      return new RoleContextEvidence(
          evidence.evidenceId(),
          evidence.sourceType(),
          evidence.sourceUri(),
          truncatedTitle,
          evidence.contentHash(),
          referenceSummary,
          evidence.collectedAtEpochMillis(),
          evidence.selectionReason(),
          evidence.relevanceScore(),
          evidence.requiredEvidenceType(),
          evidence.sharedRoot()
      );
    }

    private String referenceOnlySummary(RoleContextEvidence evidence) {
      return "[ref: " + evidence.contentHash() + " @ " + evidence.sourceUri() + "]";
    }

    private RoleContextEvidence withSummary(RoleContextEvidence evidence, String summary) {
      return new RoleContextEvidence(
          evidence.evidenceId(),
          evidence.sourceType(),
          evidence.sourceUri(),
          evidence.title(),
          evidence.contentHash(),
          summary,
          evidence.collectedAtEpochMillis(),
          evidence.selectionReason(),
          evidence.relevanceScore(),
          evidence.requiredEvidenceType(),
          evidence.sharedRoot()
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

    private boolean irrelevantWorkflowExperience(RdRequirementTask task, RoleContextEvidence evidence) {
        if (!"WORKFLOW_EXPERIENCE".equalsIgnoreCase(evidence.sourceType())) {
            return false;
        }
        if (evidence.relevanceScore() <= 0.0d) {
            return true;
        }
        String query = taskQuery(task);
        if (query.isBlank()) {
            return false;
        }
        String corpus = (evidence.title() + " " + evidence.summary()).toLowerCase(Locale.ROOT);
        return !hasSemanticOverlap(query, corpus);
    }

    private String taskQuery(RdRequirementTask task) {
        if (task == null) {
            return "";
        }
        return (task.title() + " " + task.expectedResult()).toLowerCase(Locale.ROOT);
    }

    private boolean hasSemanticOverlap(String query, String corpus) {
        for (String token : semanticTokens(query)) {
            if (corpus.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> semanticTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch >= '\u4e00' && ch <= '\u9fff') {
                current.append(ch);
            } else if (!current.isEmpty()) {
                addSemanticToken(tokens, current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            addSemanticToken(tokens, current.toString());
        }
        return List.copyOf(tokens);
    }

    private void addSemanticToken(List<String> tokens, String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        if (normalized.length() >= 2) {
            tokens.add(normalized);
        }
    }

    private record CandidateEvidence(RoleContextEvidence evidence, int score, int index) {
    }
}
