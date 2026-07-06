package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Markdown evidence for the Feishu alert production smoke.
 */
final class FeishuAlertProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final List<String> REQUIRED_ALERT_TYPES = List.of(
            RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
            RepairAlertType.STAGE_FAILED_NEEDS_HUMAN.name(),
            RepairAlertType.PROVIDER_FALLBACK.name(),
            RepairAlertType.QA_FAILED.name(),
            RepairAlertType.DELIVERY_REVIEW_FAILED.name(),
            RepairAlertType.PR_PUBLICATION_FAILED.name()
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

    FeishuAlertProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static FeishuAlertProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.feishu.alert.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new FeishuAlertProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    static List<RepairAlertType> requiredAlertTypes() {
        return REQUIRED_ALERT_TYPES.stream()
                .map(RepairAlertType::valueOf)
                .toList();
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 Feishu 告警 smoke 参数不完整，本次没有发送真实飞书消息。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 Feishu 告警前置条件"));
        return write(markdown);
    }

    Path writePassed(AlertSmokeEvidence evidence) throws IOException {
        requirePassedAlertEvidence(evidence);
        StringBuilder markdown = header("PASSED_FEISHU_ALERT_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writePassedEvidenceJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(AlertSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_FEISHU_ALERT_SMOKE", evidence.secretNeedles());
        markdown.append("""

                ## 失败信息

                """);
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
                .append("# RD-Bot Feishu 告警生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 Feishu 告警 smoke 覆盖的真实发送证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 Feishu app secret 的真实值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=FeishuAlertRealSmokeTest \\
                  -Drd.integration.feishu-alert.enabled=true \\
                  -Drd.feishu.alert.smoke.production-evidence=true \\
                  -Drd.feishu.alert.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.feishu.alert.smoke.environment-id=<production-environment-id> \\
                  -Drd.feishu.alert.smoke.executed-by=<operator> \\
                  -Drd.feishu.alert.smoke.app-id=<feishu-app-id> \\
                  -Drd.feishu.alert.smoke.app-secret=<feishu-app-secret> \\
                  -Drd.feishu.alert.smoke.chat-id=<feishu-chat-id> \\
                  -Drd.feishu.alert.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, AlertSmokeEvidence evidence) {
        markdown.append("""

                ## Feishu 告警 Smoke 证据

                """);
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- chatId：").append(oneLine(evidence.chatId())).append('\n');
        markdown.append("- feishuAlertEvidenceValidated：").append(feishuAlertEvidenceValidated(evidence)).append('\n');
        markdown.append("- feishuAlertMessageCount：").append(evidence.deliveredMessageCount()).append('\n');
        markdown.append("- alertTypes：").append(String.join(", ", evidence.alertTypes())).append('\n');
        markdown.append("- messageIds：").append(String.join(", ", evidence.messageIds())).append('\n');
        markdown.append("\n| alertType | delivered | messageId | metadataComplete | role | stageRunId | failureCategory | artifactUrl |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AlertDeliveryEvidence delivery : evidence.deliveries()) {
            markdown.append("| ").append(oneLine(delivery.alertType()))
                    .append(" | ").append(delivery.delivered())
                    .append(" | ").append(oneLine(delivery.messageId()))
                    .append(" | ").append(delivery.metadataComplete())
                    .append(" | ").append(oneLine(delivery.role()))
                    .append(" | ").append(oneLine(delivery.stageRunId()))
                    .append(" | ").append(oneLine(delivery.failureCategory()))
                    .append(" | ").append(oneLine(delivery.artifactUrl()))
                    .append(" |\n");
        }
    }

    private void requirePassedAlertEvidence(AlertSmokeEvidence evidence) {
        requireEvidenceText(evidence.taskId(), "taskId");
        requireEvidenceText(evidence.chatId(), "chatId");
        requireEvidenceText(evidence.rdBotVersion(), "rdBotVersion");
        requireEvidenceText(evidence.environmentId(), "environmentId");
        requireEvidenceText(evidence.executedBy(), "executedBy");
        for (AlertDeliveryEvidence delivery : evidence.deliveries()) {
            requireEvidenceText(delivery.alertType(), "alertType");
            if (!delivery.delivered()) {
                throw new IllegalArgumentException("delivered must be true for PASSED report");
            }
            requireEvidenceText(delivery.messageId(), "messageId");
            if (!delivery.metadataComplete()) {
                throw new IllegalArgumentException(
                        "Feishu alert delivery metadata must include role, stageRunId, failureCategory, nextAction and artifactUrl"
                );
            }
        }
        if (!evidence.alertTypes().containsAll(REQUIRED_ALERT_TYPES)) {
            throw new IllegalArgumentException(
                    "requiredAlertTypes must be delivered for PASSED report: " + REQUIRED_ALERT_TYPES
            );
        }
        if (!requiredAlertMessageIdsAreTraceable(evidence)) {
            throw new IllegalArgumentException(
                    "required alert deliveries must have unique messageId values for PASSED report"
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

    private Map<Integer, MatrixStatus> passedMatrix(AlertSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("Feishu 告警 smoke 未覆盖该验收点，需要生产专项演练。");
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", messageCount=" + evidence.deliveredMessageCount()
                + ", chatId=" + oneLine(evidence.chatId())
                + ", alertTypes=" + String.join(",", evidence.alertTypes());
        statuses.put(10, new MatrixStatus(
                "PASSED",
                evidenceRef + "；六类真实 Feishu IM API 发送均返回 messageId，证明告警已发送到配置的真实 chat。"
        ));
        statuses.put(13, new MatrixStatus(
                "NOT_RUN",
                "Feishu 告警消息和报告执行了 secret needle 脱敏校验；全量产物扫描仍需完整交付 smoke。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "Feishu 告警 smoke 只能作为 #10 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("Feishu 告警 smoke 未覆盖该验收点，需要生产专项演练。");
        statuses.put(10, new MatrixStatus(
                "FAILED",
                "Feishu 告警 smoke 失败，六类真实告警没有全部送达配置的 Feishu chat。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "Feishu 告警 smoke 失败，#10 未通过；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("feishu-alert-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writePassedEvidenceJson(Path markdownReportPath, AlertSmokeEvidence evidence) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", "PASSED_FEISHU_ALERT_SMOKE");
        json.put("generatedAt", clock.instant().toString());
        json.put("taskId", evidence.taskId());
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("chatId", evidence.chatId());
        json.put("feishuAlertEvidenceValidated", feishuAlertEvidenceValidated(evidence));
        json.put("feishuAlertMetadataComplete", feishuAlertEvidenceValidated(evidence));
        json.put("feishuAlertTaskId", evidence.taskId());
        json.put("feishuAlertMessageCount", evidence.deliveredMessageCount());
        json.put("alertTypes", evidence.alertTypes());
        json.put("messageIds", evidence.messageIds());
        json.put("deliveries", evidence.deliveries().stream()
                .map(delivery -> Map.of(
                        "alertType", delivery.alertType(),
                        "messageId", delivery.messageId(),
                        "delivered", delivery.delivered(),
                        "taskId", evidence.taskId(),
                        "role", delivery.role(),
                        "stageRunId", delivery.stageRunId(),
                        "failureCategory", delivery.failureCategory(),
                        "nextAction", delivery.nextAction(),
                        "artifactUrl", delivery.artifactUrl(),
                        "metadataComplete", delivery.metadataComplete()
                ))
                .toList());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static void requireEvidenceText(String value, String fieldName) {
        if (oneLine(value).isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank for PASSED report");
        }
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

    private static boolean feishuAlertEvidenceValidated(AlertSmokeEvidence evidence) {
        return evidence != null
                && evidence.deliveredMessageCount() >= REQUIRED_ALERT_TYPES.size()
                && evidence.alertTypes().containsAll(REQUIRED_ALERT_TYPES)
                && evidence.deliveries().stream().allMatch(AlertDeliveryEvidence::metadataComplete)
                && requiredAlertMessageIdsAreTraceable(evidence);
    }

    private static boolean requiredAlertMessageIdsAreTraceable(AlertSmokeEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        List<String> requiredMessageIds = evidence.deliveries().stream()
                .filter(delivery -> REQUIRED_ALERT_TYPES.contains(delivery.alertType()))
                .map(AlertDeliveryEvidence::messageId)
                .filter(messageId -> !messageId.isBlank())
                .toList();
        return requiredMessageIds.size() >= REQUIRED_ALERT_TYPES.size()
                && requiredMessageIds.stream().distinct().count() == requiredMessageIds.size();
    }

    record AlertSmokeEvidence(
            String taskId,
            String chatId,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<AlertDeliveryEvidence> deliveries,
            List<String> secretNeedles
    ) {
        AlertSmokeEvidence(
                String taskId,
                String alertType,
                String chatId,
                String messageId,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                boolean delivered,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    chatId,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    List.of(new AlertDeliveryEvidence(alertType, messageId, delivered)),
                    secretNeedles
            );
        }

        AlertSmokeEvidence {
            taskId = oneLine(taskId);
            chatId = oneLine(chatId);
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
            secretNeedles = secretNeedles == null ? List.of() : List.copyOf(secretNeedles);
        }

        List<String> alertTypes() {
            return deliveries.stream()
                    .map(AlertDeliveryEvidence::alertType)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }

        List<String> messageIds() {
            return deliveries.stream()
                    .filter(AlertDeliveryEvidence::delivered)
                    .map(AlertDeliveryEvidence::messageId)
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        int deliveredMessageCount() {
            return messageIds().size();
        }
    }

    record AlertDeliveryEvidence(
            String alertType,
            String messageId,
            boolean delivered,
            String role,
            String stageRunId,
            String failureCategory,
            String nextAction,
            String artifactUrl
    ) {
        AlertDeliveryEvidence(String alertType, String messageId, boolean delivered) {
            this(
                    alertType,
                    messageId,
                    delivered,
                    defaultRole(alertType),
                    defaultStageRunId(alertType),
                    oneLine(alertType),
                    defaultNextAction(alertType),
                    defaultArtifactUrl(alertType)
            );
        }

        AlertDeliveryEvidence {
            alertType = oneLine(alertType);
            messageId = oneLine(messageId);
            role = oneLine(role);
            stageRunId = oneLine(stageRunId);
            failureCategory = oneLine(failureCategory);
            nextAction = oneLine(nextAction);
            artifactUrl = oneLine(artifactUrl);
        }

        boolean metadataComplete() {
            return !role.isBlank()
                    && !stageRunId.isBlank()
                    && !failureCategory.isBlank()
                    && failureCategory.equals(alertType)
                    && !nextAction.isBlank()
                    && productionArtifactUri(artifactUrl);
        }

        private static boolean productionArtifactUri(String value) {
            return ProductionEvidenceUris.isProductionArtifactUri(value);
        }

        private static String defaultRole(String alertType) {
            return switch (oneLine(alertType)) {
                case "PROVIDER_FALLBACK" -> "CODING_AGENT";
                case "QA_FAILED" -> "QA_AGENT";
                case "DELIVERY_REVIEW_FAILED", "PR_PUBLICATION_FAILED" -> "DELIVERY_REVIEWER";
                default -> "REQUIREMENT_REVIEWER";
            };
        }

        private static String defaultStageRunId(String alertType) {
            return "feishu-alert-smoke-" + oneLine(alertType).toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }

        private static String defaultNextAction(String alertType) {
            return switch (oneLine(alertType)) {
                case "PROVIDER_FALLBACK" -> "确认 provider 降级链路和后续产物质量。";
                case "QA_FAILED" -> "人工复核 QA 失败日志和验收标准。";
                case "DELIVERY_REVIEW_FAILED" -> "人工复核交付复核意见并决定是否返工。";
                case "PR_PUBLICATION_FAILED" -> "检查 GitHub 认证、分支权限和 PR 发布响应。";
                case "STAGE_FAILED_NEEDS_HUMAN" -> "人工复核需求评审或执行失败原因。";
                default -> "观察自动重试是否进入下一 provider 或下一次阶段执行。";
            };
        }

        private static String defaultArtifactUrl(String alertType) {
            return "s3://rd-bot-qa/multi-agent-production-acceptance/feishu-alert/"
                    + oneLine(alertType).toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                    + ".json";
        }
    }

    private record AcceptancePoint(int number, String title) {
    }

    private record MatrixStatus(String status, String evidence) {
    }
}
