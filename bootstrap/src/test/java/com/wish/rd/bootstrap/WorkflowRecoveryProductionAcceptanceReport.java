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
 * Writes Markdown and JSON evidence for the workflow recovery production smoke.
 */
final class WorkflowRecoveryProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final List<String> REQUIRED_TIMELINE_ORDER = List.of(
            "EXECUTING",
            "VALIDATING",
            "PR_CREATING",
            "COMMITTED",
            "REPORTING",
            "COMPLETED"
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

    WorkflowRecoveryProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static WorkflowRecoveryProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.workflow.recovery.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new WorkflowRecoveryProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 workflow recovery smoke 参数不完整，本次没有访问真实 RD-Bot HTTP 或 PostgreSQL。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 workflow recovery 前置条件"));
        return write(markdown);
    }

    Path writePassed(WorkflowRecoverySmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_WORKFLOW_RECOVERY_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writeJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(WorkflowRecoverySmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_WORKFLOW_RECOVERY_SMOKE", evidence.secretNeedles());
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
                .append("# RD-Bot Workflow Recovery 生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 workflow recovery smoke 覆盖的真实恢复证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 PostgreSQL 密码或其他 secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=WorkflowRecoveryRealSmokeTest \\
                  -Drd.integration.workflow-recovery.enabled=true \\
                  -Drd.workflow.recovery.smoke.production-evidence=true \\
                  -Drd.workflow.recovery.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.workflow.recovery.smoke.environment-id=<production-environment-id> \\
                  -Drd.workflow.recovery.smoke.executed-by=<operator> \\
                  -Drd.workflow.recovery.smoke.base-url=<rd-bot-base-url> \\
                  -Drd.workflow.recovery.smoke.postgres-url=<postgres-jdbc-url> \\
                  -Drd.workflow.recovery.smoke.postgres-user=<postgres-user> \\
                  -Drd.workflow.recovery.smoke.postgres-password=<postgres-password> \\
                  -Drd.workflow.recovery.smoke.task-id=<rd-bot-task-id> \\
                  -Drd.workflow.recovery.smoke.stage-run-count-before-restart=<count-before-restart> \\
                  -Drd.workflow.recovery.smoke.stage-event-count-before-restart=<count-before-restart> \\
                  -Drd.workflow.recovery.smoke.retry-attempt-count=<retry-attempt-count> \\
                  -Drd.workflow.recovery.smoke.retained-retry-artifact-count=<retry-artifact-count> \\
                  -Drd.workflow.recovery.smoke.startup-log-evidence-uri=<startup-log-artifact-uri> \\
                  -Drd.workflow.recovery.smoke.database-snapshot-evidence-uri=<database-snapshot-artifact-uri> \\
                  -Drd.workflow.recovery.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, WorkflowRecoverySmokeEvidence evidence) {
        markdown.append("\n## Workflow Recovery Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- workflowRecoveryEvidenceValidated：").append(workflowRecoveryEvidenceValidated(evidence)).append('\n');
        markdown.append("- stageRunCountBeforeRestart：").append(evidence.stageRunCountBeforeRestart()).append('\n');
        markdown.append("- stageRunCountAfterRestart：").append(evidence.stageRunCountAfterRestart()).append('\n');
        markdown.append("- stageEventCountBeforeRestart：").append(evidence.stageEventCountBeforeRestart()).append('\n');
        markdown.append("- stageEventCountAfterRestart：").append(evidence.stageEventCountAfterRestart()).append('\n');
        markdown.append("- duplicateSuccessfulStageCount：").append(evidence.duplicateSuccessfulStageCount()).append('\n');
        markdown.append("- retryAttemptCount：").append(evidence.retryAttemptCount()).append('\n');
        markdown.append("- retainedRetryArtifactCount：").append(evidence.retainedRetryArtifactCount()).append('\n');
        markdown.append("- deliveryReviewApprovedBeforePrCreating：")
                .append(evidence.deliveryReviewApprovedBeforePrCreating())
                .append('\n');
        markdown.append("- timelineStatuses：").append(String.join(", ", evidence.timelineStatuses())).append('\n');
        markdown.append("- startupLogEvidenceUri：").append(oneLine(evidence.startupLogEvidenceUri())).append('\n');
        markdown.append("- databaseSnapshotEvidenceUri：")
                .append(oneLine(evidence.databaseSnapshotEvidenceUri()))
                .append('\n');
    }

    private void requirePassedEvidence(WorkflowRecoverySmokeEvidence evidence) {
        if (!workflowRecoveryEvidenceValidated(evidence)) {
            throw new IllegalArgumentException("workflow recovery evidence must satisfy the production contract");
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(WorkflowRecoverySmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "workflow recovery smoke 未覆盖该验收点，需要生产专项演练。"
        );
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", stageRunsBefore=" + evidence.stageRunCountBeforeRestart()
                + ", stageRunsAfter=" + evidence.stageRunCountAfterRestart()
                + ", duplicateSuccessfulStageCount=" + evidence.duplicateSuccessfulStageCount();
        statuses.put(9, new MatrixStatus(
                "PASSED",
                evidenceRef + "；真实重启前后快照证明阶段运行和事件只增不减，成功阶段没有重复派发。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "workflow recovery smoke 只能作为 #9 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "workflow recovery smoke 未覆盖该验收点，需要生产专项演练。"
        );
        statuses.put(9, new MatrixStatus(
                "FAILED",
                "workflow recovery smoke 失败，无法证明重启恢复、幂等或交付顺序。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "workflow recovery smoke 失败，#9 未通过；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("workflow-recovery-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(Path markdownReportPath, WorkflowRecoverySmokeEvidence evidence) throws IOException {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("workflowRecoveryEvidenceValidated", true);
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("recoveryTaskId", evidence.taskId());
        json.put("taskId", evidence.taskId());
        json.put("stageRunCountBeforeRestart", evidence.stageRunCountBeforeRestart());
        json.put("stageRunCountAfterRestart", evidence.stageRunCountAfterRestart());
        json.put("stageEventCountBeforeRestart", evidence.stageEventCountBeforeRestart());
        json.put("stageEventCountAfterRestart", evidence.stageEventCountAfterRestart());
        json.put("duplicateSuccessfulStageCount", evidence.duplicateSuccessfulStageCount());
        json.put("retryAttemptCount", evidence.retryAttemptCount());
        json.put("retainedRetryArtifactCount", evidence.retainedRetryArtifactCount());
        json.put("deliveryReviewApprovedBeforePrCreating", evidence.deliveryReviewApprovedBeforePrCreating());
        ArrayNode statuses = json.putArray("timelineStatuses");
        evidence.timelineStatuses().forEach(statuses::add);
        json.put("startupLogEvidenceUri", evidence.startupLogEvidenceUri());
        json.put("databaseSnapshotEvidenceUri", evidence.databaseSnapshotEvidenceUri());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static boolean workflowRecoveryEvidenceValidated(WorkflowRecoverySmokeEvidence evidence) {
        return evidence != null
                && !evidence.taskId().isBlank()
                && !evidence.rdBotVersion().isBlank()
                && !evidence.environmentId().isBlank()
                && !evidence.executedBy().isBlank()
                && evidence.stageRunCountBeforeRestart() > 0
                && evidence.stageRunCountAfterRestart() >= 4
                && evidence.stageRunCountAfterRestart() >= evidence.stageRunCountBeforeRestart()
                && evidence.stageEventCountBeforeRestart() > 0
                && evidence.stageEventCountAfterRestart() >= evidence.stageEventCountBeforeRestart()
                && evidence.duplicateSuccessfulStageCount() == 0
                && evidence.retryAttemptCount() >= 0
                && evidence.retainedRetryArtifactCount() >= 0
                && evidence.deliveryReviewApprovedBeforePrCreating()
                && containsInOrder(evidence.timelineStatuses(), REQUIRED_TIMELINE_ORDER)
                && ProductionEvidenceUris.isProductionArtifactUri(evidence.startupLogEvidenceUri())
                && ProductionEvidenceUris.isProductionArtifactUri(evidence.databaseSnapshotEvidenceUri());
    }

    private static boolean containsInOrder(List<String> actual, List<String> expected) {
        int cursor = 0;
        for (String status : actual) {
            if (cursor < expected.size() && expected.get(cursor).equals(status)) {
                cursor++;
            }
        }
        if (cursor != expected.size()) {
            return false;
        }
        int previous = -1;
        for (String status : expected) {
            int current = actual.indexOf(status);
            if (current <= previous) {
                return false;
            }
            previous = current;
        }
        return true;
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

    record WorkflowRecoverySmokeEvidence(
            String taskId,
            int stageRunCountBeforeRestart,
            int stageRunCountAfterRestart,
            int stageEventCountBeforeRestart,
            int stageEventCountAfterRestart,
            int duplicateSuccessfulStageCount,
            int retryAttemptCount,
            int retainedRetryArtifactCount,
            boolean deliveryReviewApprovedBeforePrCreating,
            List<String> timelineStatuses,
            String startupLogEvidenceUri,
            String databaseSnapshotEvidenceUri,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<String> secretNeedles
    ) {
        WorkflowRecoverySmokeEvidence(
                WorkflowRecoveryProductionAcceptanceProfile profile,
                int stageRunCountAfterRestart,
                int stageEventCountAfterRestart,
                int duplicateSuccessfulStageCount,
                boolean deliveryReviewApprovedBeforePrCreating,
                List<String> timelineStatuses
        ) {
            this(
                    profile.taskId(),
                    profile.stageRunCountBeforeRestart(),
                    stageRunCountAfterRestart,
                    profile.stageEventCountBeforeRestart(),
                    stageEventCountAfterRestart,
                    duplicateSuccessfulStageCount,
                    profile.retryAttemptCount(),
                    profile.retainedRetryArtifactCount(),
                    deliveryReviewApprovedBeforePrCreating,
                    timelineStatuses,
                    profile.startupLogEvidenceUri(),
                    profile.databaseSnapshotEvidenceUri(),
                    profile.rdBotVersion(),
                    profile.environmentId(),
                    profile.executedBy(),
                    profile.secretScanNeedles()
            );
        }

        WorkflowRecoverySmokeEvidence {
            taskId = oneLine(taskId);
            stageRunCountBeforeRestart = Math.max(stageRunCountBeforeRestart, 0);
            stageRunCountAfterRestart = Math.max(stageRunCountAfterRestart, 0);
            stageEventCountBeforeRestart = Math.max(stageEventCountBeforeRestart, 0);
            stageEventCountAfterRestart = Math.max(stageEventCountAfterRestart, 0);
            duplicateSuccessfulStageCount = Math.max(duplicateSuccessfulStageCount, 0);
            retryAttemptCount = Math.max(retryAttemptCount, 0);
            retainedRetryArtifactCount = Math.max(retainedRetryArtifactCount, 0);
            timelineStatuses = timelineStatuses == null ? List.of() : List.copyOf(timelineStatuses);
            startupLogEvidenceUri = oneLine(startupLogEvidenceUri);
            databaseSnapshotEvidenceUri = oneLine(databaseSnapshotEvidenceUri);
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
