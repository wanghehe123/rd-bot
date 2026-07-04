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
 * Writes Markdown and JSON evidence for the observability metrics production smoke.
 */
final class ObservabilityMetricsProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
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

    ObservabilityMetricsProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static ObservabilityMetricsProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.observability-metrics.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new ObservabilityMetricsProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 observability metrics smoke 参数不完整，本次没有访问真实 RD-Bot HTTP、Prometheus 指标接口或 PostgreSQL。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 observability metrics 前置条件"));
        return write(markdown);
    }

    Path writePassed(ObservabilityMetricsSmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_OBSERVABILITY_METRICS_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writeJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(ObservabilityMetricsSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_OBSERVABILITY_METRICS_SMOKE", evidence.secretNeedles());
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
                .append("# RD-Bot Observability Metrics 生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 observability metrics smoke 覆盖的真实指标和审计链证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 PostgreSQL 密码或其他 secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=ObservabilityMetricsRealSmokeTest \\
                  -Drd.integration.observability-metrics.enabled=true \\
                  -Drd.observability-metrics.smoke.production-evidence=true \\
                  -Drd.observability-metrics.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.observability-metrics.smoke.environment-id=<production-environment-id> \\
                  -Drd.observability-metrics.smoke.executed-by=<operator> \\
                  -Drd.observability-metrics.smoke.base-url=<rd-bot-base-url> \\
                  -Drd.observability-metrics.smoke.postgres-url=<postgres-jdbc-url> \\
                  -Drd.observability-metrics.smoke.postgres-user=<postgres-user> \\
                  -Drd.observability-metrics.smoke.postgres-password=<postgres-password> \\
                  -Drd.observability-metrics.smoke.task-id=<same-main-task-id> \\
                  -Drd.observability-metrics.smoke.github-pr-remote-evidence-json=<github-pr-remote-evidence-json> \\
                  -Drd.observability-metrics.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, ObservabilityMetricsSmokeEvidence evidence) {
        markdown.append("\n## Observability Metrics Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- metricsEndpointUrl：").append(oneLine(evidence.metricsEndpointUrl())).append('\n');
        markdown.append("- metricsHttpStatus：").append(evidence.metricsHttpStatus()).append('\n');
        markdown.append("- contextBuildLatencyMetricPresent：").append(evidence.contextBuildLatencyMetricPresent()).append('\n');
        markdown.append("- repairSuccessRateMetricPresent：").append(evidence.repairSuccessRateMetricPresent()).append('\n');
        markdown.append("- validationPassRateMetricPresent：").append(evidence.validationPassRateMetricPresent()).append('\n');
        markdown.append("- prCreationRateMetricPresent：").append(evidence.prCreationRateMetricPresent()).append('\n');
        markdown.append("- humanInterventionRateMetricPresent：").append(evidence.humanInterventionRateMetricPresent()).append('\n');
        markdown.append("- retryRateMetricPresent：").append(evidence.retryRateMetricPresent()).append('\n');
        markdown.append("- meanTimeToRepairMetricPresent：").append(evidence.meanTimeToRepairMetricPresent()).append('\n');
        markdown.append("- topFailureCategoriesMetricPresent：").append(evidence.topFailureCategoriesMetricPresent()).append('\n');
        markdown.append("- stageMetricCount：").append(evidence.stageMetricCount()).append('\n');
        markdown.append("- auditTraceQuerySucceeded：").append(evidence.auditTraceQuerySucceeded()).append('\n');
        markdown.append("- remotePrTraceValidated：").append(evidence.remotePrTraceValidated()).append('\n');
        markdown.append("- auditTraceLinkCount：").append(evidence.auditTraceLinkCount()).append('\n');
        markdown.append("- taskBoundAuditTraceLinkCount：").append(evidence.taskBoundAuditTraceLinkCount()).append('\n');
    }

    private void requirePassedEvidence(ObservabilityMetricsSmokeEvidence evidence) {
        if (!observabilityMetricsEvidenceValidated(evidence)) {
            throw new IllegalArgumentException("Observability metrics evidence must satisfy the production contract");
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(ObservabilityMetricsSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "Observability metrics smoke 未覆盖该验收点，需要生产专项演练。"
        );
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", metricsEndpointUrl=" + oneLine(evidence.metricsEndpointUrl())
                + ", auditTraceLinkCount=" + evidence.auditTraceLinkCount()
                + ", taskBoundAuditTraceLinkCount=" + evidence.taskBoundAuditTraceLinkCount();
        statuses.put(14, new MatrixStatus(
                "PASSED",
                evidenceRef + "；真实指标接口和同 taskId 审计链验证通过。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "Observability metrics smoke 只能作为 #14 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "Observability metrics smoke 未覆盖该验收点，需要生产专项演练。"
        );
        statuses.put(14, new MatrixStatus(
                "FAILED",
                "Observability metrics smoke 失败，无法证明指标和审计链真实可观测。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "Observability metrics smoke 失败，#14 未通过；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("observability-metrics-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(Path markdownReportPath, ObservabilityMetricsSmokeEvidence evidence) throws IOException {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("observabilityMetricsEvidenceValidated", true);
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("observabilityTaskId", evidence.taskId());
        json.put("taskId", evidence.taskId());
        json.put("metricsEndpointUrl", evidence.metricsEndpointUrl());
        json.put("metricsHttpStatus", evidence.metricsHttpStatus());
        json.put("contextBuildLatencyMetricPresent", evidence.contextBuildLatencyMetricPresent());
        json.put("repairSuccessRateMetricPresent", evidence.repairSuccessRateMetricPresent());
        json.put("validationPassRateMetricPresent", evidence.validationPassRateMetricPresent());
        json.put("prCreationRateMetricPresent", evidence.prCreationRateMetricPresent());
        json.put("humanInterventionRateMetricPresent", evidence.humanInterventionRateMetricPresent());
        json.put("retryRateMetricPresent", evidence.retryRateMetricPresent());
        json.put("meanTimeToRepairMetricPresent", evidence.meanTimeToRepairMetricPresent());
        json.put("topFailureCategoriesMetricPresent", evidence.topFailureCategoriesMetricPresent());
        json.put("stageMetricCount", evidence.stageMetricCount());
        json.put("auditTraceQuerySucceeded", evidence.auditTraceQuerySucceeded());
        json.put("remotePrTraceValidated", evidence.remotePrTraceValidated());
        json.put("auditTraceLinkCount", evidence.auditTraceLinkCount());
        json.put("taskBoundAuditTraceLinkCount", evidence.taskBoundAuditTraceLinkCount());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static boolean observabilityMetricsEvidenceValidated(ObservabilityMetricsSmokeEvidence evidence) {
        return evidence != null
                && !evidence.taskId().isBlank()
                && !evidence.rdBotVersion().isBlank()
                && !evidence.environmentId().isBlank()
                && !evidence.executedBy().isBlank()
                && !evidence.metricsEndpointUrl().isBlank()
                && evidence.metricsHttpStatus() == 200
                && evidence.contextBuildLatencyMetricPresent()
                && evidence.repairSuccessRateMetricPresent()
                && evidence.validationPassRateMetricPresent()
                && evidence.prCreationRateMetricPresent()
                && evidence.humanInterventionRateMetricPresent()
                && evidence.retryRateMetricPresent()
                && evidence.meanTimeToRepairMetricPresent()
                && evidence.topFailureCategoriesMetricPresent()
                && evidence.stageMetricCount() >= 4
                && evidence.auditTraceQuerySucceeded()
                && evidence.remotePrTraceValidated()
                && evidence.auditTraceLinkCount() >= 8
                && evidence.taskBoundAuditTraceLinkCount() >= 8
                && evidence.taskBoundAuditTraceLinkCount() <= evidence.auditTraceLinkCount();
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

    record ObservabilityMetricsSmokeEvidence(
            String taskId,
            String metricsEndpointUrl,
            int metricsHttpStatus,
            boolean contextBuildLatencyMetricPresent,
            boolean repairSuccessRateMetricPresent,
            boolean validationPassRateMetricPresent,
            boolean prCreationRateMetricPresent,
            boolean humanInterventionRateMetricPresent,
            boolean retryRateMetricPresent,
            boolean meanTimeToRepairMetricPresent,
            boolean topFailureCategoriesMetricPresent,
            int stageMetricCount,
            boolean auditTraceQuerySucceeded,
            boolean remotePrTraceValidated,
            int auditTraceLinkCount,
            int taskBoundAuditTraceLinkCount,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<String> secretNeedles
    ) {
        ObservabilityMetricsSmokeEvidence(
                ObservabilityMetricsProductionAcceptanceProfile profile,
                int metricsHttpStatus,
                boolean contextBuildLatencyMetricPresent,
                boolean repairSuccessRateMetricPresent,
                boolean validationPassRateMetricPresent,
                boolean prCreationRateMetricPresent,
                boolean humanInterventionRateMetricPresent,
                boolean retryRateMetricPresent,
                boolean meanTimeToRepairMetricPresent,
                boolean topFailureCategoriesMetricPresent,
                int stageMetricCount,
                boolean auditTraceQuerySucceeded,
                boolean remotePrTraceValidated,
                int auditTraceLinkCount,
                int taskBoundAuditTraceLinkCount
        ) {
            this(
                    profile.taskId(),
                    profile.metricsEndpointUrl(),
                    metricsHttpStatus,
                    contextBuildLatencyMetricPresent,
                    repairSuccessRateMetricPresent,
                    validationPassRateMetricPresent,
                    prCreationRateMetricPresent,
                    humanInterventionRateMetricPresent,
                    retryRateMetricPresent,
                    meanTimeToRepairMetricPresent,
                    topFailureCategoriesMetricPresent,
                    stageMetricCount,
                    auditTraceQuerySucceeded,
                    remotePrTraceValidated,
                    auditTraceLinkCount,
                    taskBoundAuditTraceLinkCount,
                    profile.rdBotVersion(),
                    profile.environmentId(),
                    profile.executedBy(),
                    profile.secretScanNeedles()
            );
        }

        ObservabilityMetricsSmokeEvidence {
            taskId = oneLine(taskId);
            metricsEndpointUrl = oneLine(metricsEndpointUrl);
            metricsHttpStatus = Math.max(metricsHttpStatus, 0);
            stageMetricCount = Math.max(stageMetricCount, 0);
            auditTraceLinkCount = Math.max(auditTraceLinkCount, 0);
            taskBoundAuditTraceLinkCount = Math.max(taskBoundAuditTraceLinkCount, 0);
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
