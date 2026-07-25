package com.wish.rd.bootstrap.executor.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerRuntimeProfileImageBuilderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildAndSmokeVerifyClaudeRuntimeWithoutShellInterpolation() {
        List<List<String>> commands = new ArrayList<>();
        DockerRuntimeProfileImageBuilder builder = new DockerRuntimeProfileImageBuilder(
                temporaryDirectory,
                (argv, environment, timeoutMillis) -> {
                    commands.add(List.copyOf(argv));
                    if (argv.contains("inspect")) {
                        return new ProcessContainerRunner.CommandResult(
                                0,
                                12L,
                                "[\"rd-claude-entrypoint\"]|rdbot",
                                ""
                        );
                    }
                    if (argv.getLast().contains("sha256sum")) {
                        return new ProcessContainerRunner.CommandResult(
                                0,
                                12L,
                                "entrypoint-sha  /usr/local/bin/rd-claude-entrypoint\n"
                                        + "claude-sha  /usr/local/bin/claude\n",
                                ""
                        );
                    }
                    return new ProcessContainerRunner.CommandResult(
                            0,
                            12L,
                            "claude 2.1.0\n/usr/local/bin/rd-claude-entrypoint\nuid=999(rdbot)",
                            ""
                    );
                }
        );

        DockerRuntimeProfileImageBuilder.VerifiedImage verified = builder.buildAndVerify(
                "7486000000000000001",
                "CODING_AGENT",
                "FROM rd-bot/claude-code:local\nRUN apt-get update && apt-get install -y python3\n"
                        .getBytes(StandardCharsets.UTF_8)
        );

        assertTrue(verified.image().startsWith("rd-bot/project-runtime-7486000000000000001-coding_agent-"));
        assertTrue(verified.validationSummary().contains("claude 2.1.0"));
        assertEquals("docker", commands.getFirst().getFirst());
        assertEquals("build", commands.getFirst().get(1));
        assertEquals(List.of("docker", "image", "inspect"), commands.get(1).subList(0, 3));
        assertEquals(verified.image(), commands.get(1).getLast());
        assertTrue(commands.stream().anyMatch(command -> command.getLast().contains("sha256sum")));
        List<String> smokeCommand = commands.stream()
                .filter(command -> command.getLast().contains("claude --version"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("docker", "run", "--rm", "-v"), smokeCommand.subList(0, 4));
        assertTrue(smokeCommand.get(4).endsWith(
                ":/home/rdbot/.claude/skills/rd-bot-runtime-contract:ro"));
        assertEquals("-v", smokeCommand.get(5));
        assertTrue(smokeCommand.get(6).endsWith(":/work/input:ro"));
        assertEquals(verified.image(), smokeCommand.get(7));
        assertEquals("/bin/sh", smokeCommand.get(8));
        assertEquals("-lc", smokeCommand.get(9));
        assertTrue(smokeCommand.getLast().contains("claude --version"));
        assertTrue(smokeCommand.getLast().contains("rd-claude-entrypoint"));
        assertTrue(smokeCommand.getLast().contains("test \"$(id -un)\" = \"rdbot\""));
        assertTrue(smokeCommand.getLast().contains("/home/rdbot/.claude/session-env"));
        assertTrue(smokeCommand.getLast().contains("rd-bot-runtime-contract/SKILL.md"));
    }

    @Test
    void shouldRejectImageThatDoesNotPreserveTheClaudeRuntimeEntrypointAndUser() {
        List<List<String>> commands = new ArrayList<>();
        DockerRuntimeProfileImageBuilder builder = new DockerRuntimeProfileImageBuilder(
                temporaryDirectory,
                (argv, environment, timeoutMillis) -> {
                    commands.add(List.copyOf(argv));
                    return argv.contains("inspect")
                            ? new ProcessContainerRunner.CommandResult(0, 1L, "[\"/bin/sh\"]|root", "")
                            : new ProcessContainerRunner.CommandResult(0, 1L, "", "");
                }
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> builder.buildAndVerify(
                "7486000000000000002",
                "QA_AGENT",
                "FROM rd-bot/claude-code-qa:local\nRUN apt-get update\n".getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(exception.getMessage().contains("runtime contract"));
        assertTrue(exception.getMessage().contains("rdbot"));
        assertEquals(List.of("docker", "image", "rm", "--force"), commands.get(2).subList(0, 4));
    }

    @Test
    void shouldRejectImageThatChangesTrustedClaudeExecutableFiles() {
        List<List<String>> commands = new ArrayList<>();
        DockerRuntimeProfileImageBuilder builder = new DockerRuntimeProfileImageBuilder(
                temporaryDirectory,
                (argv, environment, timeoutMillis) -> {
                    commands.add(List.copyOf(argv));
                    if (argv.contains("inspect")) {
                        return new ProcessContainerRunner.CommandResult(
                                0,
                                1L,
                                "[\"rd-claude-entrypoint\"]|rdbot",
                                ""
                        );
                    }
                    if (argv.getLast().contains("sha256sum")) {
                        String runtimeImage = argv.get(argv.size() - 3);
                        String fingerprint = runtimeImage.startsWith("rd-bot/project-runtime-")
                                ? "changed-entrypoint  /usr/local/bin/rd-claude-entrypoint\nchanged-claude  /usr/local/bin/claude\n"
                                : "trusted-entrypoint  /usr/local/bin/rd-claude-entrypoint\ntrusted-claude  /usr/local/bin/claude\n";
                        return new ProcessContainerRunner.CommandResult(0, 1L, fingerprint, "");
                    }
                    return new ProcessContainerRunner.CommandResult(0, 1L, "", "");
                },
                Set.of("rd-bot/claude-code:local")
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> builder.buildAndVerify(
                "7486000000000000003",
                "CODING_AGENT",
                "FROM rd-bot/claude-code:local\nUSER root\nRUN apt-get update\nUSER rdbot\n"
                        .getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(exception.getMessage().contains("trusted Claude executable files"));
        assertTrue(commands.stream().anyMatch(command -> command.size() == 5
                && command.subList(0, 4).equals(List.of("docker", "image", "rm", "--force"))));
    }
}
