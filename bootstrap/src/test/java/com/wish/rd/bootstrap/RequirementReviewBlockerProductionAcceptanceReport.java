package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Markdown and JSON evidence for the requirement-review blocker production smoke.
 */
final class RequirementReviewBlockerProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final String REQUIRED_ALERT_TYPE = "STAGE_FAILED_NEEDS_HUMAN";
    private static final List<String> BLOCKING_DECISIONS = List.of(
            "NEED_INFO",
            "NEEDS_HUMAN",
            "UNSAFE",
            "REJECTED",
            "FAILED",
            "BLOCKED"
    );
    private static final List<String> BLOCKING_TASK_STATUSES = List.of(
            "FAILED_NEEDS_HUMAN",
            "REJECTED"
    );
    private static final List<String> BLOCKING_EXECUTION_STATUSES = List.of(
            "NEEDS_HUMAN",
            "FAILED",
            "FAILED_NEEDS_HUMAN"
    );
    private static final List<String> REQUIRED_PENDING_ROLES = List.of(
            "SOLUTION_ARCHITECT",
            "CODING_AGENT",
            "QA_AGENT"
    );
    private static final List<AcceptancePoint> ACCEPTANCE_POINTS = List.of(
            new AcceptancePoint(1, "需求任务可进入多 Agent 工作流"),
            new AcceptancePoint(2, "角色上下文包真实落库且内容不同"),
            new AcceptancePoint(3, "需求评审 Agent 能阻断不可交付需求"),
            new AcceptancePoint(4, "方案 Agent 产出可执行开发方案"),
            new AcceptancePoint(5, "多 provider 降级重试真实生效"),
            new AcceptancePoint(6, "编码 Agent 在 Docker 中真实改代码并运行测试"),
            new AcceptancePoint(7, "QA Agent 逐条验收并阻断失败交付"),
            new AcceptancePoint(8, "交付复核通过后才提交为已交付"),
            new AcceptancePoint(9, "状态机可恢复且不会重复派发"),
            new AcceptancePoint(10, "错误通知真实送达 Feishu"),
            new AcceptancePoint(11, "Skill 安装和使用受策略控制"),
            new AcceptancePoint(12, "经验自动沉淀且可被后续 RAG 检索"),
            new AcceptancePoint(13, "密钥和敏感信息不进入产物"),
            new AcceptancePoint(14, "指标和审计可观测"),
            new AcceptancePoint(15, "生产真实测试结论要求")
    );

    private final Path reportRoot;
    private final Clock clock;

    RequirementReviewBlockerProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static RequirementReviewBlockerProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.requirement-review.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new RequirementReviewBlockerProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 requirement review blocker smoke 参数不完整，本次没有访问真实 RD-Bot HTTP 或 PostgreSQL。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 requirement review blocker 前置条件"));
        return write(markdown);
    }

    Path writePassed(RequirementReviewBlockerSmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_REQUIREMENT_REVIEW_BLOCKER_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writeJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(RequirementReviewBlockerSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_REQUIREMENT_REVIEW_BLOCKER_SMOKE", evidence.secretNeedles());
        markdown.append("\n## 失败信息\n\n");
        markdown.append("- errorType：").append(failure == null ? "" : failure.getClass().getSimpleName()).append('\n');
        markdown.append("- message：").append(redact(
                failure == null ? "" : failure.getMessage(),
                evidence.secretNeedles()
        )).append('\n');
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, failedMatrix());
        return write(markdown);
    }

    private StringBuilder header(String conclusion, List<String> secretNeedles) {
        return new StringBuilder()
                .append("# RD-Bot 需求评审阻断生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 requirement review blocker smoke 覆盖的真实阻断证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 PostgreSQL 密码或其他 secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=RequirementReviewBlockerRealSmokeTest \\
                  -Drd.integration.requirement-review-blocker.enabled=true \\
                  -Drd.requirement-review.smoke.production-evidence=true \\
                  -Drd.requirement-review.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.requirement-review.smoke.environment-id=<production-environment-id> \\
                  -Drd.requirement-review.smoke.executed-by=<operator> \\
                  -Drd.requirement-review.smoke.base-url=<rd-bot-base-url> \\
                  -Drd.requirement-review.smoke.postgres-url=<postgres-jdbc-url> \\
                  -Drd.requirement-review.smoke.postgres-user=<postgres-user> \\
                  -Drd.requirement-review.smoke.postgres-password=<postgres-password> \\
                  -Drd.requirement-review.smoke.task-id=<rd-bot-task-id> \\
                  -Drd.requirement-review.smoke.review-artifact-uri=<requirement-review-artifact-uri> \\
                  -Drd.requirement-review.smoke.feishu-alert-message-id=<feishu-message-id> \\
                  -Drd.requirement-review.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, RequirementReviewBlockerSmokeEvidence evidence) {
        markdown.append("\n## 需求评审阻断 Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- stageRunId：").append(oneLine(evidence.stageRunId())).append('\n');
        markdown.append("- taskStatus：").append(oneLine(evidence.taskStatus())).append('\n');
        markdown.append("- executionResultStatus：").append(oneLine(evidence.executionResultStatus())).append('\n');
        markdown.append("- reviewDecision：").append(oneLine(evidence.reviewDecision())).append('\n');
        markdown.append("- reviewArtifactId：").append(oneLine(evidence.reviewArtifactId())).append('\n');
        markdown.append("- reviewArtifactUri：").append(oneLine(evidence.reviewArtifactUri())).append('\n');
        markdown.append("- errorMessage：").append(oneLine(evidence.errorMessage())).append('\n');
        markdown.append("- missingInformation：").append(String.join(", ", evidence.missingInformation())).append('\n');
        markdown.append("- pendingDownstreamRoles：").append(String.join(", ", evidence.pendingDownstreamRoles())).append('\n');
        markdown.append("- downstreamAgentsDispatched：").append(evidence.downstreamAgentsDispatched()).append('\n');
        markdown.append("- feishuAlertDelivered：").append(evidence.feishuAlertDelivered()).append('\n');
        markdown.append("- feishuAlertType：").append(REQUIRED_ALERT_TYPE).append('\n');
        markdown.append("- feishuAlertMessageId：").append(oneLine(evidence.feishuAlertMessageId())).append('\n');
    }

    private void requirePassedEvidence(RequirementReviewBlockerSmokeEvidence evidence) {
        if (!requirementReviewBlockerEvidenceValidated(evidence)) {
            throw new IllegalArgumentException(
                    "requirement review blocker evidence must satisfy the production contract"
            );
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(RequirementReviewBlockerSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "requirement review blocker smoke 未覆盖该验收点，需要生产专项演练。"
        );
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", stageRunId=" + oneLine(evidence.stageRunId())
                + ", decision=" + oneLine(evidence.reviewDecision())
                + ", pendingDownstreamRoles=" + evidence.pendingDownstreamRoles().size();
        statuses.put(3, new MatrixStatus(
                "PASSED",
                evidenceRef + "；真实任务由需求评审阶段阻断，后续 Agent 未派发，Feishu 缺信息告警已记录。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "requirement review blocker smoke 只能作为 #3 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "requirement review blocker smoke 未覆盖该验收点，需要生产专项演练。"
        );
        statuses.put(3, new MatrixStatus(
                "FAILED",
                "requirement review blocker smoke 失败，无法证明缺信息需求被评审 Agent 阻断。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "requirement review blocker smoke 失败，#3 未通过；#15 不能标记为通过。"
        ));
        return statuses;
    }

    private void appendMatrix(StringBuilder markdown, Map<Integer, MatrixStatus> statuses) {
        markdown.append("| # | 验收点 | 结论 | 证据/缺口 |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            MatrixStatus status = statuses.get(point.number());
            markdown.append("| ").append(point.number())
                    .append(" | ").append(point.title())
                    .append(" | ").append(status.status())
                    .append(" | ").append(oneLine(status.evidence()))
                    .append(" |\n");
        }
    }

    private Path write(StringBuilder markdown) throws IOException {
        Files.createDirectories(reportRoot);
        Path reportPath = reportRoot.resolve("requirement-review-blocker-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(Path markdownReportPath, RequirementReviewBlockerSmokeEvidence evidence)
            throws IOException {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("requirementReviewBlockerEvidenceValidated", true);
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("taskId", evidence.taskId());
        json.put("stageRunId", evidence.stageRunId());
        json.put("role", "REQUIREMENT_REVIEWER");
        json.put("taskStatus", evidence.taskStatus());
        json.put("executionResultStatus", evidence.executionResultStatus());
        json.put("reviewDecision", evidence.reviewDecision());
        json.put("reviewArtifactId", evidence.reviewArtifactId());
        json.put("reviewArtifactUri", evidence.reviewArtifactUri());
        json.put("errorMessage", evidence.errorMessage());
        ArrayNode missingInformation = json.putArray("missingInformation");
        evidence.missingInformation().forEach(missingInformation::add);
        ArrayNode pendingRoles = json.putArray("pendingDownstreamRoles");
        evidence.pendingDownstreamRoles().forEach(pendingRoles::add);
        json.put("downstreamAgentsDispatched", evidence.downstreamAgentsDispatched());
        json.put("feishuAlertDelivered", evidence.feishuAlertDelivered());
        json.put("feishuAlertType", REQUIRED_ALERT_TYPE);
        json.put("feishuAlertMessageId", evidence.feishuAlertMessageId());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static boolean requirementReviewBlockerEvidenceValidated(
            RequirementReviewBlockerSmokeEvidence evidence
    ) {
        return evidence != null
                && !evidence.taskId().isBlank()
                && !evidence.stageRunId().isBlank()
                && !evidence.rdBotVersion().isBlank()
                && !evidence.environmentId().isBlank()
                && !evidence.executedBy().isBlank()
                && BLOCKING_TASK_STATUSES.contains(evidence.taskStatus())
                && BLOCKING_EXECUTION_STATUSES.contains(evidence.executionResultStatus())
                && BLOCKING_DECISIONS.contains(evidence.reviewDecision())
                && !evidence.reviewArtifactId().isBlank()
                && ProductionEvidenceUris.isProductionArtifactUri(evidence.reviewArtifactUri())
                && !evidence.errorMessage().isBlank()
                && !evidence.missingInformation().isEmpty()
                && evidence.pendingDownstreamRoles().containsAll(REQUIRED_PENDING_ROLES)
                && !evidence.downstreamAgentsDispatched()
                && evidence.feishuAlertDelivered()
                && !evidence.feishuAlertMessageId().isBlank();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static String redact(String value, List<String> secretNeedles) {
        String redacted = oneLine(value);
        if (secretNeedles == null) {
            return redacted;
        }
        for (String needle : secretNeedles) {
            String normalized = oneLine(needle);
            if (!normalized.isBlank()) {
                redacted = redacted.replace(normalized, "[REDACTED]");
            }
        }
        return redacted;
    }

    record RequirementReviewBlockerSmokeEvidence(
            String taskId,
            String stageRunId,
            String taskStatus,
            String executionResultStatus,
            String reviewDecision,
            String reviewArtifactId,
            String reviewArtifactUri,
            String errorMessage,
            List<String> missingInformation,
            List<String> pendingDownstreamRoles,
            boolean downstreamAgentsDispatched,
            boolean feishuAlertDelivered,
            String feishuAlertMessageId,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<String> secretNeedles
    ) {
        RequirementReviewBlockerSmokeEvidence(
                RequirementReviewBlockerProductionAcceptanceProfile profile,
                String stageRunId,
                String taskStatus,
                String executionResultStatus,
                String reviewDecision,
                String reviewArtifactId,
                String errorMessage,
                List<String> missingInformation,
                List<String> pendingDownstreamRoles,
                boolean downstreamAgentsDispatched
        ) {
            this(
                    profile.taskId(),
                    stageRunId,
                    taskStatus,
                    executionResultStatus,
                    reviewDecision,
                    reviewArtifactId,
                    profile.reviewArtifactUri(),
                    errorMessage,
                    missingInformation,
                    pendingDownstreamRoles,
                    downstreamAgentsDispatched,
                    true,
                    profile.feishuAlertMessageId(),
                    profile.rdBotVersion(),
                    profile.environmentId(),
                    profile.executedBy(),
                    profile.secretScanNeedles()
            );
        }

        RequirementReviewBlockerSmokeEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            taskStatus = oneLine(taskStatus);
            executionResultStatus = oneLine(executionResultStatus);
            reviewDecision = oneLine(reviewDecision);
            reviewArtifactId = oneLine(reviewArtifactId);
            reviewArtifactUri = oneLine(reviewArtifactUri);
            errorMessage = oneLine(errorMessage);
            missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
            pendingDownstreamRoles = pendingDownstreamRoles == null ? List.of() : List.copyOf(pendingDownstreamRoles);
            feishuAlertMessageId = oneLine(feishuAlertMessageId);
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            secretNeedles = secretNeedles == null ? List.of() : List.copyOf(secretNeedles);
        }
    }

    private record AcceptancePoint(int number, String title) {
    }

    private record MatrixStatus(String status, String evidence) {
    }
}
