package com.wish.rd.exec.repair.qa;

import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;

import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaRepositoryProfileDetectorTest {

    @TempDir
    Path repository;

    private final QaRepositoryProfileDetector detector = new QaRepositoryProfileDetector();

    @Test
    void shouldPreferTaskOverrideOverProjectAndRepositoryProfiles() throws Exception {
        Files.createDirectories(repository.resolve(".rd-bot"));
        Files.writeString(repository.resolve(".rd-bot/qa-profile.json"), profile("http://repo:3000"));
        RepairJobCommand command = command(Map.of(
                "qaTaskOverrideJson", profile("http://127.0.0.1:4100"),
                "qaProjectProfileJson", profile("http://project:3000")
        ));

        QaExecutionProfile profile = detector.detect(command, repository);

        assertTrue(profile.browserRequired());
        assertEquals("TASK_OVERRIDE", profile.decisionSource());
        assertEquals("http://127.0.0.1:4100", profile.baseUrl());
    }

    @Test
    void shouldUseProjectProfileBeforeRepositoryConfig() throws Exception {
        Files.createDirectories(repository.resolve(".rd-bot"));
        Files.writeString(repository.resolve(".rd-bot/qa-profile.json"), profile("http://repo:3000"));

        QaExecutionProfile profile = detector.detect(command(Map.of(
                "qaProjectProfileJson", profile("http://127.0.0.1:4200")
        )), repository);

        assertEquals("PROJECT_PROFILE", profile.decisionSource());
        assertEquals("http://127.0.0.1:4200", profile.baseUrl());
    }

    @Test
    void shouldCarryGovernedRegressionCommandsIntoResolvedQaProfile() {
        QaExecutionProfile profile = detector.detect(command(Map.of(
                "qaTaskOverrideJson", """
                        {
                          "mode": "REQUIRED",
                          "baseUrl": "http://127.0.0.1:4173",
                          "startCommand": "npm run preview -- --host 0.0.0.0",
                          "healthPath": "/health",
                          "allowedHosts": ["127.0.0.1"],
                          "regressionCommands": ["npm test", "npm run typecheck"]
                        }
                        """
        )), repository);

        assertEquals(java.util.List.of("npm test", "npm run typecheck"), profile.regressionCommands());
        assertTrue(detector.toJson(profile).contains("regressionCommands"));
    }

    @Test
    void shouldAutoDetectViteWebProjectWhenNoExplicitProfileExists() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {"dev": "vite"},
                  "dependencies": {"react": "latest", "vite": "latest"}
                }
                """);

        QaExecutionProfile profile = detector.detect(command(Map.of()), repository);

        assertTrue(profile.browserRequired());
        assertFalse(profile.ambiguous());
        assertEquals("AUTO_DETECTION", profile.decisionSource());
        assertEquals("http://127.0.0.1:5173", profile.baseUrl());
        assertTrue(profile.startCommand().contains("npm run dev"));
        assertTrue(profile.allowedHosts().contains("127.0.0.1"));
    }

    @Test
    void shouldAutoDetectNestedViteProjectWithRepositoryStartScript() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "dev": "concurrently npm:dev:server npm:dev:client",
                    "dev:client": "cd client && npm run dev"
                  },
                  "devDependencies": {"concurrently": "latest"}
                }
                """);
        Files.writeString(repository.resolve("start.sh"), "#!/bin/sh\ncd client && npm run dev\n");
        Files.createDirectories(repository.resolve("client"));
        Files.writeString(repository.resolve("client/package.json"), """
                {"scripts":{"dev":"vite"},"devDependencies":{"vite":"latest"}}
                """);

        QaExecutionProfile profile = detector.detect(command(Map.of()), repository);

        assertTrue(profile.browserRequired());
        assertFalse(profile.ambiguous());
        assertEquals("AUTO_DETECTION", profile.decisionSource());
        assertEquals("http://127.0.0.1:5173", profile.baseUrl());
        assertEquals("./start.sh", profile.startCommand());
        assertTrue(profile.allowedHosts().contains("127.0.0.1"));
    }

    @Test
    void shouldContinueToRepositoryDetectionWhenPersistedProfileModeIsAuto() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {"scripts":{"dev":"vite"},"devDependencies":{"vite":"latest"}}
                """);

        QaExecutionProfile profile = detector.detect(command(Map.of(
                "qaProjectProfileJson", "{\"mode\":\"AUTO\",\"regressionCommands\":[\"npm test\"]}"
        )), repository);

        assertEquals("AUTO_DETECTION", profile.decisionSource());
        assertTrue(profile.browserRequired());
    }

    @Test
    void shouldMarkNonWebRepositoryAsNotApplicable() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), "<project></project>");

        QaExecutionProfile profile = detector.detect(command(Map.of()), repository);

        assertFalse(profile.browserRequired());
        assertFalse(profile.ambiguous());
        assertEquals("NOT_APPLICABLE", profile.decisionSource());
    }

    @Test
    void shouldBlockRepositoryProfileWhoseBaseHostIsOutsideAllowlist() throws Exception {
        Files.createDirectories(repository.resolve(".rd-bot"));
        Files.writeString(repository.resolve(".rd-bot/qa-profile.json"), """
                {
                  "mode": "REQUIRED",
                  "baseUrl": "http://metadata.internal:8080",
                  "startCommand": "npm run dev",
                  "healthPath": "/",
                  "allowedHosts": ["127.0.0.1"]
                }
                """);

        QaExecutionProfile profile = detector.detect(command(Map.of()), repository);

        assertTrue(profile.ambiguous());
        assertEquals("REPOSITORY_CONFIG", profile.decisionSource());
    }

    private static String profile(String baseUrl) {
        String host = URI.create(baseUrl).getHost();
        return """
                {
                  "mode": "REQUIRED",
                  "baseUrl": "%s",
                  "startCommand": "npm run dev -- --host 0.0.0.0",
                  "healthPath": "/",
                  "allowedHosts": ["%s"]
                }
                """.formatted(baseUrl, host).strip();
    }

    private static RepairJobCommand command(Map<String, String> context) {
        return new RepairJobCommand(
                "repair-1",
                "task-1",
                "ticket-1",
                "QA",
                "verify",
                "https://github.com/acme/example.git",
                "acme",
                "example",
                "main",
                "requirement/task-1",
                context,
                Map.of("repositoryPublishRequired", "false")
        );
    }
}
