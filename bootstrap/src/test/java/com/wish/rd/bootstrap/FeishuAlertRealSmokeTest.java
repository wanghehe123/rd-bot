package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.feishu.im.FeishuImClient;
import com.wish.rd.bootstrap.feishu.im.FeishuImProperties;
import com.wish.rd.bootstrap.feishu.im.FeishuImRepairAlertSink;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feishu alert production smoke. Disabled by default and sends a real Feishu IM message when enabled.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=FeishuAlertRealSmokeTest \
 *   -Drd.integration.feishu-alert.enabled=true \
 *   -Drd.feishu.alert.smoke.production-evidence=true \
 *   -Drd.feishu.alert.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.feishu.alert.smoke.environment-id=prod-equivalent-a \
 *   -Drd.feishu.alert.smoke.executed-by=qa-runner \
 *   -Drd.feishu.alert.smoke.app-id="$FEISHU_APP_ID" \
 *   -Drd.feishu.alert.smoke.app-secret="$FEISHU_APP_SECRET" \
 *   -Drd.feishu.alert.smoke.chat-id="$FEISHU_ALERT_CHAT_ID" \
 *   -Drd.feishu.alert.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Drd.feishu.alert.smoke.secret-scan-needles="$FEISHU_APP_SECRET" \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.feishu-alert.enabled", matches = "true")
class FeishuAlertRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendsRealRepairAlertToFeishuAndWritesAcceptanceEvidence() throws Exception {
        FeishuAlertProductionAcceptanceReport report = FeishuAlertProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = FeishuAlertProductionAcceptanceProfile.systemProperties();
        List<String> missing = FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("feishu-alert", missing, skippedReport);
        }
        FeishuAlertProductionAcceptanceProfile profile = FeishuAlertProductionAcceptanceProfile.from(properties);
        String taskId = profile.taskId().isBlank()
                ? "feishu-alert-smoke-" + Instant.now().toEpochMilli()
                : profile.taskId();
        List<FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence> deliveries = new ArrayList<>();
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence evidence =
                alertEvidence(profile, taskId, deliveries);
        try {
            FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                    new FeishuImClient(feishuProperties(profile), OBJECT_MAPPER),
                    feishuProperties(profile)
            );
            List<RepairAlertType> alertTypes = FeishuAlertProductionAcceptanceReport.requiredAlertTypes();
            for (RepairAlertType alertType : alertTypes) {
                Map<String, String> metadata = alertMetadata(alertType);
                sink.publish(new RepairAlert(
                        taskId + "-" + alertType.name().toLowerCase(java.util.Locale.ROOT),
                        taskId,
                        alertType,
                        "生产验收 smoke: RD-Bot 多 Agent " + alertType.name() + " 告警真实发送验证。",
                        metadata,
                        System.currentTimeMillis()
                ));
                FeishuImRepairAlertSink.DeliveryAttempt attempt = sink.deliveryAttempts().get(deliveries.size());
                deliveries.add(new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        attempt.alertType(),
                        attempt.messageId(),
                        attempt.success(),
                        metadata.get("role"),
                        metadata.get("stageRunId"),
                        metadata.get("failureCategory"),
                        metadata.get("nextAction"),
                        metadata.get("artifactUrl")
                ));
                evidence = alertEvidence(profile, taskId, deliveries);
                assertEquals(alertType.name(), attempt.alertType(), "Feishu attempt must match alert type");
                assertTrue(attempt.success(), "Feishu alert delivery must succeed: type=" + alertType
                        + ", code=" + attempt.code() + ", message=" + attempt.message());
                assertFalse(attempt.messageId().isBlank(), "Feishu API must return a real messageId: " + alertType);
            }
            assertEquals(alertTypes.size(), sink.deliveryAttempts().size(),
                    "Feishu smoke must send every required alert type");
            Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] feishu-alert taskId=" + taskId
                    + " messageCount=" + deliveries.size()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] feishu-alert failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    private static FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence alertEvidence(
            FeishuAlertProductionAcceptanceProfile profile,
            String taskId,
            List<FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence> deliveries
    ) {
        return new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                taskId,
                profile.chatId(),
                profile.rdBotVersion(),
                profile.environmentId(),
                profile.executedBy(),
                deliveries,
                profile.secretScanNeedles()
        );
    }

    private static Map<String, String> alertMetadata(RepairAlertType alertType) {
        return switch (alertType) {
            case STAGE_FAILED_RETRYABLE -> Map.of(
                    "role", "REQUIREMENT_REVIEWER",
                    "stageRunId", "feishu-alert-smoke-retryable",
                    "failureCategory", "STAGE_FAILED_RETRYABLE",
                    "nextAction", "观察自动重试是否进入下一 provider 或下一次阶段执行。",
                    "artifactUrl", artifactUrl(alertType)
            );
            case STAGE_FAILED_NEEDS_HUMAN -> Map.of(
                    "role", "REQUIREMENT_REVIEWER",
                    "stageRunId", "feishu-alert-smoke-human",
                    "failureCategory", "STAGE_FAILED_NEEDS_HUMAN",
                    "nextAction", "人工复核需求评审或执行失败原因。",
                    "artifactUrl", artifactUrl(alertType)
            );
            case PROVIDER_FALLBACK -> Map.of(
                    "role", "CODING_AGENT",
                    "stageRunId", "feishu-alert-smoke-provider",
                    "failureCategory", "PROVIDER_FALLBACK",
                    "failedProvider", "provider-a",
                    "failedStatus", "FAILED_VALIDATION",
                    "activeProvider", "provider-b",
                    "nextAction", "确认 provider 降级链路和后续产物质量。",
                    "artifactUrl", artifactUrl(alertType)
            );
            case QA_FAILED -> Map.of(
                    "role", "QA_AGENT",
                    "stageRunId", "feishu-alert-smoke-qa",
                    "failureCategory", "QA_FAILED",
                    "nextAction", "人工复核 QA 失败日志和验收标准。",
                    "artifactUrl", artifactUrl(alertType)
            );
            case DELIVERY_REVIEW_FAILED -> Map.of(
                    "role", "DELIVERY_REVIEWER",
                    "stageRunId", "feishu-alert-smoke-review",
                    "failureCategory", "DELIVERY_REVIEW_FAILED",
                    "nextAction", "人工复核交付复核意见并决定是否返工。",
                    "artifactUrl", artifactUrl(alertType)
            );
            case PR_PUBLICATION_FAILED -> Map.of(
                    "role", "DELIVERY_REVIEWER",
                    "stageRunId", "feishu-alert-smoke-pr",
                    "failureCategory", "PR_PUBLICATION_FAILED",
                    "nextAction", "检查 GitHub 认证、分支权限和 PR 发布响应。",
                    "artifactUrl", artifactUrl(alertType)
            );
            default -> Map.of(
                    "role", "REQUIREMENT_REVIEWER",
                    "stageRunId", "feishu-alert-smoke-generic",
                    "failureCategory", alertType.name(),
                    "nextAction", "人工查看告警类型并处理。",
                    "artifactUrl", artifactUrl(alertType)
            );
        };
    }

    private static String artifactUrl(RepairAlertType alertType) {
        return "s3://rd-bot-qa/multi-agent-production-acceptance/feishu-alert/"
                + alertType.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                + ".json";
    }

    private static FeishuImProperties feishuProperties(FeishuAlertProductionAcceptanceProfile profile) {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(profile.baseUrl());
        properties.setAppId(profile.appId());
        properties.setAppSecret(profile.appSecret());
        properties.getAlert().setEnabled(true);
        properties.getAlert().setChatId(profile.chatId());
        return properties;
    }
}
