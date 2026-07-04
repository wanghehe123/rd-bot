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

class GitHubCodePlatformRealSmokePreconditionsTest {

    private static final List<String> SMOKE_SYSTEM_PROPERTIES = List.of(
            "rd.github.smoke.production-evidence",
            "rd.github.smoke.rd-bot-version",
            "rd.github.smoke.environment-id",
            "rd.github.smoke.executed-by",
            "rd.github.smoke.repo-owner",
            "rd.github.smoke.repo-name",
            "rd.github.smoke.base-branch",
            "rd.github.smoke.work-branch",
            "rd.github.smoke.report-dir",
            "rd.github.code-platform.pat-token"
    );

    @TempDir
    Path reportRoot;

    @Test
    void shouldReportMissingRealGitHubSmokePropertiesTogether() {
        assertEquals(
                java.util.List.of(
                        "rd.github.smoke.production-evidence",
                        "rd.github.smoke.rd-bot-version",
                        "rd.github.smoke.environment-id",
                        "rd.github.smoke.executed-by",
                        "rd.github.smoke.repo-owner",
                        "rd.github.smoke.repo-name",
                        "rd.github.smoke.work-branch",
                        "env:GITHUB_PAT|rd.github.code-platform.pat-token"
                ),
                GitHubCodePlatformRealSmokeTest.missingRequiredProperties(Map.of(), Map.of())
        );
    }

    @Test
    void shouldAcceptSystemPropertyTokenAsLocalSmokeFallback() {
        assertEquals(
                java.util.List.of(),
                GitHubCodePlatformRealSmokeTest.missingRequiredProperties(
                        Map.of(
                                "rd.github.smoke.production-evidence", "true",
                                "rd.github.smoke.rd-bot-version", "0.1.0-smoke",
                                "rd.github.smoke.environment-id", "prod-a",
                                "rd.github.smoke.executed-by", "qa-runner",
                                "rd.github.smoke.repo-owner", "acme",
                                "rd.github.smoke.repo-name", "rd-bot-smoke",
                                "rd.github.smoke.work-branch", "repair/smoke",
                                "rd.github.code-platform.pat-token", "secret"
                        ),
                        Map.of()
                )
        );
    }

    @Test
    void shouldRequireProductionEvidenceAndDistinctBranchesForRealPrSmoke() {
        assertEquals(
                java.util.List.of(
                        "rd.github.smoke.production-evidence=true",
                        "rd.github.smoke.work-branch!=rd.github.smoke.base-branch"
                ),
                GitHubCodePlatformRealSmokeTest.missingRequiredProperties(
                        Map.of(
                                "rd.github.smoke.production-evidence", "false",
                                "rd.github.smoke.rd-bot-version", "0.1.0-smoke",
                                "rd.github.smoke.environment-id", "prod-a",
                                "rd.github.smoke.executed-by", "qa-runner",
                                "rd.github.smoke.repo-owner", "acme",
                                "rd.github.smoke.repo-name", "rd-bot-smoke",
                                "rd.github.smoke.base-branch", "main",
                                "rd.github.smoke.work-branch", "main",
                                "rd.github.code-platform.pat-token", "secret"
                        ),
                        Map.of()
                )
        );
    }

    @Test
    void shouldRequireTraceableProductionMetadataForRealPrSmoke() {
        assertEquals(
                java.util.List.of(
                        "rd.github.smoke.rd-bot-version",
                        "rd.github.smoke.environment-id",
                        "rd.github.smoke.executed-by"
                ),
                GitHubCodePlatformRealSmokeTest.missingRequiredProperties(
                        Map.of(
                                "rd.github.smoke.production-evidence", "true",
                                "rd.github.smoke.repo-owner", "acme",
                                "rd.github.smoke.repo-name", "rd-bot-smoke",
                                "rd.github.smoke.work-branch", "repair/smoke",
                                "rd.github.code-platform.pat-token", "secret"
                        ),
                        Map.of()
                )
        );
    }

    @Test
    void shouldWriteSkippedReportWhenRealPrSmokePreconditionsAreMissing() throws Throwable {
        withClearedSmokeProperties(reportRoot, () -> {
            AssertionError failure = assertThrows(
                    AssertionError.class,
                    () -> new GitHubCodePlatformRealSmokeTest().createsPullRequestWithExistingJavaAdapter()
            );

            assertTrue(failure.getMessage().contains("github-code-platform production smoke requires real properties"));
            assertTrue(failure.getMessage().contains("report="));
            List<Path> reports;
            try (Stream<Path> files = Files.list(reportRoot)) {
                reports = files
                        .filter(path -> path.getFileName().toString()
                                .startsWith("github-code-platform-production-acceptance-"))
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .toList();
            }
            assertEquals(1, reports.size());
            String markdown = Files.readString(reports.get(0));
            assertTrue(markdown.contains("# RD-Bot GitHub PR 生产验收报告"));
            assertTrue(markdown.contains("结论：SKIPPED"));
            assertTrue(markdown.contains("rd.github.smoke.production-evidence"));
            assertTrue(markdown.contains("rd.github.smoke.rd-bot-version"));
            assertTrue(markdown.contains("rd.github.smoke.environment-id"));
            assertTrue(markdown.contains("rd.github.smoke.executed-by"));
            assertTrue(markdown.contains("GitHubCodePlatformRealSmokeTest"));
            assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
        });
    }

    private static void withClearedSmokeProperties(Path reportRoot, ThrowingBlock runnable) throws Throwable {
        Map<String, String> previousValues = new java.util.LinkedHashMap<>();
        for (String property : SMOKE_SYSTEM_PROPERTIES) {
            previousValues.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
        System.setProperty("rd.github.smoke.report-dir", reportRoot.toString());
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
