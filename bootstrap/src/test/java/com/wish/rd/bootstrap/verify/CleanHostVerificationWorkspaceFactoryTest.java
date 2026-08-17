package com.wish.rd.bootstrap.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.verify.HostVerificationChangeSetResolver;
import com.wish.rd.engine.requirement.verify.HostVerificationPort;
import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.HostVerificationWorkspaceFactory;
import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanHostVerificationWorkspaceFactoryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void prepareBuildsHostReplayCommandAndCallsCleanVerifierWithRuntimeDisabled() throws Exception {
        byte[] patch = samplePatch();
        Path repo = Files.createDirectories(temporaryDirectory.resolve("replay-repo"));
        Files.createDirectories(repo.resolve("cache"));
        Files.createDirectories(repo.resolve("node_modules/pkg"));
        CapturingVerifierFactory verifier = new CapturingVerifierFactory(repo);
        CleanHostVerificationWorkspaceFactory factory = new CleanHostVerificationWorkspaceFactory(
                verifier,
                (task, codingStage) -> HostVerificationCandidatePatch.of(patch)
        );

        Path prepared = factory.prepare(task(), codingStage());

        assertEquals(repo.toAbsolutePath().normalize(), prepared);
        assertEquals(1, verifier.calls);
        assertFalse(verifier.request.runtimeRequired());
        assertEquals("CURRENT", verifier.request.scope());
        assertEquals("9001", verifier.request.taskId());
        assertEquals("7001", verifier.request.stageRunId());

        RepairJobCommand command = verifier.command;
        assertEquals(1, command.attachments().size());
        RepairInputAttachment attachment = command.attachments().getFirst();
        assertEquals("candidate-patch.diff", attachment.filename());
        assertEquals("text/x-diff", attachment.mimeType());
        assertEquals(new String(patch, StandardCharsets.UTF_8), new String(attachment.content(), StandardCharsets.UTF_8));
        assertEquals("QA_AGENT", command.contextJson().get("agentRole"));
        assertEquals("true", command.contextJson().get("hostVerificationReplay"));
        assertEquals("true", command.policyJson().get("applyCandidatePatch"));
        assertEquals("LOCAL_ONLY", command.policyJson().get("repositoryDeliveryMode"));
        assertFalse(command.policyJson().containsKey("cleanCache"));
        assertFalse(command.policyJson().containsKey("deleteNodeModules"));

        JsonNode manifest = OBJECT_MAPPER.readTree(command.contextJson().get("upstreamHandoffManifestJson"));
        assertTrue(manifest.isArray());
        assertEquals(1, manifest.size());
        JsonNode entry = manifest.get(0);
        assertEquals("CODING_AGENT", entry.path("sourceRole").asText());
        assertEquals("QA_AGENT", entry.path("targetRole").asText());
        assertEquals("/work/input/attachments/candidate-patch.diff", entry.path("path").asText());
        assertTrue(entry.path("applied").asBoolean());
        assertEquals(sha256(patch), entry.path("sha256").asText());
        assertEquals(patch.length, entry.path("bytes").asInt());
        assertTrue(Files.isDirectory(repo.resolve("cache")));
        assertTrue(Files.isDirectory(repo.resolve("node_modules/pkg")));
    }

    @Test
    void missingPatchThrowsWithoutCallingVerifier() {
        Path repo = temporaryDirectory.resolve("unused-repo");
        CapturingVerifierFactory verifier = new CapturingVerifierFactory(repo);
        CleanHostVerificationWorkspaceFactory factory = new CleanHostVerificationWorkspaceFactory(
                verifier,
                (task, codingStage) -> {
                    throw new IllegalStateException(
                            "coding stage 7001 has no verified candidate-patch.diff");
                }
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> factory.prepare(task(), codingStage())
        );

        assertTrue(failure.getMessage().contains("candidate-patch.diff"), failure.getMessage());
        assertEquals(0, verifier.calls);
        assertFalse(Files.exists(repo));
    }

    @Test
    void emptyPatchThrowsWithoutCallingVerifier() {
        CapturingVerifierFactory verifier = new CapturingVerifierFactory(temporaryDirectory.resolve("empty"));
        CleanHostVerificationWorkspaceFactory factory = new CleanHostVerificationWorkspaceFactory(
                verifier,
                (task, codingStage) -> new HostVerificationCandidatePatch(new byte[0], "")
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> factory.prepare(task(), codingStage())
        );

        assertTrue(failure.getMessage().toLowerCase().contains("candidate-patch"), failure.getMessage());
        assertEquals(0, verifier.calls);
    }

    @Test
    void configurationRegistersFactoryResolverAndPort() {
        new ApplicationContextRunner()
                .withUserConfiguration(HostVerificationExecutorConfiguration.class)
                .withBean(HostVerificationStore.class, InMemoryHostVerificationStore::new)
                .run(context -> {
                    assertNotNull(context.getBean(HostVerificationWorkspaceFactory.class));
                    assertInstanceOf(
                            CleanHostVerificationWorkspaceFactory.class,
                            context.getBean(HostVerificationWorkspaceFactory.class)
                    );
                    assertNotNull(context.getBean(HostVerificationChangeSetResolver.class));
                    assertInstanceOf(
                            GitHostVerificationChangeSetResolver.class,
                            context.getBean(HostVerificationChangeSetResolver.class)
                    );
                    assertNotNull(context.getBean(HostVerificationPort.class));
                    assertInstanceOf(
                            HostVerificationExecutorAdapter.class,
                            context.getBean(HostVerificationPort.class)
                    );
                });
    }

    private static RdRequirementTask task() {
        return RdRequirementTask.created(
                "9001",
                new CreateRequirementTaskCommand(
                        "title",
                        "P2",
                        "https://example.com/repo.git",
                        "acme",
                        "repo",
                        "main",
                        "ok",
                        List.of(),
                        false
                ),
                1L
        );
    }

    private static AgentStageRun codingStage() {
        return AgentStageRun.pending("7001", "9001", AgentRole.CODING_AGENT, 1, "idem-1", 1L);
    }

    private static byte[] samplePatch() {
        return """
                diff --git a/src/App.tsx b/src/App.tsx
                --- a/src/App.tsx
                +++ b/src/App.tsx
                @@ -1 +1 @@
                -old
                +new
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static final class CapturingVerifierFactory implements HostVerifierWorkspaceFactory {
        private final Path repo;
        private int calls;
        private RepairJobCommand command;
        private HostVerifierWorkspaceRequest request;

        private CapturingVerifierFactory(Path repo) {
            this.repo = repo;
        }

        @Override
        public HostVerifierWorkspace create(RepairJobCommand command, HostVerifierWorkspaceRequest request) {
            calls++;
            this.command = command;
            this.request = request;
            return new HostVerifierWorkspace(repo, "", Map.of());
        }
    }
}
