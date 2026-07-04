package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubPullRequestRemoteEvidenceReportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path reportRoot;

    @Test
    void shouldWritePassedReportAndJsonForRemotePrBodyEvidence() throws Exception {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        Path reportPath = report.writePassed(validEvidence());
        Path jsonPath = reportPath.resolveSibling(reportPath.getFileName().toString().replace(".md", ".json"));

        String markdown = Files.readString(reportPath);
        JsonNode json = OBJECT_MAPPER.readTree(jsonPath.toFile());
        assertTrue(markdown.contains("# RD-Bot GitHub PR 远端反查生产验收报告"));
        assertTrue(markdown.contains("结论：PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE"));
        assertTrue(markdown.contains("taskId：task-remote-pr"));
        assertTrue(markdown.contains("pullRequestBodyIncludesDeliveryReview：true"));
        assertTrue(markdown.contains("pullRequestBodyIncludesQaEvidence：true"));
        assertTrue(markdown.contains("pullRequestBodyContainsTaskId：true"));
        assertTrue(markdown.contains("pullRequestBodyContainsArtifactLink：true"));
        assertTrue(markdown.contains("secretLeakFound：false"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertFalse(markdown.contains("secret-value"));
        assertTrue(Files.isRegularFile(jsonPath));
        assertTrue(json.path("githubPrRemoteEvidenceValidated").asBoolean(false));
        assertTrue(json.path("remotePrTraceValidated").asBoolean(false));
        assertTrue(json.path("pullRequestBodyIncludesDeliveryReview").asBoolean(false));
        assertTrue(json.path("pullRequestBodyIncludesQaEvidence").asBoolean(false));
        assertTrue(json.path("pullRequestBodyContainsTaskId").asBoolean(false));
        assertTrue(json.path("pullRequestBodyContainsExactTaskId").asBoolean(false));
        assertTrue(json.path("pullRequestBodyContainsArtifactLink").asBoolean(false));
        assertTrue(json.path("secretScanEvidenceValidated").asBoolean(false));
        assertTrue(json.path("secretLeakFound").asBoolean(true) == false);
        assertTrue(json.path("secretScannedValueCount").asInt(0) == 1);
        assertTrue(json.path("taskId").asText("").equals("task-remote-pr"));
    }

    @Test
    void shouldWriteFailedReportWithoutLeakingPrBodySecrets() throws Exception {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        Path reportPath = report.writeFailed(
                validEvidence().withBody("RD-Bot QA Evidence leaked secret-value"),
                new AssertionError("remote PR body leaked secret-value")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("结论：FAILED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE"));
        assertTrue(markdown.contains("[REDACTED]"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | FAILED |"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | FAILED |"));
        assertFalse(markdown.contains("secret-value"));
    }

    @Test
    void shouldRejectPassedReportWhenBodyLacksQaEvidence() {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidence().withBody("""
                        ## RD-Bot Delivery Review
                        task-remote-pr
                        https://artifact.example.com/task-remote-pr/report.json
                        """))
        );

        assertTrue(failure.getMessage().contains("RD-Bot QA Evidence"));
    }

    @Test
    void shouldRejectPassedReportWhenPullRequestUrlIsNotHttp() {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidenceWithPullRequestUrl(
                        "ftp://github.com/acme/rd-bot-smoke/pull/42"
                ))
        );

        assertTrue(failure.getMessage().contains("pullRequestUrl"));
    }

    @Test
    void shouldRejectPassedReportWhenPullRequestUrlHostIsNotGitHub() {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidenceWithPullRequestUrl(
                        "https://evil.example/acme/rd-bot-smoke/pull/42"
                ))
        );

        assertTrue(failure.getMessage().contains("pullRequestUrl"));
    }

    @Test
    void shouldRejectPassedReportWhenPullRequestUrlDoesNotEndWithNumberSegment() {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidenceWithPullRequestUrl(
                        "https://github.com/acme/rd-bot-smoke/pull/not-a-pr/42"
                ))
        );

        assertTrue(failure.getMessage().contains("pullRequestUrl"));
    }

    @Test
    void shouldRejectPassedReportWhenWorkBranchIsNotRequirementTaskBranch() {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidenceWithWorkBranch("hotfix/task-remote-pr"))
        );

        assertTrue(failure.getMessage().contains("workBranch"));
    }

    @Test
    void shouldRejectPassedReportWhenBodyOnlyContainsTaskIdAsPrefixOfAnotherValue() {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidenceWithBody("""
                        ## RD-Bot Delivery Review
                        taskId: task-remote-pr-extra
                        reviewArtifact: https://artifact.example.com/task-remote-pr-extra/review.json

                        ## RD-Bot QA Evidence
                        acceptance: PASSED
                        log: https://artifact.example.com/task-remote-pr-extra/qa.log
                        """))
        );

        assertTrue(failure.getMessage().contains("taskId"));
    }

    @Test
    void shouldRejectPassedReportWhenBodyOnlyContainsGenericWebUrlWithoutArtifactEvidenceLine() {
        GitHubPullRequestRemoteEvidenceReport report =
                new GitHubPullRequestRemoteEvidenceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidenceWithBody("""
                        ## RD-Bot Delivery Review
                        taskId: task-remote-pr
                        reference: https://example.com/status/task-remote-pr

                        ## RD-Bot QA Evidence
                        acceptance: PASSED
                        summary: verified by remote checklist
                        """))
        );

        assertTrue(failure.getMessage().contains("artifact link"));
    }

    private static GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence validEvidence() {
        return validEvidenceWithPullRequestUrl("https://github.com/acme/rd-bot-smoke/pull/42");
    }

    private static GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence validEvidenceWithPullRequestUrl(
            String pullRequestUrl
    ) {
        return validEvidence(
                pullRequestUrl,
                "requirement/task-remote-pr"
        );
    }

    private static GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence validEvidenceWithWorkBranch(
            String workBranch
    ) {
        return validEvidence(
                "https://github.com/acme/rd-bot-smoke/pull/42",
                workBranch
        );
    }

    private static GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence validEvidenceWithBody(String body) {
        return new GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence(
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                "task-remote-pr",
                "acme",
                "rd-bot-smoke",
                "main",
                "requirement/task-remote-pr",
                "https://github.com/acme/rd-bot-smoke/pull/42",
                "42",
                body,
                List.of("secret-value")
        );
    }

    private static GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence validEvidence(
            String pullRequestUrl,
            String workBranch
    ) {
        return new GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence(
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                "task-remote-pr",
                "acme",
                "rd-bot-smoke",
                "main",
                workBranch,
                pullRequestUrl,
                "42",
                """
                        ## RD-Bot Delivery Review
                        taskId: task-remote-pr
                        reviewArtifact: https://artifact.example.com/task-remote-pr/review.json

                        ## RD-Bot QA Evidence
                        acceptance: PASSED
                        log: https://artifact.example.com/task-remote-pr/qa.log
                        """,
                List.of("secret-value")
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-01T02:03:04Z"), ZoneOffset.UTC);
    }
}
