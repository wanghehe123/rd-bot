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

class GitHubCodePlatformProductionAcceptanceReportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path reportRoot;

    @Test
    void shouldWritePassedReportAndJsonForRealGitHubPrSmoke() throws Exception {
        GitHubCodePlatformProductionAcceptanceReport report =
                new GitHubCodePlatformProductionAcceptanceReport(reportRoot, fixedClock());
        GitHubCodePlatformProductionAcceptanceReport.GitHubPrSmokeEvidence evidence =
                validEvidence("https://github.com/acme/rd-bot-smoke/pull/7", "7");

        Path reportPath = report.writePassed(evidence);
        Path jsonPath = reportPath.resolveSibling(reportPath.getFileName().toString().replace(".md", ".json"));

        String markdown = Files.readString(reportPath);
        JsonNode json = OBJECT_MAPPER.readTree(jsonPath.toFile());
        assertTrue(markdown.contains("# RD-Bot GitHub PR 生产验收报告"));
        assertTrue(markdown.contains("结论：PASSED_GITHUB_PR_SMOKE"));
        assertTrue(markdown.contains("RD-Bot 版本：0.1.0-smoke"));
        assertTrue(markdown.contains("生产环境标识：prod-equivalent-a"));
        assertTrue(markdown.contains("执行人：qa-runner"));
        assertTrue(markdown.contains("repository：acme/rd-bot-smoke"));
        assertTrue(markdown.contains("pullRequestUrl：https://github.com/acme/rd-bot-smoke/pull/7"));
        assertTrue(markdown.contains("githubPrEvidenceValidated：true"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
        assertTrue(markdown.contains("PR 创建子证据"));
        assertFalse(markdown.contains("secret-token"));
        assertTrue(Files.isRegularFile(jsonPath));
        assertTrue(json.path("githubPrEvidenceValidated").asBoolean(false));
        assertTrue(json.path("pullRequestPublicationSucceeded").asBoolean(false));
        assertTrue(json.path("repoOwner").asText("").equals("acme"));
        assertTrue(json.path("repoName").asText("").equals("rd-bot-smoke"));
        assertTrue(json.path("pullRequestUrl").asText("").equals("https://github.com/acme/rd-bot-smoke/pull/7"));
        assertTrue(json.path("pullRequestNumber").asText("").equals("7"));
        assertTrue(json.path("rdBotVersion").asText("").equals("0.1.0-smoke"));
        assertTrue(json.path("environmentId").asText("").equals("prod-equivalent-a"));
        assertTrue(json.path("executedBy").asText("").equals("qa-runner"));
    }

    @Test
    void shouldWriteFailedReportWithoutLeakingGitHubToken() throws Exception {
        GitHubCodePlatformProductionAcceptanceReport report =
                new GitHubCodePlatformProductionAcceptanceReport(reportRoot, fixedClock());

        Path reportPath = report.writeFailed(
                validEvidence("", ""),
                new AssertionError("GitHub rejected secret-token")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("结论：FAILED_GITHUB_PR_SMOKE"));
        assertTrue(markdown.contains("[REDACTED]"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | FAILED |"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | FAILED |"));
        assertFalse(markdown.contains("secret-token"));
    }

    @Test
    void shouldRejectPassedReportWhenPullRequestUrlDoesNotMatchConfiguredRepository() {
        GitHubCodePlatformProductionAcceptanceReport report =
                new GitHubCodePlatformProductionAcceptanceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidence("https://github.com/other/repo/pull/7", "7"))
        );

        assertTrue(failure.getMessage().contains("pullRequestUrl"));
    }

    @Test
    void shouldRejectPassedReportWhenPullRequestUrlIsNotHttp() {
        GitHubCodePlatformProductionAcceptanceReport report =
                new GitHubCodePlatformProductionAcceptanceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidence("ssh://github.com/acme/rd-bot-smoke/pull/7", "7"))
        );

        assertTrue(failure.getMessage().contains("pullRequestUrl"));
    }

    @Test
    void shouldRejectPassedReportWhenPullRequestUrlHostIsNotGitHub() {
        GitHubCodePlatformProductionAcceptanceReport report =
                new GitHubCodePlatformProductionAcceptanceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidence("https://evil.example/acme/rd-bot-smoke/pull/7", "7"))
        );

        assertTrue(failure.getMessage().contains("pullRequestUrl"));
    }

    @Test
    void shouldRejectPassedReportWhenPullRequestUrlDoesNotEndWithNumberSegment() {
        GitHubCodePlatformProductionAcceptanceReport report =
                new GitHubCodePlatformProductionAcceptanceReport(reportRoot, fixedClock());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(validEvidence("https://github.com/acme/rd-bot-smoke/pull/not-a-pr/7", "7"))
        );

        assertTrue(failure.getMessage().contains("pullRequestUrl"));
    }

    private static GitHubCodePlatformProductionAcceptanceReport.GitHubPrSmokeEvidence validEvidence(
            String pullRequestUrl,
            String pullRequestNumber
    ) {
        return new GitHubCodePlatformProductionAcceptanceReport.GitHubPrSmokeEvidence(
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                "acme",
                "rd-bot-smoke",
                "main",
                "repair/smoke",
                pullRequestUrl,
                pullRequestNumber,
                true,
                List.of("secret-token")
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-01T02:03:04Z"), ZoneOffset.UTC);
    }
}
