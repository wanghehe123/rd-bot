package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuAlertEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedSixAlertEvidenceForTheSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, """
                {
                  "feishuAlertEvidenceValidated": true,
                  "feishuAlertMessageCount": 6,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "feishuAlertTaskId": "alert-smoke-1",
                  "feishuAlertMetadataComplete": true,
                  "alertTypes": [
                    "STAGE_FAILED_RETRYABLE",
                    "STAGE_FAILED_NEEDS_HUMAN",
                    "PROVIDER_FALLBACK",
                    "QA_FAILED",
                    "DELIVERY_REVIEW_FAILED",
                    "PR_PUBLICATION_FAILED"
                  ],
                  "messageIds": ["om-1", "om-2", "om-3", "om-4", "om-5", "om-6"]
                  ,
                  "deliveries": [
                    {
                      "alertType": "STAGE_FAILED_RETRYABLE",
                      "messageId": "om-1",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-1",
                      "failureCategory": "STAGE_FAILED_RETRYABLE",
                      "nextAction": "观察自动重试。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-1.md"
                    },
                    {
                      "alertType": "STAGE_FAILED_NEEDS_HUMAN",
                      "messageId": "om-2",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-2",
                      "failureCategory": "STAGE_FAILED_NEEDS_HUMAN",
                      "nextAction": "人工补充需求。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-2.md"
                    },
                    {
                      "alertType": "PROVIDER_FALLBACK",
                      "messageId": "om-3",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "CODING_AGENT",
                      "stageRunId": "stage-3",
                      "failureCategory": "PROVIDER_FALLBACK",
                      "nextAction": "确认 provider 降级。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-3.md"
                    },
                    {
                      "alertType": "QA_FAILED",
                      "messageId": "om-4",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "QA_AGENT",
                      "stageRunId": "stage-4",
                      "failureCategory": "QA_FAILED",
                      "nextAction": "复核 QA 日志。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-4.md"
                    },
                    {
                      "alertType": "DELIVERY_REVIEW_FAILED",
                      "messageId": "om-5",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-5",
                      "failureCategory": "DELIVERY_REVIEW_FAILED",
                      "nextAction": "复核交付意见。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-5.md"
                    },
                    {
                      "alertType": "PR_PUBLICATION_FAILED",
                      "messageId": "om-6",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-6",
                      "failureCategory": "PR_PUBLICATION_FAILED",
                      "nextAction": "检查 GitHub 权限。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-6.md"
                    }
                  ]
                }
                """);
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertTrue(evidence.validated());
        assertEquals("alert-smoke-1", evidence.taskId());
        assertEquals(6, evidence.messageCount());
        assertEquals(List.of(
                "STAGE_FAILED_RETRYABLE",
                "STAGE_FAILED_NEEDS_HUMAN",
                "PROVIDER_FALLBACK",
                "QA_FAILED",
                "DELIVERY_REVIEW_FAILED",
                "PR_PUBLICATION_FAILED"
        ), evidence.alertTypes());
    }

    @Test
    void shouldIgnoreFeishuAlertEvidenceFromADifferentProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, """
                {
                  "feishuAlertEvidenceValidated": true,
                  "feishuAlertMessageCount": 6,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "another-env",
                  "executedBy": "qa-runner",
                  "alertTypes": [
                    "STAGE_FAILED_RETRYABLE",
                    "STAGE_FAILED_NEEDS_HUMAN",
                    "PROVIDER_FALLBACK",
                    "QA_FAILED",
                    "DELIVERY_REVIEW_FAILED",
                    "PR_PUBLICATION_FAILED"
                  ],
                  "messageIds": ["om-1", "om-2", "om-3", "om-4", "om-5", "om-6"]
                }
                """);
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWithLocalFileArtifactUrl() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, validCompactEvidenceJson().replace(
                "\"artifactUrl\": \"s3://rd-bot-qa/feishu/stage-1.json\"",
                "\"artifactUrl\": \"file:///tmp/rd-bot/feishu/stage-1.json\""
        ));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWithoutMetadataForEveryDelivery() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, """
                {
                  "feishuAlertEvidenceValidated": true,
                  "feishuAlertMessageCount": 6,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "alertTypes": [
                    "STAGE_FAILED_RETRYABLE",
                    "STAGE_FAILED_NEEDS_HUMAN",
                    "PROVIDER_FALLBACK",
                    "QA_FAILED",
                    "DELIVERY_REVIEW_FAILED",
                    "PR_PUBLICATION_FAILED"
                  ],
                  "messageIds": ["om-1", "om-2", "om-3", "om-4", "om-5", "om-6"],
                  "deliveries": [
                    {
                      "alertType": "STAGE_FAILED_RETRYABLE",
                      "messageId": "om-1",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-1",
                      "failureCategory": "STAGE_FAILED_RETRYABLE",
                      "nextAction": "观察自动重试。"
                    }
                  ]
                }
                """);
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWhenTopLevelMessageIdsDoNotMatchDeliveries() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, """
                {
                  "feishuAlertEvidenceValidated": true,
                  "feishuAlertMessageCount": 6,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "alertTypes": [
                    "STAGE_FAILED_RETRYABLE",
                    "STAGE_FAILED_NEEDS_HUMAN",
                    "PROVIDER_FALLBACK",
                    "QA_FAILED",
                    "DELIVERY_REVIEW_FAILED",
                    "PR_PUBLICATION_FAILED"
                  ],
                  "messageIds": ["om-a", "om-b", "om-c", "om-d", "om-e", "om-f"],
                  "deliveries": [
                    {
                      "alertType": "STAGE_FAILED_RETRYABLE",
                      "messageId": "om-1",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-1",
                      "failureCategory": "STAGE_FAILED_RETRYABLE",
                      "nextAction": "观察自动重试。",
                      "artifactUrl": "qa-runs/multi-agent-production-acceptance"
                    },
                    {
                      "alertType": "STAGE_FAILED_NEEDS_HUMAN",
                      "messageId": "om-2",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-2",
                      "failureCategory": "STAGE_FAILED_NEEDS_HUMAN",
                      "nextAction": "人工补充需求。",
                      "artifactUrl": "qa-runs/multi-agent-production-acceptance"
                    },
                    {
                      "alertType": "PROVIDER_FALLBACK",
                      "messageId": "om-3",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "CODING_AGENT",
                      "stageRunId": "stage-3",
                      "failureCategory": "PROVIDER_FALLBACK",
                      "nextAction": "确认 provider 降级。",
                      "artifactUrl": "qa-runs/multi-agent-production-acceptance"
                    },
                    {
                      "alertType": "QA_FAILED",
                      "messageId": "om-4",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "QA_AGENT",
                      "stageRunId": "stage-4",
                      "failureCategory": "QA_FAILED",
                      "nextAction": "复核 QA 日志。",
                      "artifactUrl": "qa-runs/multi-agent-production-acceptance"
                    },
                    {
                      "alertType": "DELIVERY_REVIEW_FAILED",
                      "messageId": "om-5",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-5",
                      "failureCategory": "DELIVERY_REVIEW_FAILED",
                      "nextAction": "复核交付意见。",
                      "artifactUrl": "qa-runs/multi-agent-production-acceptance"
                    },
                    {
                      "alertType": "PR_PUBLICATION_FAILED",
                      "messageId": "om-6",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-6",
                      "failureCategory": "PR_PUBLICATION_FAILED",
                      "nextAction": "检查 GitHub 权限。",
                      "artifactUrl": "qa-runs/multi-agent-production-acceptance"
                    }
                  ]
                }
                """);
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWhenTopLevelTaskIdDoesNotMatchDeliveries() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, validSixAlertEvidenceJson().replace(
                "\"feishuAlertTaskId\": \"alert-smoke-1\"",
                "\"feishuAlertTaskId\": \"alert-smoke-2\""
        ));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWhenRequiredDeliveriesReuseMessageIds() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, validSixAlertEvidenceJson().replace(
                "\"messageId\": \"om-6\"",
                "\"messageId\": \"om-4\""
        ));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWhenFailureCategoryDoesNotMatchAlertType() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, validSixAlertEvidenceJson().replace(
                "\"failureCategory\": \"QA_FAILED\"",
                "\"failureCategory\": \"STAGE_FAILED_RETRYABLE\""
        ));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWhenDeliveriesComeFromDifferentTasks() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, validSixAlertEvidenceJson().replace(
                """
                      "taskId": "alert-smoke-1",
                      "role": "QA_AGENT",
                """,
                """
                      "taskId": "alert-smoke-2",
                      "role": "QA_AGENT",
                """
        ));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWhenArtifactUrlsAreNotProductionUris() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, validSixAlertEvidenceJson().replace(
                "\"artifactUrl\": \"s3://rd-bot-qa/multi-agent-production-acceptance/stage-4.md\"",
                "\"artifactUrl\": \"qa-runs/multi-agent-production-acceptance/stage-4.md\""
        ));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    @Test
    void shouldRejectFeishuAlertEvidenceWithoutTopLevelMetadataCompleteFlag() throws Exception {
        Path evidenceJson = tempDir.resolve("feishu-alert.json");
        Files.writeString(evidenceJson, validSixAlertEvidenceJson().replace(
                "  \"feishuAlertMetadataComplete\": true,\n",
                ""
        ));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        FeishuAlertEvidenceFile evidence = FeishuAlertEvidenceFile.from(profile);

        assertFalse(evidence.validated());
        assertEquals(0, evidence.messageCount());
        assertEquals(List.of(), evidence.alertTypes());
    }

    private static String validCompactEvidenceJson() {
        return """
                {
                  "feishuAlertEvidenceValidated": true,
                  "feishuAlertMessageCount": 6,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "feishuAlertTaskId": "alert-smoke-1",
                  "feishuAlertMetadataComplete": true,
                  "alertTypes": [
                    "STAGE_FAILED_RETRYABLE",
                    "STAGE_FAILED_NEEDS_HUMAN",
                    "PROVIDER_FALLBACK",
                    "QA_FAILED",
                    "DELIVERY_REVIEW_FAILED",
                    "PR_PUBLICATION_FAILED"
                  ],
                  "messageIds": ["om-1", "om-2", "om-3", "om-4", "om-5", "om-6"],
                  "deliveries": [
                    {
                      "alertType": "STAGE_FAILED_RETRYABLE",
                      "messageId": "om-1",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-1",
                      "failureCategory": "STAGE_FAILED_RETRYABLE",
                      "nextAction": "retry",
                      "artifactUrl": "s3://rd-bot-qa/feishu/stage-1.json"
                    },
                    {
                      "alertType": "STAGE_FAILED_NEEDS_HUMAN",
                      "messageId": "om-2",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-2",
                      "failureCategory": "STAGE_FAILED_NEEDS_HUMAN",
                      "nextAction": "human",
                      "artifactUrl": "s3://rd-bot-qa/feishu/stage-2.json"
                    },
                    {
                      "alertType": "PROVIDER_FALLBACK",
                      "messageId": "om-3",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "CODING_AGENT",
                      "stageRunId": "stage-3",
                      "failureCategory": "PROVIDER_FALLBACK",
                      "nextAction": "fallback",
                      "artifactUrl": "s3://rd-bot-qa/feishu/stage-3.json"
                    },
                    {
                      "alertType": "QA_FAILED",
                      "messageId": "om-4",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "QA_AGENT",
                      "stageRunId": "stage-4",
                      "failureCategory": "QA_FAILED",
                      "nextAction": "qa",
                      "artifactUrl": "s3://rd-bot-qa/feishu/stage-4.json"
                    },
                    {
                      "alertType": "DELIVERY_REVIEW_FAILED",
                      "messageId": "om-5",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-5",
                      "failureCategory": "DELIVERY_REVIEW_FAILED",
                      "nextAction": "review",
                      "artifactUrl": "s3://rd-bot-qa/feishu/stage-5.json"
                    },
                    {
                      "alertType": "PR_PUBLICATION_FAILED",
                      "messageId": "om-6",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-6",
                      "failureCategory": "PR_PUBLICATION_FAILED",
                      "nextAction": "pr",
                      "artifactUrl": "s3://rd-bot-qa/feishu/stage-6.json"
                    }
                  ]
                }
                """;
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path feishuAlertEvidenceJson) {
        return MultiAgentProductionAcceptanceProfile.from(Map.ofEntries(
                entry("rd.multi-agent.smoke.production-evidence", "true"),
                entry("rd.multi-agent.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.multi-agent.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.multi-agent.smoke.executed-by", "qa-runner"),
                entry("rd.multi-agent.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.multi-agent.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.multi-agent.smoke.postgres-user", "rd_bot"),
                entry("rd.multi-agent.smoke.postgres-password", "secret"),
                entry("rd.multi-agent.smoke.repository-url", "https://github.com/acme/rd-bot-smoke.git"),
                entry("rd.multi-agent.smoke.repo-owner", "acme"),
                entry("rd.multi-agent.smoke.repo-name", "rd-bot-smoke"),
                entry("rd.multi-agent.smoke.expected-provider-count", "2"),
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,ANTHROPIC_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "postgres-secret,github-secret"),
                entry("rd.multi-agent.smoke.feishu-alert-evidence-json", feishuAlertEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }

    private static String validSixAlertEvidenceJson() {
        return """
                {
                  "feishuAlertEvidenceValidated": true,
                  "feishuAlertMessageCount": 6,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "feishuAlertTaskId": "alert-smoke-1",
                  "feishuAlertMetadataComplete": true,
                  "alertTypes": [
                    "STAGE_FAILED_RETRYABLE",
                    "STAGE_FAILED_NEEDS_HUMAN",
                    "PROVIDER_FALLBACK",
                    "QA_FAILED",
                    "DELIVERY_REVIEW_FAILED",
                    "PR_PUBLICATION_FAILED"
                  ],
                  "messageIds": ["om-1", "om-2", "om-3", "om-4", "om-5", "om-6"],
                  "deliveries": [
                    {
                      "alertType": "STAGE_FAILED_RETRYABLE",
                      "messageId": "om-1",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-1",
                      "failureCategory": "STAGE_FAILED_RETRYABLE",
                      "nextAction": "观察自动重试。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-1.md"
                    },
                    {
                      "alertType": "STAGE_FAILED_NEEDS_HUMAN",
                      "messageId": "om-2",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "REQUIREMENT_REVIEWER",
                      "stageRunId": "stage-2",
                      "failureCategory": "STAGE_FAILED_NEEDS_HUMAN",
                      "nextAction": "人工补充需求。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-2.md"
                    },
                    {
                      "alertType": "PROVIDER_FALLBACK",
                      "messageId": "om-3",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "CODING_AGENT",
                      "stageRunId": "stage-3",
                      "failureCategory": "PROVIDER_FALLBACK",
                      "nextAction": "确认 provider 降级。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-3.md"
                    },
                    {
                      "alertType": "QA_FAILED",
                      "messageId": "om-4",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "QA_AGENT",
                      "stageRunId": "stage-4",
                      "failureCategory": "QA_FAILED",
                      "nextAction": "复核 QA 日志。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-4.md"
                    },
                    {
                      "alertType": "DELIVERY_REVIEW_FAILED",
                      "messageId": "om-5",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-5",
                      "failureCategory": "DELIVERY_REVIEW_FAILED",
                      "nextAction": "复核交付意见。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-5.md"
                    },
                    {
                      "alertType": "PR_PUBLICATION_FAILED",
                      "messageId": "om-6",
                      "delivered": true,
                      "taskId": "alert-smoke-1",
                      "role": "DELIVERY_REVIEWER",
                      "stageRunId": "stage-6",
                      "failureCategory": "PR_PUBLICATION_FAILED",
                      "nextAction": "检查 GitHub 权限。",
                      "artifactUrl": "s3://rd-bot-qa/multi-agent-production-acceptance/stage-6.md"
                    }
                  ]
                }
                """;
    }
}
