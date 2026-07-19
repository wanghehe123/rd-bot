package com.wish.rd.engine.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.model.TaskFailureDiagnostic;
import com.wish.rd.engine.retry.model.TaskFailureIssue;
import com.wish.rd.engine.retry.model.TaskFailurePhase;

import java.util.ArrayList;
import java.util.List;

/** Parses bounded structured role results into stable failure diagnostics. */
public final class TaskFailureDiagnosticParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_TEXT_LENGTH = 2_000;

    /**
     * Creates one safe diagnostic for a failed phase.
     *
     * @param failurePhase resolved retry phase
     * @param retryFromRole failed role when the phase has a role
     * @param errorCategory durable stage error category
     * @param errorMessage durable stage error message
     * @param resultJson bounded result artifact preview
     * @return normalized diagnostic that is safe to render in the operator UI
     */
    public TaskFailureDiagnostic parse(
            TaskFailurePhase failurePhase,
            AgentRole retryFromRole,
            String errorCategory,
            String errorMessage,
            String resultJson
    ) {
        JsonNode root = parseJson(resultJson);
        String decision = text(root, "decision");
        String feasibility = text(root, "feasibility");
        String status = text(root, "status");
        boolean needsInformation = "NEED_INFO".equals(decision)
                || "NEED_INFO".equals(feasibility)
                || "NEED_INFO".equals(status);
        boolean needsHuman = needsInformation
                || "NEEDS_HUMAN".equals(decision)
                || "NEEDS_HUMAN".equals(feasibility)
                || "UNSAFE".equals(decision)
                || "REJECTED".equals(decision);

        List<TaskFailureIssue> issues = issues(root.path("missingInformation"), "MISSING_INFORMATION", "需要补充的信息");
        List<TaskFailureIssue> risks = issues(root.path("risks"), "RISK", "风险");
        List<TaskFailureIssue> acceptanceGaps = acceptanceGaps(root.path("acceptanceCoverage"));
        String category = firstNonBlank(errorCategory, failurePhase == null ? "UNKNOWN" : failurePhase.name());
        String title = title(failurePhase, needsInformation, needsHuman);
        String summary = firstNonBlank(
                text(root, "summary"),
                text(root, "reason"),
                errorMessage,
                issues.isEmpty() ? "当前阶段未产生可解析的结构化结果。" : issues.getFirst().detail()
        );
        String suggestedAction = suggestedAction(failurePhase, needsInformation, needsHuman, retryFromRole);
        boolean requiresSupplement = needsInformation || (failurePhase == TaskFailurePhase.RAG && needsHuman);
        return new TaskFailureDiagnostic(
                category,
                title,
                summary,
                suggestedAction,
                requiresSupplement,
                issues,
                risks,
                acceptanceGaps
        );
    }

    private static JsonNode parseJson(String value) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value == null ? "" : value.strip());
            return root != null && root.isObject() ? root : OBJECT_MAPPER.createObjectNode();
        } catch (Exception ignored) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static List<TaskFailureIssue> issues(JsonNode node, String kind, String title) {
        List<TaskFailureIssue> results = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String detail = item.isTextual() ? bounded(item.asText()) : bounded(item.toString());
                if (!detail.isBlank()) {
                    results.add(new TaskFailureIssue(kind, "HIGH", title, detail, nodeField(kind)));
                }
            }
        } else {
            String detail = node.isTextual() ? bounded(node.asText()) : bounded(node.toString());
            if (!detail.isBlank()) {
                results.add(new TaskFailureIssue(kind, "HIGH", title, detail, nodeField(kind)));
            }
        }
        return List.copyOf(results);
    }

    private static List<TaskFailureIssue> acceptanceGaps(JsonNode coverage) {
        if (coverage == null || coverage.isMissingNode() || coverage.isNull()) {
            return List.of();
        }
        if (coverage.isObject() && coverage.has("missing")) {
            return issues(coverage.path("missing"), "ACCEPTANCE_GAP", "未覆盖验收项");
        }
        if (coverage.isArray()) {
            return issues(coverage, "ACCEPTANCE_GAP", "验收覆盖信息");
        }
        return List.of();
    }

    private static String title(TaskFailurePhase phase, boolean needsInformation, boolean needsHuman) {
        if (needsInformation) {
            return "需求信息不足";
        }
        if (phase == TaskFailurePhase.RAG) {
            return "检索证据不足";
        }
        if (phase == TaskFailurePhase.POLICY) {
            return "策略门控阻断";
        }
        if (needsHuman) {
            return "需要人工处理";
        }
        return "阶段执行失败";
    }

    private static String suggestedAction(
            TaskFailurePhase phase,
            boolean needsInformation,
            boolean needsHuman,
            AgentRole role
    ) {
        if (needsInformation) {
            return "补充缺失的需求事实或验收证据后，从当前失败阶段重试。";
        }
        if (phase == TaskFailurePhase.RAG) {
            return "补充可检索的项目资料、日志或复现条件后重新检索。";
        }
        if (needsHuman) {
            return "确认失败原因并补充必要证据后，从当前失败阶段继续。";
        }
        String roleName = role == null ? "当前阶段" : role.name();
        return "核对 " + roleName + " 的错误分类与执行环境后重试。";
    }

    private static String text(JsonNode root, String field) {
        return root == null ? "" : bounded(root.path(field).asText(""));
    }

    private static String nodeField(String kind) {
        return switch (kind) {
            case "MISSING_INFORMATION" -> "missingInformation";
            case "RISK" -> "risks";
            default -> "acceptanceCoverage";
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = bounded(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String bounded(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= MAX_TEXT_LENGTH ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
    }
}
