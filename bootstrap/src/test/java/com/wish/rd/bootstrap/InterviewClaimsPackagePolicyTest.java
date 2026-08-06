package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-8: interview claim numbers must live under benchmarks/interview-claims with reproducible layout.
 */
class InterviewClaimsPackagePolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path PACKAGE = PROJECT_ROOT.resolve("benchmarks/interview-claims");

    @Test
    void shouldProvideInterviewClaimsPackageLayout() throws Exception {
        assertTrue(Files.isDirectory(PACKAGE), "missing " + PACKAGE);
        assertTrue(Files.exists(PACKAGE.resolve("README.md")));
        assertTrue(Files.exists(PACKAGE.resolve("metrics.json")));
        assertTrue(Files.exists(PACKAGE.resolve("datasets/v0/cases.jsonl")));
        assertTrue(Files.isDirectory(PACKAGE.resolve("runners")));
        assertTrue(Files.exists(PACKAGE.resolve("runners/verify-package.sh")));
        assertTrue(Files.isDirectory(PACKAGE.resolve("raw")));
        assertTrue(Files.isDirectory(PACKAGE.resolve("reports")));
        assertTrue(Files.isDirectory(PACKAGE.resolve("faults")));

        String metrics = Files.readString(PACKAGE.resolve("metrics.json"));
        assertTrue(metrics.contains("待复现") || metrics.contains("\"status\": \"待复现\"")
                || metrics.contains("unreproducedLabel"));
        assertTrue(metrics.contains("requireRawRunForNumericClaim"));

        List<String> cases = Files.readAllLines(PACKAGE.resolve("datasets/v0/cases.jsonl"));
        assertFalse(cases.isEmpty(), "cases.jsonl must list Q scenarios");
        assertTrue(cases.stream().anyMatch(line -> line.contains("Q8-metrics-package")));

        try (Stream<Path> faults = Files.list(PACKAGE.resolve("faults"))) {
            long faultDirs = faults.filter(Files::isDirectory).count();
            assertTrue(faultDirs >= 7, "expected fault id directories under faults/");
        }
    }
}
