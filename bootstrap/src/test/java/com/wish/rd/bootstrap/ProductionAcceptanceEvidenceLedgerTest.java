package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAcceptanceEvidenceLedgerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAggregateLatestSidecarEvidenceIntoFifteenPointLedger() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("provider-preflight-production-acceptance-20260704-080657.json"),
                """
                        {
                          "conclusion": "FAILED_PROVIDER_PREFLIGHT_SMOKE",
                          "providerPreflightEvidenceValidated": false,
                          "providers": [
                            {
                              "name": "long-cat",
                              "httpStatus": 401,
                              "failureReason": "PROVIDER_AUTHENTICATION_FAILED",
                              "message": "redacted ledger-canary-value"
                            },
                            {
                              "name": "minimax",
                              "httpStatus": 429,
                              "failureReason": "PROVIDER_QUOTA_OR_RATE_LIMIT",
                              "message": "redacted ledger-canary-value"
                            }
                          ]
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("feishu-alert-production-acceptance-20260704-030907.json"),
                """
                        {
                          "conclusion": "PASSED_FEISHU_ALERT_SMOKE",
                          "feishuAlertEvidenceValidated": true,
                          "feishuAlertMetadataComplete": true,
                          "taskId": "task-feishu-1",
                          "messageIds": ["om_1", "om_2", "om_3", "om_4", "om_5", "om_6"]
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("skill-production-acceptance-20260704-030532.json"),
                """
                        {
                          "skillPolicyEvidenceValidated": true,
                          "taskId": "task-skill-1"
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("qa-failure-blocker-production-acceptance-20260704-163325.json"),
                """
                        {
                          "conclusion": "PASSED_QA_FAILURE_BLOCKER_SMOKE",
                          "qaFailureBlockerEvidenceValidated": true,
                          "taskId": "task-qa-failed",
                          "stageRunId": "stage-qa-agent",
                          "taskStatus": "FAILED_NEEDS_HUMAN",
                          "qaStageStatus": "FAILED_NEEDS_HUMAN",
                          "executionResultStatus": "NEEDS_HUMAN",
                          "failedAcceptanceCount": 2
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("github-pr-remote-evidence-production-acceptance-20260704-030439.json"),
                """
                        {
                          "conclusion": "PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE",
                          "rdBotVersion": "0.1.0-local-real-smoke",
                          "environmentId": "local-machine",
                          "executedBy": "codex-local",
                          "githubPrRemoteEvidenceValidated": true,
                          "remotePrTraceValidated": true,
                          "pullRequestBodyIncludesDeliveryReview": true,
                          "pullRequestBodyIncludesQaEvidence": true,
                          "pullRequestBodyContainsTaskId": true,
                          "pullRequestBodyContainsArtifactLink": true,
                          "secretScanEvidenceValidated": true,
                          "pullRequestUrl": "https://github.com/acme/rd-bot/pull/12",
                          "taskId": "task-github-1"
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("delivery-review-failure-production-acceptance-20260704-124029.json"),
                """
                        {
                          "deliveryReviewFailureEvidenceValidated": true,
                          "rdBotVersion": "0.1.0-local-real-smoke",
                          "environmentId": "local-machine",
                          "executedBy": "codex-local",
                          "taskId": "task-review-failed",
                          "taskStatus": "REJECTED",
                          "deliveryReviewApproved": false,
                          "reviewDecision": "FAILED",
                          "reviewer": "DELIVERY_REVIEWER",
                          "reviewArtifactUri": "rd-artifact://task-review-failed/delivery-review/rejection.json",
                          "pullRequestPublicationAttempted": false,
                          "prCreated": false,
                          "successReportCreated": false,
                          "failureReportCreated": true,
                          "successDeliveryReportExperienceCreated": false,
                          "blockedBeforePrCreating": true
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("full-maven-local-regression-20260704-081310.json"),
                """
                        {
                          "conclusion": "PASSED_FULL_MAVEN_LOCAL_REGRESSION",
                          "testsRun": 541,
                          "failures": 0,
                          "secretNeedle": "ledger-canary-value"
                        }
                        """
        );
        ProductionAcceptanceEvidenceLedger ledger = new ProductionAcceptanceEvidenceLedger(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = ledger.write(evidenceRoot);

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("结论：FAILED_PRODUCTION_ACCEPTANCE_LEDGER"));
        assertTrue(markdown.contains("| 5 | 多 provider 降级重试真实生效 | FAILED | provider-preflight-production-acceptance-20260704-080657.json"));
        assertTrue(markdown.contains("PROVIDER_QUOTA_OR_RATE_LIMIT"));
        assertTrue(markdown.contains("| 10 | 错误通知真实送达 Feishu | PASSED | feishu-alert-production-acceptance-20260704-030907.json"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | PASSED | skill-production-acceptance-20260704-030532.json"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | PASSED | qa-failure-blocker-production-acceptance-20260704-163325.json"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | PASSED | delivery-review-failure-production-acceptance-20260704-124029.json"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | PASSED | github-pr-remote-evidence-production-acceptance-20260704-030439.json"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | FAILED |"));
        assertTrue(markdown.contains("未通过或未运行：#1, #2, #3, #4, #5, #6, #9, #12, #14"));
        assertTrue(markdown.contains("full-maven-local-regression-20260704-081310.json"));
        assertTrue(markdown.contains("## 下一步动作"));
        assertTrue(markdown.contains("long-cat：重新注入有效 provider secret"));
        assertTrue(markdown.contains("minimax：恢复 provider quota 或 Token Plan 额度"));
        assertTrue(json.contains("\"totalAcceptancePointCount\":15"));
        assertTrue(json.contains("\"passedCount\":5"));
        assertTrue(json.contains("\"failedCount\":2"));
        assertTrue(json.contains("\"notRunCount\":8"));
        assertTrue(json.contains("\"nextActions\""));
        assertTrue(json.contains("PROVIDER_AUTHENTICATION_FAILED"));
        assertTrue(json.contains("PROVIDER_QUOTA_OR_RATE_LIMIT"));
        assertFalse(markdown.contains("ledger-canary-value"));
        assertFalse(json.contains("ledger-canary-value"));
    }

    @Test
    void shouldUseFullProductionMatrixWhenItAlreadyPassed() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("multi-agent-production-acceptance-20260704-100000.json"),
                passedFullProductionMatrix()
        );
        ProductionAcceptanceEvidenceLedger ledger = new ProductionAcceptanceEvidenceLedger(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = ledger.write(evidenceRoot);

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("结论：PASSED_PRODUCTION_ACCEPTANCE_LEDGER"));
        assertTrue(markdown.contains("| 1 | 需求任务可进入多 Agent 工作流 | PASSED | multi-agent-production-acceptance-20260704-100000.json"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | PASSED | multi-agent-production-acceptance-20260704-100000.json"));
        assertTrue(json.contains("\"passedCount\":15"));
        assertTrue(json.contains("\"failedCount\":0"));
        assertTrue(json.contains("\"notRunCount\":0"));
    }

    @Test
    void shouldNotTreatLocalRegressionAsProductionAcceptancePass() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("full-maven-local-regression-20260704-081310.json"),
                """
                        {
                          "conclusion": "PASSED_FULL_MAVEN_LOCAL_REGRESSION",
                          "testsRun": 541,
                          "failures": 0
                        }
                        """
        );
        ProductionAcceptanceEvidenceLedger ledger = new ProductionAcceptanceEvidenceLedger(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = ledger.write(evidenceRoot);

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("结论：NOT_RUN_PRODUCTION_ACCEPTANCE_LEDGER"));
        assertTrue(markdown.contains("| 1 | 需求任务可进入多 Agent 工作流 | NOT_RUN |"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | NOT_RUN |"));
        assertTrue(markdown.contains("本机 Maven 回归只作为代码质量证据，不计入生产验收点通过"));
        assertTrue(json.contains("\"passedCount\":0"));
        assertTrue(json.contains("\"failedCount\":0"));
        assertTrue(json.contains("\"notRunCount\":15"));
    }

    @Test
    void shouldCarrySkippedFullSmokeMissingRequirementsIntoLedgerRows() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("multi-agent-production-acceptance-20260704-073845.json"),
                """
                        {
                          "conclusion": "SKIPPED",
                          "missingRequirements": [
                            "rd.multi-agent.smoke.provider-preflight-evidence-json=PASSED ProviderPreflightRealSmokeTest JSON",
                            "rd.multi-agent.smoke.secret-scan-needles"
                          ],
                          "matrix": [
                            {
                              "number": 1,
                              "title": "需求任务可进入多 Agent 工作流",
                              "status": "NOT_RUN",
                              "evidence": "缺少真实生产前置条件"
                            },
                            {
                              "number": 15,
                              "title": "生产真实测试结论要求",
                              "status": "NOT_RUN",
                              "evidence": "缺少真实生产前置条件"
                            }
                          ]
                        }
                        """
        );
        ProductionAcceptanceEvidenceLedger ledger = new ProductionAcceptanceEvidenceLedger(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = ledger.write(evidenceRoot);

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("missing=rd.multi-agent.smoke.provider-preflight-evidence-json"));
        assertTrue(markdown.contains("rd.multi-agent.smoke.secret-scan-needles"));
        assertTrue(json.contains("rd.multi-agent.smoke.provider-preflight-evidence-json"));
        assertTrue(json.contains("rd.multi-agent.smoke.secret-scan-needles"));
    }

    private static String passedFullProductionMatrix() {
        StringBuilder matrix = new StringBuilder();
        for (int point = 1; point <= 15; point++) {
            if (point > 1) {
                matrix.append(",\n");
            }
            matrix.append("""
                            {
                              "number": %d,
                              "title": "%s",
                              "status": "PASSED",
                              "evidence": "taskId=task-prod-1, PR=https://github.com/acme/rd-bot/pull/99"
                            }""".formatted(point, title(point)));
        }
        return """
                {
                  "conclusion": "PASSED_FULL_PRODUCTION_ACCEPTANCE",
                  "multiAgentProductionEvidenceValidated": true,
                  "taskId": "task-prod-1",
                  "matrix": [
                %s
                  ]
                }
                """.formatted(matrix);
    }

    private static String title(int point) {
        return switch (point) {
            case 1 -> "需求任务可进入多 Agent 工作流";
            case 2 -> "角色上下文包真实落库且内容不同";
            case 3 -> "需求评审 Agent 能阻断不可交付需求";
            case 4 -> "方案 Agent 产出可执行开发方案";
            case 5 -> "多 provider 降级重试真实生效";
            case 6 -> "编码 Agent 在 Docker 中真实改代码并运行测试";
            case 7 -> "QA Agent 逐条验收并阻断失败交付";
            case 8 -> "交付复核通过后才提交为已交付";
            case 9 -> "状态机可恢复且不会重复派发";
            case 10 -> "错误通知真实送达 Feishu";
            case 11 -> "Skill 安装和使用受策略控制";
            case 12 -> "经验自动沉淀且可被后续 RAG 检索";
            case 13 -> "密钥和敏感信息不进入产物";
            case 14 -> "指标和审计可观测";
            case 15 -> "生产真实测试结论要求";
            default -> throw new IllegalArgumentException("unknown point " + point);
        };
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-04T08:20:00Z"), ZoneOffset.UTC);
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }
}
