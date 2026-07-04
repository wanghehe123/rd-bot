package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerCodingRealSmokePreconditionsTest {

    @Test
    void shouldListMissingRequiredDockerCodingSmokeProperties() {
        java.util.List<String> missing = DockerCodingRealSmokeTest.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.docker-coding.smoke.production-evidence"));
        assertTrue(missing.contains("rd.docker-coding.smoke.base-url"));
        assertTrue(missing.contains("rd.docker-coding.smoke.postgres-url"));
        assertTrue(missing.contains("rd.docker-coding.smoke.repository-url"));
        assertTrue(missing.contains("rd.docker-coding.smoke.task-id"));
        assertTrue(missing.contains("rd.docker-coding.smoke.patch-artifact-uri"));
        assertTrue(missing.contains("rd.docker-coding.smoke.result-artifact-uri"));
        assertTrue(missing.contains("rd.docker-coding.smoke.test-log-artifact-uri"));
        assertTrue(missing.contains("rd.docker-coding.smoke.docker-metadata-artifact-uri"));
    }
}
