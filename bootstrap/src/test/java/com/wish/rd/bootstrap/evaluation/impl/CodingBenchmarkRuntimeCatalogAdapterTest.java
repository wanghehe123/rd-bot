package com.wish.rd.bootstrap.evaluation.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingBenchmarkRuntimeCatalogAdapterTest {

    @Test
    void shouldRewritePlaceholderMavenTestSelectorToFailToPassIds() {
        List<String> rewritten = CodingBenchmarkRuntimeCatalogAdapter.rewriteOracleTestSelector(
                List.of("mvn", "-B", "-q", "test", "-pl", "core",
                        "-Dtest=AgentBenchmarkOracleTest", "-Dsurefire.failIfNoTests=false"),
                List.of("com.alibaba.fastjson2.issues_2200.Issue2269")
        );

        assertTrue(rewritten.contains("-Dtest=com.alibaba.fastjson2.issues_2200.Issue2269"));
        assertTrue(rewritten.contains("-Dsurefire.failIfNoTests=true"));
        assertTrue(rewritten.contains("-o"));
        assertTrue(rewritten.stream().noneMatch(arg -> arg.contains("AgentBenchmarkOracleTest")));
        assertTrue(rewritten.stream().noneMatch("-q"::equals));
    }

    @Test
    void shouldPreferJava21ToolchainAsOracleImageForJavaCases() {
        String image = CodingBenchmarkRuntimeCatalogAdapter.resolveOracleImage(
                "JAVA",
                "alibaba__fastjson2-2285",
                List.of(
                        "rd-bot/coding-eval-agent@sha256:" + "a".repeat(64),
                        "rd-bot/coding-eval-java17@sha256:" + "b".repeat(64),
                        "rd-bot/coding-eval-java21@sha256:" + "c".repeat(64)
                ),
                "rd-bot/coding-eval-oracle@sha256:" + "d".repeat(64)
        );

        assertTrue(image.contains("coding-eval-java21"));
    }

    @Test
    void shouldPreferJava17ToolchainForMockitoCases() {
        String image = CodingBenchmarkRuntimeCatalogAdapter.resolveOracleImage(
                "JAVA",
                "mockito__mockito-3133",
                List.of(
                        "rd-bot/coding-eval-java17@sha256:" + "b".repeat(64),
                        "rd-bot/coding-eval-java21@sha256:" + "c".repeat(64)
                ),
                "rd-bot/coding-eval-oracle@sha256:" + "d".repeat(64)
        );

        assertEquals("rd-bot/coding-eval-java17@sha256:" + "b".repeat(64), image);
    }
}
