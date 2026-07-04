package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAcceptanceReadinessReportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteNotReadySnapshotWhenPassedProviderPreflightSidecarIsMissing() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("provider-preflight-production-acceptance-20260704-072431.json"),
                """
                        {
                          "conclusion": "SKIPPED_PROVIDER_PREFLIGHT_SMOKE",
                          "providerPreflightEvidenceValidated": false,
                          "successfulProviderCount": 0
                        }
                        """
        );
        ProductionAcceptanceReadinessReport report = new ProductionAcceptanceReadinessReport(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = report.write(evidenceRoot, Map.of());

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("结论：NOT_READY_FOR_FULL_SMOKE"));
        assertTrue(markdown.contains("Provider preflight PASSED sidecar"));
        assertTrue(json.contains("\"readyForFullSmoke\":false"));
        assertTrue(json.contains("\"missing\""));
    }

    @Test
    void shouldRecognizePassedSidecarsAndNeverLeakEnvValues() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("provider-preflight-production-acceptance-20260704-080000.json"),
                """
                        {
                          "conclusion": "PASSED_PROVIDER_PREFLIGHT_SMOKE",
                          "providerPreflightEvidenceValidated": true,
                          "successfulProviderCount": 2
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("feishu-alert-production-acceptance-20260704-080100.json"),
                """
                        {
                          "conclusion": "PASSED_FEISHU_ALERT_SMOKE",
                          "feishuAlertEvidenceValidated": true,
                          "feishuAlertMetadataComplete": true
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("skill-production-acceptance-20260704-080200.json"),
                """
                        {
                          "skillPolicyEvidenceValidated": true
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("github-pr-remote-evidence-production-acceptance-20260704-080300.json"),
                """
                        {
                          "conclusion": "PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE",
                          "githubPrRemoteEvidenceValidated": true,
                          "remotePrTraceValidated": true
                        }
                        """
        );
        ProductionAcceptanceReadinessReport report = new ProductionAcceptanceReadinessReport(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = report.write(evidenceRoot, Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "MINIMAX_API_KEY", "minimax-secret",
                "GITHUB_PAT", "github-secret",
                "FEISHU_APP_ID", "feishu-app-id",
                "FEISHU_APP_SECRET", "feishu-secret",
                "FEISHU_IM_ALERT_CHAT_ID", "oc-alert",
                "RD_BOT_SECRET_SCAN_NEEDLES", "postgres-secret,github-secret"
        ));

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("provider-preflight-production-acceptance-20260704-080000.json"));
        assertTrue(markdown.contains("Feishu alert PASSED sidecar"));
        assertTrue(markdown.contains("| Skill policy PASSED sidecar | true |"));
        assertTrue(markdown.contains("| GitHub PR remote PASSED sidecar | true |"));
        assertTrue(json.contains("\"providerPreflightReady\":true"));
        assertFalse(markdown.contains("longcat-secret"));
        assertFalse(json.contains("github-secret"));
        assertFalse(json.contains("feishu-secret"));
    }

    @Test
    void shouldNotRequireRefreshOnlyEnvWhenSidecarsAlreadyPassed() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        writePassedSidecars(evidenceRoot);
        ProductionAcceptanceReadinessReport report = new ProductionAcceptanceReadinessReport(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = report.write(evidenceRoot, Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "MINIMAX_API_KEY", "minimax-secret",
                "RD_BOT_SECRET_SCAN_NEEDLES", "postgres-secret,github-secret"
        ));

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("结论：READY_FOR_FULL_SMOKE"));
        assertTrue(json.contains("\"readyForFullSmoke\":true"));
        assertFalse(json.contains("GITHUB_PAT|GH_TOKEN)"));
        assertFalse(json.contains("FEISHU_APP_SECRET)"));
    }

    @Test
    void shouldIncludeMiniMaxCliProbeAsDiagnosticWithoutGatingFullSmoke() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        writePassedSidecars(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("minimax-mmx-cli-real-probe-20260704-075444.json"),
                """
                        {
                          "conclusion": "FAILED_MINIMAX_QUOTA_EXCEEDED",
                          "provider": "minimax",
                          "quotaExceeded": true,
                          "secretRedacted": true,
                          "canCountAsProviderPreflightPass": false
                        }
                        """
        );
        ProductionAcceptanceReadinessReport report = new ProductionAcceptanceReadinessReport(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = report.write(evidenceRoot, Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "MINIMAX_API_KEY", "minimax-secret",
                "RD_BOT_SECRET_SCAN_NEEDLES", "postgres-secret,github-secret"
        ));

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("## 本机诊断"));
        assertTrue(markdown.contains("| MiniMax mmx CLI probe | false | minimax-mmx-cli-real-probe-20260704-075444.json | FAILED_MINIMAX_QUOTA_EXCEEDED |"));
        assertTrue(json.contains("\"diagnostics\""));
        assertTrue(json.contains("\"conclusion\":\"FAILED_MINIMAX_QUOTA_EXCEEDED\""));
        assertTrue(json.contains("\"readyForFullSmoke\":true"));
    }

    @Test
    void shouldExposeLatestProviderPreflightConclusionInReadinessEvidence() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("provider-preflight-production-acceptance-20260704-080001.json"),
                """
                        {
                          "conclusion": "FAILED_PROVIDER_PREFLIGHT_SMOKE",
                          "providerPreflightEvidenceValidated": false,
                          "successfulProviderCount": 0
                        }
                        """
        );
        ProductionAcceptanceReadinessReport report = new ProductionAcceptanceReadinessReport(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = report.write(evidenceRoot, Map.of());

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("| Provider preflight PASSED sidecar | false | provider-preflight-production-acceptance-20260704-080001.json | FAILED_PROVIDER_PREFLIGHT_SMOKE |"));
        assertTrue(json.contains("\"conclusion\":\"FAILED_PROVIDER_PREFLIGHT_SMOKE\""));
    }

    @Test
    void shouldExposeProviderPreflightFailureReasonsInReadiness() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("provider-preflight-production-acceptance-20260704-080657.json"),
                """
                        {
                          "conclusion": "FAILED_PROVIDER_PREFLIGHT_SMOKE",
                          "providerPreflightEvidenceValidated": false,
                          "successfulProviderCount": 0,
                          "providers": [
                            {
                              "name": "long-cat",
                              "httpStatus": 401,
                              "failureReason": "PROVIDER_AUTHENTICATION_FAILED"
                            },
                            {
                              "name": "minimax",
                              "httpStatus": 429,
                              "failureReason": "PROVIDER_QUOTA_OR_RATE_LIMIT"
                            }
                          ]
                        }
                        """
        );
        ProductionAcceptanceReadinessReport report = new ProductionAcceptanceReadinessReport(
                tempDir.resolve("reports"),
                fixedClock()
        );

        Path reportPath = report.write(evidenceRoot, Map.of());

        String markdown = Files.readString(reportPath);
        String json = Files.readString(jsonPath(reportPath));
        assertTrue(markdown.contains("## Provider Preflight Failure Reasons"));
        assertTrue(markdown.contains("| long-cat | 401 | PROVIDER_AUTHENTICATION_FAILED |"));
        assertTrue(markdown.contains("| minimax | 429 | PROVIDER_QUOTA_OR_RATE_LIMIT |"));
        assertTrue(json.contains("\"providerFailures\""));
        assertTrue(json.contains("\"failureReason\":\"PROVIDER_QUOTA_OR_RATE_LIMIT\""));
    }

    private static void writePassedSidecars(Path evidenceRoot) throws Exception {
        Files.writeString(
                evidenceRoot.resolve("provider-preflight-production-acceptance-20260704-080000.json"),
                """
                        {
                          "conclusion": "PASSED_PROVIDER_PREFLIGHT_SMOKE",
                          "providerPreflightEvidenceValidated": true,
                          "successfulProviderCount": 2
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("feishu-alert-production-acceptance-20260704-080100.json"),
                """
                        {
                          "conclusion": "PASSED_FEISHU_ALERT_SMOKE",
                          "feishuAlertEvidenceValidated": true,
                          "feishuAlertMetadataComplete": true
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("skill-production-acceptance-20260704-080200.json"),
                """
                        {
                          "skillPolicyEvidenceValidated": true
                        }
                        """
        );
        Files.writeString(
                evidenceRoot.resolve("github-pr-remote-evidence-production-acceptance-20260704-080300.json"),
                """
                        {
                          "conclusion": "PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE",
                          "githubPrRemoteEvidenceValidated": true,
                          "remotePrTraceValidated": true
                        }
                        """
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), ZoneOffset.UTC);
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }
}
