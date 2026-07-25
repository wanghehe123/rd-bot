package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStorageRoleHandoffPublisherTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldPersistBoundedMarkdownHandoffAsPrivateRustFsArtifact() throws Exception {
        Path file = temporaryDirectory.resolve("handoff/next.md");
        Files.createDirectories(file.getParent());
        byte[] content = "# Implementation plan\n\n1. Update the serializer.\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);
        RepairExecutionResult result = new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "Plan complete",
                "",
                List.of(new RepairArtifact(
                        RepairArtifactType.HANDOFF_MARKDOWN,
                        "handoff/next.md",
                        file.toUri().toString(),
                        "Downstream handoff",
                        Map.of(
                                "bytes", String.valueOf(content.length),
                                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                                "contentType", "text/markdown"
                        )
                )),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
        RoleHandoffProperties properties = new RoleHandoffProperties();
        properties.setMaxTokens(128);
        ObjectStorageRoleHandoffPublisher publisher = new ObjectStorageRoleHandoffPublisher(
                new InMemoryObjectStorageService(),
                properties
        );

        RepairExecutionResult published = publisher.publish(AgentRole.SOLUTION_ARCHITECT, result);

        RepairArtifact artifact = published.artifacts().getFirst();
        assertEquals(RepairArtifactType.HANDOFF_MARKDOWN, artifact.type());
        assertTrue(artifact.uri().startsWith("s3://rd-role-handoffs/"));
        assertEquals("text/markdown", artifact.metadataJson().get("contentType"));
    }

    @Test
    void shouldPersistCodingPatchAsPrivateCandidateArtifactForIsolatedQa() throws Exception {
        Path file = temporaryDirectory.resolve("patch.diff");
        byte[] content = """
                diff --git a/src/order.py b/src/order.py
                index 1111111..2222222 100644
                --- a/src/order.py
                +++ b/src/order.py
                @@ -1 +1 @@
                -return old_order
                +return new_order
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);
        RepairExecutionResult result = new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "Coding complete",
                "",
                List.of(new RepairArtifact(
                        RepairArtifactType.PATCH_DIFF,
                        "patch.diff",
                        file.toUri().toString(),
                        "Candidate code patch",
                        Map.of(
                                "bytes", String.valueOf(content.length),
                                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                                "contentType", "text/x-diff"
                        )
                )),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), ""
        );
        RoleHandoffProperties properties = new RoleHandoffProperties();
        ObjectStorageRoleHandoffPublisher publisher = new ObjectStorageRoleHandoffPublisher(
                new InMemoryObjectStorageService(), properties
        );

        RepairExecutionResult published = publisher.publish(AgentRole.CODING_AGENT, result);

        RepairArtifact artifact = published.artifacts().getFirst();
        assertEquals(RepairArtifactType.PATCH_DIFF, artifact.type());
        assertTrue(artifact.uri().startsWith("s3://rd-role-handoffs/"));
        assertEquals("text/x-diff", artifact.metadataJson().get("contentType"));
        assertEquals(String.valueOf(content.length), artifact.metadataJson().get("bytes"));
    }
}
