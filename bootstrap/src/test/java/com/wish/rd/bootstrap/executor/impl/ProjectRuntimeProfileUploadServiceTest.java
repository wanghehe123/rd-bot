package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.impl.InMemoryProjectRuntimeProfileStore;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRuntimeProfileUploadServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildVerifyStoreAndPersistUploadedRoleDockerfile() throws Exception {
        DockerRuntimeProfileImageBuilder builder = new DockerRuntimeProfileImageBuilder(
                temporaryDirectory,
                (argv, environment, timeoutMillis) -> argv.contains("inspect")
                        ? new ProcessContainerRunner.CommandResult(0, 1L, "[\"rd-claude-entrypoint\"]|rdbot", "")
                        : new ProcessContainerRunner.CommandResult(0, 1L, "claude 2.1.0", "")
        );
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                storage,
                builder
        );

        ProjectRuntimeProfile profile = service.upload(
                "7486000000000000003",
                "QA_AGENT",
                "CLAUDE_CODE",
                "Dockerfile.python",
                (
                        "FROM rd-bot/claude-code-qa:local\n"
                                + "USER root\n"
                                + "RUN apt-get update && apt-get install -y python3\n"
                                + "USER rdbot\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        assertEquals("VERIFIED", profile.validationStatus());
        assertEquals("QA_AGENT", profile.role());
        assertTrue(profile.dockerfileArtifactUri().startsWith("s3://rd-project-runtime-dockerfiles/"));
        try (InputStream stored = storage.openStream(profile.dockerfileArtifactUri())) {
            assertTrue(new String(stored.readAllBytes(), StandardCharsets.UTF_8).contains("python3"));
        }
    }

    @Test
    void shouldRejectDockerfileThatNeedsUnuploadedBuildContext() {
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                new InMemoryObjectStorageService(),
                new DockerRuntimeProfileImageBuilder(
                        temporaryDirectory,
                        (argv, environment, timeoutMillis) -> new ProcessContainerRunner.CommandResult(0, 1L, "", "")
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.upload(
                "7486000000000000004",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "Dockerfile",
                "FROM rd-bot/claude-code:local\nCOPY requirements.txt /tmp/requirements.txt\n"
                        .getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(exception.getMessage().contains("COPY or ADD"));
    }

    @Test
    void shouldRejectEntrypointOrCommandOverridesBeforeStartingAnyDockerBuild() {
        AtomicInteger dockerInvocations = new AtomicInteger();
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                new InMemoryObjectStorageService(),
                new DockerRuntimeProfileImageBuilder(
                        temporaryDirectory,
                        (argv, environment, timeoutMillis) -> {
                            dockerInvocations.incrementAndGet();
                            return new ProcessContainerRunner.CommandResult(0, 1L, "", "");
                        }
                )
        );

        IllegalArgumentException entrypoint = assertThrows(IllegalArgumentException.class, () -> service.upload(
                "7486000000000000006",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "Dockerfile",
                "FROM rd-bot/claude-code:local\nENTRYPOINT [\"/bin/sh\"]\n".getBytes(StandardCharsets.UTF_8)
        ));
        IllegalArgumentException command = assertThrows(IllegalArgumentException.class, () -> service.upload(
                "7486000000000000006",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "Dockerfile",
                "FROM rd-bot/claude-code:local\nCMD [\"/bin/sh\"]\n".getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(entrypoint.getMessage().contains("ENTRYPOINT or CMD"));
        assertTrue(command.getMessage().contains("ENTRYPOINT or CMD"));
        assertEquals(0, dockerInvocations.get());
    }

    @Test
    void shouldRejectArbitraryRunThatCouldReplaceTheTrustedClaudeRuntimeBeforeAnyDockerBuild() {
        AtomicInteger dockerInvocations = new AtomicInteger();
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                new InMemoryObjectStorageService(),
                new DockerRuntimeProfileImageBuilder(
                        temporaryDirectory,
                        (argv, environment, timeoutMillis) -> {
                            dockerInvocations.incrementAndGet();
                            return new ProcessContainerRunner.CommandResult(0, 1L, "", "");
                        }
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.upload(
                "7486000000000000007",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "Dockerfile",
                (
                        "FROM rd-bot/claude-code:local\n"
                                + "USER root\n"
                                + "RUN printf '#!/bin/sh\\n' > /usr/local/bin/rd-claude-entrypoint\n"
                                + "USER rdbot\n"
                ).getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(exception.getMessage().contains("arbitrary shell commands"));
        assertEquals(0, dockerInvocations.get());
    }

    @Test
    void shouldRejectAnUntrustedDockerBaseBeforeAnyDockerBuild() {
        AtomicInteger dockerInvocations = new AtomicInteger();
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                new InMemoryObjectStorageService(),
                new DockerRuntimeProfileImageBuilder(
                        temporaryDirectory,
                        (argv, environment, timeoutMillis) -> {
                            dockerInvocations.incrementAndGet();
                            return new ProcessContainerRunner.CommandResult(0, 1L, "", "");
                        }
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.upload(
                "7486000000000000008",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "Dockerfile",
                "FROM public/unknown-claude:latest\n".getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(exception.getMessage().contains("configured RD-Bot Claude runtime base images"));
        assertEquals(0, dockerInvocations.get());
    }

    @Test
    void shouldRejectPipPackagesBecauseTheyCanRunUntrustedBuildHooks() {
        AtomicInteger dockerInvocations = new AtomicInteger();
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                new InMemoryObjectStorageService(),
                new DockerRuntimeProfileImageBuilder(
                        temporaryDirectory,
                        (argv, environment, timeoutMillis) -> {
                            dockerInvocations.incrementAndGet();
                            return new ProcessContainerRunner.CommandResult(0, 1L, "", "");
                        }
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.upload(
                "7486000000000000009",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "Dockerfile",
                (
                        "FROM rd-bot/claude-code:local\n"
                                + "USER root\n"
                                + "RUN python3 -m pip install --no-cache-dir untrusted-package\n"
                                + "USER rdbot\n"
                ).getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(exception.getMessage().contains("plain apt-get"));
        assertEquals(0, dockerInvocations.get());
    }

    @Test
    void shouldAcceptTheBundledDjangoRuntimeProfileTemplates() throws Exception {
        DockerRuntimeProfileImageBuilder builder = new DockerRuntimeProfileImageBuilder(
                temporaryDirectory,
                (argv, environment, timeoutMillis) -> argv.contains("inspect")
                        ? new ProcessContainerRunner.CommandResult(0, 1L, "[\"rd-claude-entrypoint\"]|rdbot", "")
                        : new ProcessContainerRunner.CommandResult(0, 1L, "runtime verification", "")
        );
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                new InMemoryObjectStorageService(),
                builder
        );
        Path templates = projectRoot().resolve("scripts/swebench");

        ProjectRuntimeProfile coding = service.upload(
                "7486000000000000010",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "Dockerfile.django-python",
                Files.readAllBytes(templates.resolve("Dockerfile.django-python"))
        );
        ProjectRuntimeProfile qa = service.upload(
                "7486000000000000010",
                "QA_AGENT",
                "CLAUDE_CODE",
                "Dockerfile.django-python-qa",
                Files.readAllBytes(templates.resolve("Dockerfile.django-python-qa"))
        );

        assertEquals("VERIFIED", coding.validationStatus());
        assertEquals("VERIFIED", qa.validationStatus());
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("scripts/swebench/Dockerfile.django-python"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("unable to locate the RD-Bot project root");
    }

    @Test
    void shouldRejectUnsupportedAgentTypeBeforeStartingAnyDockerBuild() {
        AtomicInteger dockerInvocations = new AtomicInteger();
        ProjectRuntimeProfileUploadService service = new ProjectRuntimeProfileUploadService(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                new InMemoryObjectStorageService(),
                new DockerRuntimeProfileImageBuilder(
                        temporaryDirectory,
                        (argv, environment, timeoutMillis) -> {
                            dockerInvocations.incrementAndGet();
                            return new ProcessContainerRunner.CommandResult(0, 1L, "", "");
                        }
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.upload(
                "7486000000000000005",
                "CODING_AGENT",
                "UNSUPPORTED_AGENT",
                "Dockerfile",
                "FROM rd-bot/claude-code:local\n".getBytes(StandardCharsets.UTF_8)
        ));

        assertEquals("agentType must be CLAUDE_CODE", exception.getMessage());
        assertEquals(0, dockerInvocations.get());
    }
}
