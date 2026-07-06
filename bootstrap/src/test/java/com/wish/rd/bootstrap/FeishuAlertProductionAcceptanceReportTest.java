package com.wish.rd.bootstrap;

import com.wish.rd.exec.repair.alert.model.RepairAlertType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuAlertProductionAcceptanceReportTest {

    @TempDir
    Path reportRoot;

    @Test
    void shouldRequireRealFeishuAlertProperties() {
        assertEquals(
                FeishuAlertProductionAcceptanceProfile.requiredPropertyNames(),
                FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(Map.of())
        );
        assertTrue(FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(Map.of())
                .contains("rd.feishu.alert.smoke.rd-bot-version"));
        assertTrue(FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(Map.of())
                .contains("rd.feishu.alert.smoke.environment-id"));
        assertTrue(FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(Map.of())
                .contains("rd.feishu.alert.smoke.executed-by"));
        Map<String, String> invalid = Map.ofEntries(
                entry("rd.feishu.alert.smoke.production-evidence", "false"),
                entry("rd.feishu.alert.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.feishu.alert.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.feishu.alert.smoke.executed-by", "qa-runner"),
                entry("rd.feishu.alert.smoke.app-id", "cli-test"),
                entry("rd.feishu.alert.smoke.app-secret", "app-secret"),
                entry("rd.feishu.alert.smoke.chat-id", "oc-alert")
        );

        assertEquals(
                List.of(
                        "rd.feishu.alert.smoke.secret-scan-needles",
                        "rd.feishu.alert.smoke.production-evidence=true"
                ),
                FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(invalid)
        );
    }

    @Test
    void shouldBuildProfileWithoutLeakingSecretValuesInDescription() {
        FeishuAlertProductionAcceptanceProfile profile = FeishuAlertProductionAcceptanceProfile.from(Map.ofEntries(
                entry("rd.feishu.alert.smoke.production-evidence", "true"),
                entry("rd.feishu.alert.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.feishu.alert.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.feishu.alert.smoke.executed-by", "qa-runner"),
                entry("rd.feishu.alert.smoke.app-id", "cli-test"),
                entry("rd.feishu.alert.smoke.app-secret", "real-secret-value"),
                entry("rd.feishu.alert.smoke.chat-id", "oc-alert"),
                entry("rd.feishu.alert.smoke.secret-scan-needles", "real-secret-value,token-value")
        ));

        assertEquals("cli-test", profile.appId());
        assertEquals("oc-alert", profile.chatId());
        assertEquals("0.1.0-smoke", profile.rdBotVersion());
        assertEquals("prod-equivalent-a", profile.environmentId());
        assertEquals("qa-runner", profile.executedBy());
        assertEquals(List.of("real-secret-value", "token-value"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("real-secret-value"));
    }

    @Test
    void shouldAcceptOptionalMainTaskIdForSameTaskAlertEvidence() {
        FeishuAlertProductionAcceptanceProfile profile = FeishuAlertProductionAcceptanceProfile.from(Map.ofEntries(
                entry("rd.feishu.alert.smoke.production-evidence", "true"),
                entry("rd.feishu.alert.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.feishu.alert.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.feishu.alert.smoke.executed-by", "qa-runner"),
                entry("rd.feishu.alert.smoke.app-id", "cli-test"),
                entry("rd.feishu.alert.smoke.app-secret", "real-secret-value"),
                entry("rd.feishu.alert.smoke.chat-id", "oc-alert"),
                entry("rd.feishu.alert.smoke.secret-scan-needles", "real-secret-value"),
                entry("rd.feishu.alert.smoke.task-id", "7478000000000000000")
        ));

        assertEquals("7478000000000000000", profile.taskId());
        assertTrue(profile.describeMissingRequirements().contains("rd.feishu.alert.smoke.task-id"));
    }

    @Test
    void shouldRequireEffectiveSecretScanNeedlesForRealFeishuAlertSmoke() {
        Map<String, String> properties = Map.ofEntries(
                entry("rd.feishu.alert.smoke.production-evidence", "true"),
                entry("rd.feishu.alert.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.feishu.alert.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.feishu.alert.smoke.executed-by", "qa-runner"),
                entry("rd.feishu.alert.smoke.app-id", "cli-test"),
                entry("rd.feishu.alert.smoke.app-secret", "real-secret-value"),
                entry("rd.feishu.alert.smoke.chat-id", "oc-alert"),
                entry("rd.feishu.alert.smoke.secret-scan-needles", ", ,")
        );

        assertTrue(FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(properties)
                .contains("rd.feishu.alert.smoke.secret-scan-needles>=1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FeishuAlertProductionAcceptanceProfile.from(properties)
        );
    }

    @Test
    void shouldRejectPassedFeishuAlertReportWithLocalArtifactUrl() {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence localArtifactDelivery =
                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                        "om-retryable",
                        true,
                        "REQUIREMENT_REVIEWER",
                        "stage-retryable",
                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                        "观察自动重试。",
                        "file:///tmp/rd-bot/feishu-alert/retryable.json"
                );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence evidence =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        "oc-alert",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of(
                                localArtifactDelivery,
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_NEEDS_HUMAN.name(),
                                        "om-human",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PROVIDER_FALLBACK.name(),
                                        "om-provider",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.QA_FAILED.name(),
                                        "om-qa",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.DELIVERY_REVIEW_FAILED.name(),
                                        "om-review",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PR_PUBLICATION_FAILED.name(),
                                        "om-pr",
                                        true
                                )
                        ),
                        List.of("secret-value")
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("artifactUrl"));
    }

    @Test
    void shouldRejectNonFeishuBaseUrlForRealFeishuAlertSmoke() {
        Map<String, String> properties = Map.ofEntries(
                entry("rd.feishu.alert.smoke.production-evidence", "true"),
                entry("rd.feishu.alert.smoke.base-url", "mock://feishu"),
                entry("rd.feishu.alert.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.feishu.alert.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.feishu.alert.smoke.executed-by", "qa-runner"),
                entry("rd.feishu.alert.smoke.app-id", "cli-test"),
                entry("rd.feishu.alert.smoke.app-secret", "real-secret-value"),
                entry("rd.feishu.alert.smoke.chat-id", "oc-alert"),
                entry("rd.feishu.alert.smoke.secret-scan-needles", "real-secret-value")
        );

        assertTrue(FeishuAlertProductionAcceptanceProfile.missingRequiredProperties(properties)
                .contains("rd.feishu.alert.smoke.base-url=https://open.feishu.cn"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FeishuAlertProductionAcceptanceProfile.from(properties)
        );
    }

    @Test
    void shouldWriteSkippedPassedAndFailedReportsWithoutSecretValues() throws Exception {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );

        Path skipped = report.writeSkipped(List.of("rd.feishu.alert.smoke.chat-id"));
        String skippedMarkdown = Files.readString(skipped);
        assertTrue(skippedMarkdown.contains("结论：SKIPPED"));
        assertTrue(skippedMarkdown.contains("下一步补验命令"));
        assertTrue(skippedMarkdown.contains("FeishuAlertRealSmokeTest"));
        assertTrue(skippedMarkdown.contains("-Drd.feishu.alert.smoke.secret-scan-needles=<secret-scan-needles>"));
        assertTrue(skippedMarkdown.contains("| 10 | 错误通知真实送达 Feishu | NOT_RUN |"));

        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence evidence =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        "oc-alert",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of(
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                                        "om-retryable",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_NEEDS_HUMAN.name(),
                                        "om-human",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PROVIDER_FALLBACK.name(),
                                        "om-provider",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.QA_FAILED.name(),
                                        "om-qa",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.DELIVERY_REVIEW_FAILED.name(),
                                        "om-review",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PR_PUBLICATION_FAILED.name(),
                                        "om-pr",
                                        true
                                )
                        ),
                        List.of("secret-value")
                );
        Path passed = report.writePassed(evidence);
        String passedMarkdown = Files.readString(passed);
        Path passedJson = Path.of(passed.toString().replace(".md", ".json"));
        String passedJsonContent = Files.readString(passedJson);
        assertTrue(passedMarkdown.contains("结论：PASSED_FEISHU_ALERT_SMOKE"));
        assertTrue(passedMarkdown.contains("验收时间：2026-07-01T08:30:00Z"));
        assertTrue(passedMarkdown.contains("RD-Bot 版本：0.1.0-smoke"));
        assertTrue(passedMarkdown.contains("生产环境标识：prod-equivalent-a"));
        assertTrue(passedMarkdown.contains("执行人：qa-runner"));
        assertTrue(passedMarkdown.contains("feishuAlertEvidenceValidated：true"));
        assertTrue(passedMarkdown.contains("feishuAlertMessageCount：6"));
        assertTrue(passedMarkdown.contains("alertTypes：STAGE_FAILED_RETRYABLE, STAGE_FAILED_NEEDS_HUMAN, "
                + "PROVIDER_FALLBACK, QA_FAILED, DELIVERY_REVIEW_FAILED, PR_PUBLICATION_FAILED"));
        assertTrue(passedMarkdown.contains("messageIds：om-retryable, om-human, om-provider, om-qa, om-review, om-pr"));
        assertTrue(passedMarkdown.contains("| 10 | 错误通知真实送达 Feishu | PASSED | taskId=alert-smoke-1"));
        assertTrue(passedMarkdown.contains("messageCount=6"));
        assertTrue(passedMarkdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertTrue(passedMarkdown.contains("| 15 | 生产真实测试结论要求 | NOT_RUN |"));
        assertTrue(passedMarkdown.contains("Feishu 告警 smoke 只能作为 #10 专项证据"));
        assertUsesOnlyFinalAcceptanceStatuses(passedMarkdown);
        assertFalse(passedMarkdown.contains("secret-value"));
        assertTrue(passedJsonContent.contains("\"feishuAlertEvidenceValidated\":true"));
        assertTrue(passedJsonContent.contains("\"feishuAlertMetadataComplete\":true"));
        assertTrue(passedJsonContent.contains("\"feishuAlertMessageCount\":6"));
        assertTrue(passedJsonContent.contains("\"feishuAlertTaskId\":\"alert-smoke-1\""));
        assertTrue(passedJsonContent.contains("\"rdBotVersion\":\"0.1.0-smoke\""));
        assertTrue(passedJsonContent.contains("\"environmentId\":\"prod-equivalent-a\""));
        assertTrue(passedJsonContent.contains("\"executedBy\":\"qa-runner\""));
        assertTrue(passedJsonContent.contains("\"PROVIDER_FALLBACK\""));
        assertTrue(passedJsonContent.contains("\"om-provider\""));
        assertTrue(passedJsonContent.contains("\"metadataComplete\":true"));
        assertTrue(passedJsonContent.contains("\"role\":\"REQUIREMENT_REVIEWER\""));
        assertTrue(passedJsonContent.contains("\"failureCategory\":\"PROVIDER_FALLBACK\""));
        assertTrue(passedJsonContent.contains(
                "\"artifactUrl\":\"s3://rd-bot-qa/multi-agent-production-acceptance/feishu-alert/"
                        + "provider-fallback.json\""));
        assertFalse(passedJsonContent.contains("secret-value"));

        Path failed = report.writeFailed(evidence, new AssertionError("leaked secret-value"));
        String failedMarkdown = Files.readString(failed);
        assertTrue(failedMarkdown.contains("结论：FAILED_FEISHU_ALERT_SMOKE"));
        assertTrue(failedMarkdown.contains("[REDACTED]"));
        assertTrue(failedMarkdown.contains("| 1 | 需求任务可进入多 Agent 工作流 | NOT_RUN |"));
        assertTrue(failedMarkdown.contains("| 10 | 错误通知真实送达 Feishu | FAILED |"));
        assertTrue(failedMarkdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
        assertTrue(failedMarkdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertTrue(failedMarkdown.contains("| 15 | 生产真实测试结论要求 | FAILED |"));
        assertUsesOnlyFinalAcceptanceStatuses(failedMarkdown);
        assertFalse(failedMarkdown.contains("secret-value"));
    }

    @Test
    void shouldRejectPassedFeishuReportWithoutTraceableEvidence() {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence missingMessageId =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        RepairAlertType.QA_FAILED.name(),
                        "oc-alert",
                        "",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        true,
                        List.of()
                );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence notDelivered =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        RepairAlertType.QA_FAILED.name(),
                        "oc-alert",
                        "om-message",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        false,
                        List.of()
                );

        IllegalArgumentException missingMessageFailure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(missingMessageId)
        );
        IllegalArgumentException notDeliveredFailure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(notDelivered)
        );

        assertTrue(missingMessageFailure.getMessage().contains("messageId"));
        assertTrue(notDeliveredFailure.getMessage().contains("delivered"));
    }

    @Test
    void shouldRejectPassedFeishuReportUnlessAllRequiredAlertTypesAreDelivered() {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence singleAlert =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        RepairAlertType.QA_FAILED.name(),
                        "oc-alert",
                        "om-qa",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        true,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(singleAlert)
        );

        assertTrue(failure.getMessage().contains("requiredAlertTypes"));
        assertTrue(failure.getMessage().contains(RepairAlertType.PROVIDER_FALLBACK.name()));
    }

    @Test
    void shouldRejectPassedFeishuReportWithoutTraceableRunMetadata() {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence evidence =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        "oc-alert",
                        "",
                        "",
                        "",
                        requiredDeliveredAlerts(),
                        List.of("secret-value")
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("rdBotVersion"));
    }

    @Test
    void shouldRejectPassedFeishuReportWhenRequiredDeliveriesReuseMessageIds() {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence evidence =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        "oc-alert",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of(
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                                        "om-retryable",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_NEEDS_HUMAN.name(),
                                        "om-human",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PROVIDER_FALLBACK.name(),
                                        "om-provider",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.QA_FAILED.name(),
                                        "om-qa",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.DELIVERY_REVIEW_FAILED.name(),
                                        "om-review",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PR_PUBLICATION_FAILED.name(),
                                        "om-qa",
                                        true
                                )
                        ),
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("unique messageId"));
    }

    @Test
    void shouldRejectPassedFeishuReportWhenFailureCategoryDoesNotMatchAlertType() {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence evidence =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        "oc-alert",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of(
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                                        "om-retryable",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_NEEDS_HUMAN.name(),
                                        "om-human",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PROVIDER_FALLBACK.name(),
                                        "om-provider",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.QA_FAILED.name(),
                                        "om-qa",
                                        true,
                                        "QA_AGENT",
                                        "stage-qa",
                                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                                        "人工复核 QA 失败日志和验收标准。",
                                        "s3://rd-bot-qa/multi-agent-production-acceptance/qa-failed.json"
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.DELIVERY_REVIEW_FAILED.name(),
                                        "om-review",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PR_PUBLICATION_FAILED.name(),
                                        "om-pr",
                                        true
                                )
                        ),
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("failureCategory"));
    }

    @Test
    void shouldRejectPassedFeishuReportWhenArtifactUrlIsNotProductionUri() {
        FeishuAlertProductionAcceptanceReport report = new FeishuAlertProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence evidence =
                new FeishuAlertProductionAcceptanceReport.AlertSmokeEvidence(
                        "alert-smoke-1",
                        "oc-alert",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of(
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                                        "om-retryable",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.STAGE_FAILED_NEEDS_HUMAN.name(),
                                        "om-human",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PROVIDER_FALLBACK.name(),
                                        "om-provider",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.QA_FAILED.name(),
                                        "om-qa",
                                        true,
                                        "QA_AGENT",
                                        "stage-qa",
                                        RepairAlertType.QA_FAILED.name(),
                                        "人工复核 QA 失败日志和验收标准。",
                                        "qa-runs/multi-agent-production-acceptance"
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.DELIVERY_REVIEW_FAILED.name(),
                                        "om-review",
                                        true
                                ),
                                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                                        RepairAlertType.PR_PUBLICATION_FAILED.name(),
                                        "om-pr",
                                        true
                                )
                        ),
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("artifactUrl"));
    }

    private static List<FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence> requiredDeliveredAlerts() {
        return List.of(
                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        RepairAlertType.STAGE_FAILED_RETRYABLE.name(),
                        "om-retryable",
                        true
                ),
                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        RepairAlertType.STAGE_FAILED_NEEDS_HUMAN.name(),
                        "om-human",
                        true
                ),
                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        RepairAlertType.PROVIDER_FALLBACK.name(),
                        "om-provider",
                        true
                ),
                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        RepairAlertType.QA_FAILED.name(),
                        "om-qa",
                        true
                ),
                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        RepairAlertType.DELIVERY_REVIEW_FAILED.name(),
                        "om-review",
                        true
                ),
                new FeishuAlertProductionAcceptanceReport.AlertDeliveryEvidence(
                        RepairAlertType.PR_PUBLICATION_FAILED.name(),
                        "om-pr",
                        true
                )
        );
    }

    private static void assertUsesOnlyFinalAcceptanceStatuses(String markdown) {
        assertFalse(markdown.contains("EVIDENCE_COLLECTED"));
        assertFalse(markdown.contains("PARTIAL_EVIDENCE"));
        assertFalse(markdown.contains("FAILED_OR_NOT_PROVEN"));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-01T08:30:00Z"), ZoneOffset.UTC);
    }
}
