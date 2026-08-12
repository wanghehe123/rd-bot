package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.requirement.model.RequirementBranchPublication;
import com.wish.rd.engine.requirement.model.RequirementBranchPublishCommand;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementBranchPublisherAdapterTest {

    private static final String PATCH_URI = "s3://rd-role-handoffs/candidate-patch.diff";
    private static final byte[] PATCH_BYTES =
            "diff --git a/app.ts b/app.ts\n+console.log('rd');\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void shouldRebuildWorkspaceApplyPatchAndPushReviewedWorkBranch(@TempDir Path workspaceRoot) throws Exception {
        RoleHandoffAttachmentResolver resolver = new RoleHandoffAttachmentResolver(
                new SinglePatchObjectStorage(PATCH_URI, PATCH_BYTES), new RoleHandoffProperties());
        RepairWorkspaceFactory workspaceFactory = new RepairWorkspaceFactory(workspaceRoot, "{}");
        RecordingWorkspaceRepository repository = new RecordingWorkspaceRepository();
        EngineRequirementBranchPublisherAdapter adapter = new EngineRequirementBranchPublisherAdapter(
                resolver, workspaceFactory, repository);

        RequirementBranchPublication publication = adapter.publishBranch(new RequirementBranchPublishCommand(
                "task-1",
                "Add reminder",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "requirement/task-1",
                deliveryResultJson()
        ));

        assertTrue(publication.success(), publication.errorMessage());
        assertFalse(publication.skipped());
        assertEquals("commit-abc123", publication.commitSha());
        assertTrue(publication.metadataJson().contains("\"pushed\":\"true\""));

        RepairJobCommand publishedJob = repository.publishCommand;
        assertNotNull(publishedJob);
        assertEquals("requirement/task-1", publishedJob.workBranch());
        assertEquals("main", publishedJob.baseBranch());
        assertEquals("example", publishedJob.repoOwner());
        assertEquals("waimai", publishedJob.repoName());
        assertEquals("true", publishedJob.policyJson().get("applyCandidatePatch"));
        assertEquals("PUBLISH", publishedJob.policyJson().get("repositoryDeliveryMode"));
        String candidatePatchSha256 = sha256(PATCH_BYTES);
        assertEquals("task-1", publishedJob.policyJson().get("requirementPublicationTaskId"));
        assertEquals(candidatePatchSha256,
                publishedJob.policyJson().get("requirementPublicationCandidatePatchSha256"));
        assertEquals(RequirementOperationId.of(
                        "task-1", "main", "requirement/task-1", candidatePatchSha256),
                publishedJob.policyJson().get("requirementPublicationOperationId"));
        assertEquals(1, publishedJob.attachments().size());
        assertEquals("candidate-patch.diff", publishedJob.attachments().getFirst().filename());
        assertTrue(publishedJob.taskId().endsWith("-publish"));

        // The workspace factory materialised the verified candidate patch as a read-only attachment.
        Path attachment = repository.preparedWorkspace.inputDirectory()
                .resolve("attachments").resolve("candidate-patch.diff");
        assertTrue(Files.isRegularFile(attachment));
        assertEquals(new String(PATCH_BYTES, StandardCharsets.UTF_8),
                Files.readString(attachment, StandardCharsets.UTF_8));
    }

    @Test
    void shouldFailWhenReviewedResultHasNoCandidatePatch(@TempDir Path workspaceRoot) {
        RoleHandoffAttachmentResolver resolver = new RoleHandoffAttachmentResolver(
                new SinglePatchObjectStorage(PATCH_URI, PATCH_BYTES), new RoleHandoffProperties());
        RepairWorkspaceFactory workspaceFactory = new RepairWorkspaceFactory(workspaceRoot, "{}");
        RecordingWorkspaceRepository repository = new RecordingWorkspaceRepository();
        EngineRequirementBranchPublisherAdapter adapter = new EngineRequirementBranchPublisherAdapter(
                resolver, workspaceFactory, repository);

        RequirementBranchPublication publication = adapter.publishBranch(new RequirementBranchPublishCommand(
                "task-2",
                "Add reminder",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "requirement/task-2",
                "{\"deliveryReview\":{\"approved\":true},\"multiAgentStages\":[{\"role\":\"CODING_AGENT\",\"resultJson\":\"{}\"}]}"
        ));

        assertFalse(publication.success());
        assertTrue(publication.errorMessage().contains("candidate patch"));
        assertTrue(repository.publishCommand == null, "publish must not run without a candidate patch");
    }

    private String deliveryResultJson() throws Exception {
        String sha256 = sha256(PATCH_BYTES);
        String codingResultJson = ("{\"stageArtifacts\":[{\"type\":\"PATCH_DIFF\",\"name\":\"patch.diff\","
                + "\"uri\":\"" + PATCH_URI + "\",\"metadataJson\":{\"candidatePatch\":\"true\","
                + "\"sha256\":\"" + sha256 + "\",\"bytes\":" + PATCH_BYTES.length + "}}]}")
                .replace("\"", "\\\"");
        return "{\"deliveryReview\":{\"approved\":true},\"multiAgentStages\":["
                + "{\"role\":\"CODING_AGENT\",\"success\":true,\"resultJson\":\"" + codingResultJson + "\"},"
                + "{\"role\":\"QA_AGENT\",\"success\":true,\"resultJson\":\"{}\"}]}";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class RecordingWorkspaceRepository implements RepairWorkspaceRepositoryPort {

        private RepairWorkspace preparedWorkspace;
        private RepairJobCommand publishCommand;

        @Override
        public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) {
            this.preparedWorkspace = workspace;
            return new RepositoryOperationResult(Map.of("prepared", "true"));
        }

        @Override
        public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
            this.publishCommand = command;
            return new RepositoryOperationResult(Map.of(
                    "pushed", "true",
                    "workBranch", command.workBranch(),
                    "commitSha", "commit-abc123"
            ));
        }
    }

    private static final class SinglePatchObjectStorage implements ObjectStorageService {

        private final String uri;
        private final byte[] bytes;

        private SinglePatchObjectStorage(String uri, byte[] bytes) {
            this.uri = uri;
            this.bytes = bytes.clone();
        }

        @Override
        public StoredIngestionFile upload(
                String bucketName, InputStream content, long size, String originalFilename, String contentType) {
            throw new UnsupportedOperationException("upload is not used in this test");
        }

        @Override
        public InputStream openStream(String url) {
            if (!uri.equals(url)) {
                throw new IllegalArgumentException("unexpected object url: " + url);
            }
            return new ByteArrayInputStream(bytes.clone());
        }
    }
}
