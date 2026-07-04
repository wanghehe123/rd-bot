package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubPullRequestRemoteEvidenceRealSmokePreconditionsTest {

    private static final List<String> SMOKE_SYSTEM_PROPERTIES = List.of(
            "rd.github.pr-evidence.production-evidence",
            "rd.github.pr-evidence.rd-bot-version",
            "rd.github.pr-evidence.environment-id",
            "rd.github.pr-evidence.executed-by",
            "rd.github.pr-evidence.task-id",
            "rd.github.pr-evidence.repo-owner",
            "rd.github.pr-evidence.repo-name",
            "rd.github.pr-evidence.base-branch",
            "rd.github.pr-evidence.work-branch",
            "rd.github.pr-evidence.pull-number",
            "rd.github.pr-evidence.secret-scan-needles",
            "rd.github.pr-evidence.report-dir",
            "rd.github.pr-evidence.pat-token"
    );

    @TempDir
    Path reportRoot;

    @Test
    void shouldReportMissingRemotePrEvidencePropertiesTogether() {
        assertEquals(
                List.of(
                        "rd.github.pr-evidence.production-evidence",
                        "rd.github.pr-evidence.rd-bot-version",
                        "rd.github.pr-evidence.environment-id",
                        "rd.github.pr-evidence.executed-by",
                        "rd.github.pr-evidence.task-id",
                        "rd.github.pr-evidence.repo-owner",
                        "rd.github.pr-evidence.repo-name",
                        "rd.github.pr-evidence.work-branch",
                        "rd.github.pr-evidence.pull-number",
                        "rd.github.pr-evidence.secret-scan-needles",
                        "env:GITHUB_PAT|rd.github.pr-evidence.pat-token"
                ),
                GitHubPullRequestRemoteEvidenceRealSmokeTest.missingRequiredProperties(Map.of(), Map.of())
        );
    }

    @Test
    void shouldRejectWeakProductionEvidenceAndUnsafeSecretScanInput() {
        assertEquals(
                List.of(
                        "rd.github.pr-evidence.production-evidence=true",
                        "rd.github.pr-evidence.secret-scan-needles"
                ),
                GitHubPullRequestRemoteEvidenceRealSmokeTest.missingRequiredProperties(
                        Map.ofEntries(
                                Map.entry("rd.github.pr-evidence.production-evidence", "false"),
                                Map.entry("rd.github.pr-evidence.rd-bot-version", "0.1.0-smoke"),
                                Map.entry("rd.github.pr-evidence.environment-id", "prod-a"),
                                Map.entry("rd.github.pr-evidence.executed-by", "qa-runner"),
                                Map.entry("rd.github.pr-evidence.task-id", "task-remote-pr"),
                                Map.entry("rd.github.pr-evidence.repo-owner", "acme"),
                                Map.entry("rd.github.pr-evidence.repo-name", "rd-bot-smoke"),
                                Map.entry("rd.github.pr-evidence.work-branch", "requirement/task-remote-pr"),
                                Map.entry("rd.github.pr-evidence.pull-number", "42"),
                                Map.entry("rd.github.pr-evidence.secret-scan-needles", " , "),
                                Map.entry("rd.github.pr-evidence.pat-token", "secret")
                        ),
                        Map.of()
                )
        );
    }

    @Test
    void shouldRejectWorkBranchThatDoesNotMatchRequirementTaskBranch() {
        assertEquals(
                List.of("rd.github.pr-evidence.work-branch=requirement/<task-id>"),
                GitHubPullRequestRemoteEvidenceRealSmokeTest.missingRequiredProperties(
                        Map.ofEntries(
                                Map.entry("rd.github.pr-evidence.production-evidence", "true"),
                                Map.entry("rd.github.pr-evidence.rd-bot-version", "0.1.0-smoke"),
                                Map.entry("rd.github.pr-evidence.environment-id", "prod-a"),
                                Map.entry("rd.github.pr-evidence.executed-by", "qa-runner"),
                                Map.entry("rd.github.pr-evidence.task-id", "task-remote-pr"),
                                Map.entry("rd.github.pr-evidence.repo-owner", "acme"),
                                Map.entry("rd.github.pr-evidence.repo-name", "rd-bot-smoke"),
                                Map.entry("rd.github.pr-evidence.work-branch", "hotfix/task-remote-pr"),
                                Map.entry("rd.github.pr-evidence.pull-number", "42"),
                                Map.entry("rd.github.pr-evidence.secret-scan-needles", "postgres-secret"),
                                Map.entry("rd.github.pr-evidence.pat-token", "secret")
                        ),
                        Map.of()
                )
        );
    }

    @Test
    void shouldWriteSkippedReportWhenRemotePrEvidencePreconditionsAreMissing() throws Throwable {
        withClearedSmokeProperties(reportRoot, () -> {
            AssertionError failure = assertThrows(
                    AssertionError.class,
                    () -> new GitHubPullRequestRemoteEvidenceRealSmokeTest().fetchesAndValidatesRemotePullRequestBody()
            );

            assertTrue(failure.getMessage().contains("github-pr-remote-evidence production smoke requires real properties"));
            assertTrue(failure.getMessage().contains("report="));
            List<Path> reports;
            try (Stream<Path> files = Files.list(reportRoot)) {
                reports = files
                        .filter(path -> path.getFileName().toString()
                                .startsWith("github-pr-remote-evidence-production-acceptance-"))
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .toList();
            }
            assertEquals(1, reports.size());
            String markdown = Files.readString(reports.get(0));
            assertTrue(markdown.contains("# RD-Bot GitHub PR 远端反查生产验收报告"));
            assertTrue(markdown.contains("结论：SKIPPED"));
            assertTrue(markdown.contains("rd.github.pr-evidence.task-id"));
            assertTrue(markdown.contains("rd.github.pr-evidence.secret-scan-needles"));
            assertTrue(markdown.contains("GitHubPullRequestRemoteEvidenceRealSmokeTest"));
            assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        });
    }

    private static void withClearedSmokeProperties(Path reportRoot, ThrowingBlock runnable) throws Throwable {
        Map<String, String> previousValues = new java.util.LinkedHashMap<>();
        for (String property : SMOKE_SYSTEM_PROPERTIES) {
            previousValues.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
        System.setProperty("rd.github.pr-evidence.report-dir", reportRoot.toString());
        try {
            runnable.run();
        } finally {
            for (Map.Entry<String, String> previousValue : previousValues.entrySet()) {
                if (previousValue.getValue() == null) {
                    System.clearProperty(previousValue.getKey());
                } else {
                    System.setProperty(previousValue.getKey(), previousValue.getValue());
                }
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingBlock {
        void run() throws Throwable;
    }
}
