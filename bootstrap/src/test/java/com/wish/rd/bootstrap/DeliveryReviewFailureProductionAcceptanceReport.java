package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Writes Markdown and JSON evidence for the delivery-review failure production smoke.
 */
final class DeliveryReviewFailureProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final String REQUIRED_ALERT_TYPE = "DELIVERY_REVIEW_FAILED";
    private static final List<String> REJECTION_DECISIONS = List.of(
            "REJECTED",
            "FAILED",
            "BLOCKED",
            "NEEDS_HUMAN"
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

    DeliveryReviewFailureProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static DeliveryReviewFailureProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.delivery-review-failure.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new DeliveryReviewFailureProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 delivery review failure smoke 参数不完整，本次没有访问真实 RD-Bot HTTP 或 PostgreSQL。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 delivery review failure 前置条件"));
        return write(markdown);
    }

    Path writePassed(DeliveryReviewFailureSmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_DELIVERY_REVIEW_FAILURE_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writeJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(DeliveryReviewFailureSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_DELIVERY_REVIEW_FAILURE_SMOKE", evidence.secretNeedles());
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
                .append("# RD-Bot 交付复核失败生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 delivery review failure smoke 覆盖的真实阻断证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 PostgreSQL 密码或其他 secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=DeliveryReviewFailureRealSmokeTest \\
                  -Drd.integration.delivery-review-failure.enabled=true \\
                  -Drd.delivery-review-failure.smoke.production-evidence=true \\
                  -Drd.delivery-review-failure.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.delivery-review-failure.smoke.environment-id=<production-environment-id> \\
                  -Drd.delivery-review-failure.smoke.executed-by=<operator> \\
                  -Drd.delivery-review-failure.smoke.base-url=<rd-bot-base-url> \\
                  -Drd.delivery-review-failure.smoke.postgres-url=<postgres-jdbc-url> \\
                  -Drd.delivery-review-failure.smoke.postgres-user=<postgres-user> \\
                  -Drd.delivery-review-failure.smoke.postgres-password=<postgres-password> \\
                  -Drd.delivery-review-failure.smoke.task-id=<rd-bot-task-id> \\
                  -Drd.delivery-review-failure.smoke.review-artifact-uri=<delivery-review-artifact-uri> \\
                  -Drd.delivery-review-failure.smoke.feishu-alert-message-id=<feishu-message-id> \\
                  -Drd.delivery-review-failure.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, DeliveryReviewFailureSmokeEvidence evidence) {
        markdown.append("\n## 交付复核失败 Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- taskStatus：").append(oneLine(evidence.taskStatus())).append('\n');
        markdown.append("- deliveryReviewApproved：").append(evidence.deliveryReviewApproved()).append('\n');
        markdown.append("- reviewDecision：").append(oneLine(evidence.reviewDecision())).append('\n');
        markdown.append("- reviewer：").append(oneLine(evidence.reviewer())).append('\n');
        markdown.append("- reviewArtifactId：").append(oneLine(evidence.reviewArtifactId())).append('\n');
        markdown.append("- reviewArtifactUri：").append(oneLine(evidence.reviewArtifactUri())).append('\n');
        markdown.append("- rejectionReason：").append(oneLine(evidence.rejectionReason())).append('\n');
        markdown.append("- pullRequestPublicationAttempted：").append(evidence.pullRequestPublicationAttempted()).append('\n');
        markdown.append("- prCreated：").append(evidence.prCreated()).append('\n');
        markdown.append("- successReportCreated：").append(evidence.successReportCreated()).append('\n');
        markdown.append("- failureReportCreated：").append(evidence.failureReportCreated()).append('\n');
        markdown.append("- successDeliveryReportExperienceCreated：")
                .append(evidence.successDeliveryReportExperienceCreated())
                .append('\n');
        markdown.append("- blockedBeforePrCreating：").append(evidence.blockedBeforePrCreating()).append('\n');
        markdown.append("- stagePullRequestUrlRejected：").append(evidence.stagePullRequestUrlRejected()).append('\n');
        markdown.append("- feishuAlertDelivered：").append(evidence.feishuAlertDelivered()).append('\n');
        markdown.append("- feishuAlertType：").append(REQUIRED_ALERT_TYPE).append('\n');
        markdown.append("- feishuAlertMessageId：").append(oneLine(evidence.feishuAlertMessageId())).append('\n');
    }

    private void requirePassedEvidence(DeliveryReviewFailureSmokeEvidence evidence) {
        if (!deliveryReviewFailureEvidenceValidated(evidence)) {
            throw new IllegalArgumentException(
                    "delivery review failure evidence must satisfy the production contract"
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

    private Map<Integer, MatrixStatus> passedMatrix(DeliveryReviewFailureSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "delivery review failure smoke 未覆盖该验收点，需要生产专项演练。"
        );
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", decision=" + oneLine(evidence.reviewDecision())
                + ", stagePullRequestUrlRejected=" + evidence.stagePullRequestUrlRejected();
        statuses.put(8, new MatrixStatus(
                "PASSED",
                evidenceRef + "；真实交付复核拒绝后未发布 PR，失败报告已记录，Feishu 复核失败告警已送达。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "delivery review failure smoke 只能作为 #8 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "delivery review failure smoke 未覆盖该验收点，需要生产专项演练。"
        );
        statuses.put(8, new MatrixStatus(
                "FAILED",
                "delivery review failure smoke 失败，无法证明交付复核失败会阻断 PR 发布。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "delivery review failure smoke 失败，#8 未通过；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("delivery-review-failure-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(Path markdownReportPath, DeliveryReviewFailureSmokeEvidence evidence) throws IOException {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("deliveryReviewFailureEvidenceValidated", true);
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("taskId", evidence.taskId());
        json.put("taskStatus", evidence.taskStatus());
        json.put("deliveryReviewApproved", evidence.deliveryReviewApproved());
        json.put("reviewDecision", evidence.reviewDecision());
        json.put("reviewer", evidence.reviewer());
        json.put("reviewArtifactId", evidence.reviewArtifactId());
        json.put("reviewArtifactUri", evidence.reviewArtifactUri());
        json.put("rejectionReason", evidence.rejectionReason());
        json.put("pullRequestPublicationAttempted", evidence.pullRequestPublicationAttempted());
        json.put("prCreated", evidence.prCreated());
        json.put("successReportCreated", evidence.successReportCreated());
        json.put("failureReportCreated", evidence.failureReportCreated());
        json.put("successDeliveryReportExperienceCreated", evidence.successDeliveryReportExperienceCreated());
        json.put("blockedBeforePrCreating", evidence.blockedBeforePrCreating());
        json.put("stagePullRequestUrlRejected", evidence.stagePullRequestUrlRejected());
        json.put("feishuAlertDelivered", evidence.feishuAlertDelivered());
        json.put("feishuAlertType", REQUIRED_ALERT_TYPE);
        json.put("feishuAlertMessageId", evidence.feishuAlertMessageId());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static boolean deliveryReviewFailureEvidenceValidated(DeliveryReviewFailureSmokeEvidence evidence) {
        return evidence != null
                && !evidence.taskId().isBlank()
                && !evidence.rdBotVersion().isBlank()
                && !evidence.environmentId().isBlank()
                && !evidence.executedBy().isBlank()
                && "REJECTED".equals(evidence.taskStatus())
                && !evidence.deliveryReviewApproved()
                && REJECTION_DECISIONS.contains(evidence.reviewDecision())
                && "DELIVERY_REVIEWER".equals(evidence.reviewer())
                && !evidence.reviewArtifactId().isBlank()
                && ProductionEvidenceUris.isProductionArtifactUri(evidence.reviewArtifactUri())
                && !evidence.rejectionReason().isBlank()
                && !evidence.pullRequestPublicationAttempted()
                && !evidence.prCreated()
                && !evidence.successReportCreated()
                && evidence.failureReportCreated()
                && !evidence.successDeliveryReportExperienceCreated()
                && evidence.blockedBeforePrCreating()
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

    record DeliveryReviewFailureSmokeEvidence(
            String taskId,
            String taskStatus,
            boolean deliveryReviewApproved,
            String reviewDecision,
            String reviewer,
            String reviewArtifactId,
            String reviewArtifactUri,
            String rejectionReason,
            boolean pullRequestPublicationAttempted,
            boolean prCreated,
            boolean successReportCreated,
            boolean failureReportCreated,
            boolean successDeliveryReportExperienceCreated,
            boolean blockedBeforePrCreating,
            boolean stagePullRequestUrlRejected,
            boolean feishuAlertDelivered,
            String feishuAlertMessageId,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<String> secretNeedles
    ) {
        DeliveryReviewFailureSmokeEvidence(
                DeliveryReviewFailureProductionAcceptanceProfile profile,
                String taskStatus,
                boolean deliveryReviewApproved,
                String reviewDecision,
                String reviewArtifactId,
                String rejectionReason,
                boolean pullRequestPublicationAttempted,
                boolean prCreated,
                boolean successReportCreated,
                boolean failureReportCreated,
                boolean successDeliveryReportExperienceCreated,
                boolean blockedBeforePrCreating,
                boolean stagePullRequestUrlRejected
        ) {
            this(
                    profile.taskId(),
                    taskStatus,
                    deliveryReviewApproved,
                    reviewDecision,
                    "DELIVERY_REVIEWER",
                    reviewArtifactId,
                    profile.reviewArtifactUri(),
                    rejectionReason,
                    pullRequestPublicationAttempted,
                    prCreated,
                    successReportCreated,
                    failureReportCreated,
                    successDeliveryReportExperienceCreated,
                    blockedBeforePrCreating,
                    stagePullRequestUrlRejected,
                    true,
                    profile.feishuAlertMessageId(),
                    profile.rdBotVersion(),
                    profile.environmentId(),
                    profile.executedBy(),
                    profile.secretScanNeedles()
            );
        }

        DeliveryReviewFailureSmokeEvidence {
            taskId = oneLine(taskId);
            taskStatus = oneLine(taskStatus);
            reviewDecision = oneLine(reviewDecision);
            reviewer = oneLine(reviewer);
            reviewArtifactId = oneLine(reviewArtifactId);
            reviewArtifactUri = oneLine(reviewArtifactUri);
            rejectionReason = oneLine(rejectionReason);
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
