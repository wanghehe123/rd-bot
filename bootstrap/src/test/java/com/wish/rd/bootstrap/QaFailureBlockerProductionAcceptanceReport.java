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
 * Writes Markdown and JSON evidence for the QA failure blocker production smoke.
 */
final class QaFailureBlockerProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final String REQUIRED_ALERT_TYPE = "QA_FAILED";
    private static final List<String> BLOCKING_EXECUTION_STATUSES = List.of(
            "FAILED",
            "NEEDS_HUMAN",
            "FAILED_NEEDS_HUMAN"
    );
    private static final List<String> BLOCKING_QA_STAGE_STATUSES = List.of(
            "FAILED_NEEDS_HUMAN",
            "FAILED_VALIDATION"
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

    QaFailureBlockerProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static QaFailureBlockerProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.qa-failure.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new QaFailureBlockerProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 QA failure blocker smoke 参数不完整，本次没有访问真实 RD-Bot HTTP 或 PostgreSQL。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 QA failure blocker 前置条件"));
        return write(markdown);
    }

    Path writePassed(QaFailureSmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_QA_FAILURE_BLOCKER_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writeJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(QaFailureSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_QA_FAILURE_BLOCKER_SMOKE", evidence.secretNeedles());
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
                .append("# RD-Bot QA 失败阻断生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 QA failure blocker smoke 覆盖的真实阻断证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 PostgreSQL 密码或其他 secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=QaFailureBlockerRealSmokeTest \\
                  -Drd.integration.qa-failure.enabled=true \\
                  -Drd.qa-failure.smoke.production-evidence=true \\
                  -Drd.qa-failure.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.qa-failure.smoke.environment-id=<production-environment-id> \\
                  -Drd.qa-failure.smoke.executed-by=<operator> \\
                  -Drd.qa-failure.smoke.base-url=<rd-bot-base-url> \\
                  -Drd.qa-failure.smoke.postgres-url=<postgres-jdbc-url> \\
                  -Drd.qa-failure.smoke.postgres-user=<postgres-user> \\
                  -Drd.qa-failure.smoke.postgres-password=<postgres-password> \\
                  -Drd.qa-failure.smoke.task-id=<rd-bot-task-id> \\
                  -Drd.qa-failure.smoke.qa-report-artifact-uri=<qa-report-artifact-uri> \\
                  -Drd.qa-failure.smoke.validation-log-artifact-uris=<validation-log-artifact-uris> \\
                  -Drd.qa-failure.smoke.feishu-alert-message-id=<feishu-message-id> \\
                  -Drd.qa-failure.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, QaFailureSmokeEvidence evidence) {
        markdown.append("\n## QA 失败阻断 Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- stageRunId：").append(oneLine(evidence.stageRunId())).append('\n');
        markdown.append("- taskStatus：").append(oneLine(evidence.taskStatus())).append('\n');
        markdown.append("- qaStageStatus：").append(oneLine(evidence.qaStageStatus())).append('\n');
        markdown.append("- executionResultStatus：").append(oneLine(evidence.executionResultStatus())).append('\n');
        markdown.append("- qaReportArtifactId：").append(oneLine(evidence.qaReportArtifactId())).append('\n');
        markdown.append("- qaReportArtifactUri：").append(oneLine(evidence.qaReportArtifactUri())).append('\n');
        markdown.append("- validationLogArtifactUris：").append(String.join(", ", evidence.validationLogArtifactUris()))
                .append('\n');
        markdown.append("- failedAcceptanceCount：").append(evidence.failedAcceptanceCount()).append('\n');
        markdown.append("- acceptanceResultCount：").append(evidence.acceptanceResultCount()).append('\n');
        markdown.append("- validationCommandCount：").append(evidence.validationCommandCount()).append('\n');
        markdown.append("- validationLogArtifactCount：").append(evidence.validationLogArtifactCount()).append('\n');
        markdown.append("- prCreated：").append(evidence.prCreated()).append('\n');
        markdown.append("- successReportCreated：").append(evidence.successReportCreated()).append('\n');
        markdown.append("- blockedBeforePrCreating：").append(evidence.blockedBeforePrCreating()).append('\n');
        markdown.append("- feishuAlertDelivered：").append(evidence.feishuAlertDelivered()).append('\n');
        markdown.append("- feishuAlertType：").append(REQUIRED_ALERT_TYPE).append('\n');
        markdown.append("- feishuAlertMessageId：").append(oneLine(evidence.feishuAlertMessageId())).append('\n');
    }

    private void requirePassedEvidence(QaFailureSmokeEvidence evidence) {
        if (!qaFailureBlockerEvidenceValidated(evidence)) {
            throw new IllegalArgumentException("QA failure blocker evidence must satisfy the production contract");
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(QaFailureSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "QA failure blocker smoke 未覆盖该验收点，需要生产专项演练。"
        );
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", stageRunId=" + oneLine(evidence.stageRunId())
                + ", failedAcceptanceCount=" + evidence.failedAcceptanceCount();
        statuses.put(7, new MatrixStatus(
                "PASSED",
                evidenceRef + "；真实 QA 阶段失败后阻断交付，PR 和成功报告均未创建，Feishu QA 失败告警已记录。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "QA failure blocker smoke 只能作为 #7 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "QA failure blocker smoke 未覆盖该验收点，需要生产专项演练。"
        );
        statuses.put(7, new MatrixStatus(
                "FAILED",
                "QA failure blocker smoke 失败，无法证明 QA 失败能阻断交付。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "QA failure blocker smoke 失败，#7 未通过；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("qa-failure-blocker-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(Path markdownReportPath, QaFailureSmokeEvidence evidence) throws IOException {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("qaFailureBlockerEvidenceValidated", true);
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("taskId", evidence.taskId());
        json.put("stageRunId", evidence.stageRunId());
        json.put("role", "QA_AGENT");
        json.put("taskStatus", evidence.taskStatus());
        json.put("qaStageStatus", evidence.qaStageStatus());
        json.put("executionResultStatus", evidence.executionResultStatus());
        json.put("qaReportArtifactId", evidence.qaReportArtifactId());
        json.put("qaReportArtifactUri", evidence.qaReportArtifactUri());
        ArrayNode validationLogUris = json.putArray("validationLogArtifactUris");
        evidence.validationLogArtifactUris().forEach(validationLogUris::add);
        json.put("failedAcceptanceCount", evidence.failedAcceptanceCount());
        json.put("acceptanceResultCount", evidence.acceptanceResultCount());
        json.put("validationCommandCount", evidence.validationCommandCount());
        json.put("validationLogArtifactCount", evidence.validationLogArtifactCount());
        json.put("prCreated", evidence.prCreated());
        json.put("successReportCreated", evidence.successReportCreated());
        json.put("blockedBeforePrCreating", evidence.blockedBeforePrCreating());
        json.put("feishuAlertDelivered", evidence.feishuAlertDelivered());
        json.put("feishuAlertType", REQUIRED_ALERT_TYPE);
        json.put("feishuAlertMessageId", evidence.feishuAlertMessageId());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static boolean qaFailureBlockerEvidenceValidated(QaFailureSmokeEvidence evidence) {
        return evidence != null
                && !evidence.taskId().isBlank()
                && !evidence.stageRunId().isBlank()
                && !evidence.rdBotVersion().isBlank()
                && !evidence.environmentId().isBlank()
                && !evidence.executedBy().isBlank()
                && "FAILED_NEEDS_HUMAN".equals(evidence.taskStatus())
                && BLOCKING_QA_STAGE_STATUSES.contains(evidence.qaStageStatus())
                && BLOCKING_EXECUTION_STATUSES.contains(evidence.executionResultStatus())
                && !evidence.qaReportArtifactId().isBlank()
                && ProductionEvidenceUris.isProductionArtifactUri(evidence.qaReportArtifactUri())
                && productionLogUrisCover(evidence.validationLogArtifactUris(), evidence.acceptanceResultCount())
                && evidence.failedAcceptanceCount() > 0
                && evidence.acceptanceResultCount() >= evidence.failedAcceptanceCount()
                && evidence.validationCommandCount() >= evidence.acceptanceResultCount()
                && evidence.validationLogArtifactCount() >= evidence.acceptanceResultCount()
                && !evidence.prCreated()
                && !evidence.successReportCreated()
                && evidence.blockedBeforePrCreating()
                && evidence.feishuAlertDelivered()
                && !evidence.feishuAlertMessageId().isBlank();
    }

    private static boolean productionLogUrisCover(List<String> values, int acceptanceResultCount) {
        return values.size() >= acceptanceResultCount
                && values.stream().allMatch(ProductionEvidenceUris::isProductionArtifactUri)
                && values.stream().distinct().count() == values.size();
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

    record QaFailureSmokeEvidence(
            String taskId,
            String stageRunId,
            String taskStatus,
            String qaStageStatus,
            String executionResultStatus,
            String qaReportArtifactId,
            String qaReportArtifactUri,
            List<String> validationLogArtifactUris,
            int failedAcceptanceCount,
            int acceptanceResultCount,
            int validationCommandCount,
            int validationLogArtifactCount,
            boolean prCreated,
            boolean successReportCreated,
            boolean blockedBeforePrCreating,
            boolean feishuAlertDelivered,
            String feishuAlertMessageId,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<String> secretNeedles
    ) {
        QaFailureSmokeEvidence(
                QaFailureBlockerProductionAcceptanceProfile profile,
                String stageRunId,
                String taskStatus,
                String qaStageStatus,
                String executionResultStatus,
                String qaReportArtifactId,
                int failedAcceptanceCount,
                int acceptanceResultCount,
                int validationCommandCount,
                boolean prCreated,
                boolean successReportCreated,
                boolean blockedBeforePrCreating
        ) {
            this(
                    profile.taskId(),
                    stageRunId,
                    taskStatus,
                    qaStageStatus,
                    executionResultStatus,
                    qaReportArtifactId,
                    profile.qaReportArtifactUri(),
                    profile.validationLogArtifactUris(),
                    failedAcceptanceCount,
                    acceptanceResultCount,
                    validationCommandCount,
                    profile.validationLogArtifactUris().size(),
                    prCreated,
                    successReportCreated,
                    blockedBeforePrCreating,
                    true,
                    profile.feishuAlertMessageId(),
                    profile.rdBotVersion(),
                    profile.environmentId(),
                    profile.executedBy(),
                    profile.secretScanNeedles()
            );
        }

        QaFailureSmokeEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            taskStatus = oneLine(taskStatus);
            qaStageStatus = oneLine(qaStageStatus);
            executionResultStatus = oneLine(executionResultStatus);
            qaReportArtifactId = oneLine(qaReportArtifactId);
            qaReportArtifactUri = oneLine(qaReportArtifactUri);
            validationLogArtifactUris = validationLogArtifactUris == null
                    ? List.of()
                    : List.copyOf(validationLogArtifactUris);
            failedAcceptanceCount = Math.max(failedAcceptanceCount, 0);
            acceptanceResultCount = Math.max(acceptanceResultCount, 0);
            validationCommandCount = Math.max(validationCommandCount, 0);
            validationLogArtifactCount = Math.max(validationLogArtifactCount, 0);
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
