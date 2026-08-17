package com.wish.rd.bootstrap.verify;

import com.wish.rd.bootstrap.executor.impl.RoleHandoffAttachmentResolver;
import com.wish.rd.bootstrap.executor.impl.RoleHandoffProperties;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactHostVerificationPatchSourceTest {

    @Test
    void loadReadsPersistedCodingPatchDiff() throws Exception {
        byte[] patch = """
                diff --git a/src/App.tsx b/src/App.tsx
                --- a/src/App.tsx
                +++ b/src/App.tsx
                @@ -1 +1 @@
                -old
                +new
                """.getBytes(StandardCharsets.UTF_8);
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        StoredIngestionFile stored = storage.upload(
                "rd-role-handoffs",
                new ByteArrayInputStream(patch),
                patch.length,
                "patch.diff",
                "text/x-diff"
        );
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patch));
        artifacts.save(new AgentStageArtifact(
                "art-1",
                "7001",
                "9001",
                AgentRole.CODING_AGENT,
                "PATCH_DIFF",
                stored.url(),
                "candidate patch",
                "",
                sha256,
                """
                        {"candidatePatch":"true","sha256":"%s","bytes":"%d","artifactName":"patch.diff"}
                        """.formatted(sha256, patch.length),
                10L
        ));
        ArtifactHostVerificationPatchSource source = new ArtifactHostVerificationPatchSource(
                artifacts,
                new RoleHandoffAttachmentResolver(storage, new RoleHandoffProperties())
        );

        HostVerificationCandidatePatch loaded = source.load(task(), codingStage());

        assertArrayEquals(patch, loaded.content());
        assertTrue(loaded.isPresent());
    }

    @Test
    void missingPatchThrowsClearIllegalStateException() {
        ArtifactHostVerificationPatchSource source = new ArtifactHostVerificationPatchSource(
                new InMemoryAgentStageArtifactStore(),
                new RoleHandoffAttachmentResolver(new InMemoryObjectStorageService(), new RoleHandoffProperties())
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> source.load(task(), codingStage())
        );

        assertTrue(failure.getMessage().contains("candidate-patch.diff"), failure.getMessage());
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
}
