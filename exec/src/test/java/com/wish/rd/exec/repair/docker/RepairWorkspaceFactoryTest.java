package com.wish.rd.exec.repair.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.docker.model.RepairWorkspaceFiles;

class RepairWorkspaceFactoryTest {

    @Test
    void shouldWriteAttachmentsAndManifestInsideInputDirectory() throws Exception {
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(temporaryDirectory, "{}");
        RepairJobCommand base = command("task-attachments");
        RepairJobCommand command = new RepairJobCommand(
                base.repairRecordId(), base.taskId(), base.ticketId(), base.ticketTitle(), base.prompt(),
                base.repositoryUrl(), base.repoOwner(), base.repoName(), base.baseBranch(), base.workBranch(),
                base.contextJson(), base.policyJson(),
                java.util.List.of(new RepairInputAttachment("../broken.png", "image/png", new byte[]{1, 2, 3}))
        );

        RepairWorkspace workspace = factory.create(command);

        Path attachment = workspace.inputDirectory().resolve("attachments/broken.png");
        assertTrue(Files.exists(attachment));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(attachment));
        String context = Files.readString(workspace.files().context());
        assertTrue(context.contains("/work/input/attachments/broken.png"));
        assertTrue(Files.readString(workspace.files().prompt()).contains("/work/input/attachments"));
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RESULT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["status"]
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCreateWorkspaceUnderRootUsingTaskId() throws IOException {
        Path workspaceRoot = temporaryDirectory.resolve("workspaces");
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(workspaceRoot, RESULT_SCHEMA_JSON);
        RepairJobCommand command = command("task-1001");

        RepairWorkspace workspace = factory.create(command);

        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        assertEquals(normalizedRoot.resolve("task-1001"), workspace.root());
        assertTrue(workspace.root().startsWith(normalizedRoot));
        assertTrue(Files.isDirectory(workspace.inputDirectory()));
        assertTrue(Files.isDirectory(workspace.repoDirectory()));
        assertTrue(Files.isDirectory(workspace.outputDirectory()));
    }

    @Test
    void shouldWriteInputFilesAsUtf8AndContextJson() throws IOException {
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON);
        RepairJobCommand command = command("task.with-safe_chars-1001");

        RepairWorkspace workspace = factory.create(command);

        assertEquals("修复 OrderService 500", Files.readString(workspace.files().prompt(), StandardCharsets.UTF_8));
        assertEquals(RESULT_SCHEMA_JSON, Files.readString(workspace.files().resultSchema(), StandardCharsets.UTF_8));
        JsonNode context = OBJECT_MAPPER.readTree(Files.readString(workspace.files().context(), StandardCharsets.UTF_8));
        assertEquals("task.with-safe_chars-1001", context.get("taskId").asText());
        assertEquals("FS-1001", context.get("ticketId").asText());
        assertEquals("wish", context.get("repoOwner").asText());
        assertEquals("rd-bot", context.get("repoName").asText());
        assertEquals("main", context.get("baseBranch").asText());
        assertEquals("repair/task-1001", context.get("workBranch").asText());
    }

    @Test
    void shouldWriteQaAgentResultSchemaWhenRoleRequiresCommandEvidence() throws IOException {
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON);
        RepairWorkspace workspace = factory.create(command("task-qa-1001", "QA_AGENT"));

        JsonNode schema = OBJECT_MAPPER.readTree(Files.readString(
                workspace.files().resultSchema(),
                StandardCharsets.UTF_8
        ));

        assertEquals("RD-Bot QA Agent Result", schema.path("title").asText());
        assertTrue(schema.path("required").toString().contains("acceptanceResults"));
        assertTrue(schema.path("properties").path("acceptanceResults").path("minItems").asInt() >= 1);
        assertEquals("PASSED", schema.path("properties").path("status").path("enum").get(0).asText());
        assertFalse(schema.path("required").toString().contains("changedFiles"));
    }

    @Test
    void shouldExposeProtocolOutputPaths() throws IOException {
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON);

        RepairWorkspace workspace = factory.create(command("task-1001"));

        assertEquals(workspace.outputDirectory().resolve("result.json"), workspace.files().resultJson());
        assertEquals(workspace.outputDirectory().resolve("patch.diff"), workspace.files().patchDiff());
        assertEquals(workspace.outputDirectory().resolve("test.log"), workspace.files().testLog());
        assertEquals(workspace.outputDirectory().resolve("claude-events.jsonl"), workspace.files().claudeEventsJsonl());
        assertEquals(workspace.outputDirectory().resolve("docker-meta.json"), workspace.files().dockerMetaJson());
    }

    @Test
    void shouldRejectUnsafeTaskDirectoryNames() {
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON);

        assertThrows(IllegalArgumentException.class, () -> factory.create(command("")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(command(" ")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(command("../task")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(command("nested/task")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(command("nested\\task")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(command("task:1001")));
    }

    @Test
    void shouldNeverWriteOutsideConfiguredWorkspaceRoot() throws IOException {
        Path workspaceRoot = temporaryDirectory.resolve("root").resolve("..").resolve("root");
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(workspaceRoot, RESULT_SCHEMA_JSON);

        RepairWorkspace workspace = factory.create(command("task..1001"));

        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        assertTrue(workspace.root().startsWith(normalizedRoot));
        assertProtocolPathsInside(workspace, normalizedRoot);
        assertFalse(Files.exists(normalizedRoot.getParent().resolve("task..1001")));
    }

    @Test
    void shouldRejectExistingTaskSymlinkEscapingWorkspaceRoot() throws IOException {
        Path workspaceRoot = temporaryDirectory.resolve("root");
        Path outsideRoot = temporaryDirectory.resolve("outside");
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(outsideRoot);
        Files.createSymbolicLink(workspaceRoot.resolve("task-1001"), outsideRoot);
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(workspaceRoot, RESULT_SCHEMA_JSON);

        assertThrows(IllegalArgumentException.class, () -> factory.create(command("task-1001")));
        assertFalse(Files.exists(outsideRoot.resolve("input").resolve("prompt.md")));
    }

    @Test
    void shouldRejectAttachmentTargetSymlinkWithoutWritingOutsideWorkspace() throws IOException {
        Path workspaceRoot = temporaryDirectory.resolve("root");
        Path outsideFile = temporaryDirectory.resolve("outside.png");
        Path attachmentDirectory = workspaceRoot.resolve("task-attachment-link/input/attachments");
        Files.createDirectories(attachmentDirectory);
        Files.write(outsideFile, new byte[]{9, 9, 9});
        Files.createSymbolicLink(attachmentDirectory.resolve("broken.png"), outsideFile);
        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(workspaceRoot, RESULT_SCHEMA_JSON);
        RepairJobCommand base = command("task-attachment-link");
        RepairJobCommand withAttachment = new RepairJobCommand(
                base.repairRecordId(), base.taskId(), base.ticketId(), base.ticketTitle(), base.prompt(),
                base.repositoryUrl(), base.repoOwner(), base.repoName(), base.baseBranch(), base.workBranch(),
                base.contextJson(), base.policyJson(),
                java.util.List.of(new RepairInputAttachment("broken.png", "image/png", new byte[]{1, 2, 3}))
        );

        assertThrows(IllegalArgumentException.class, () -> factory.create(withAttachment));
        assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(outsideFile));
    }

    private static void assertProtocolPathsInside(RepairWorkspace workspace, Path normalizedRoot) {
        RepairWorkspaceFiles files = workspace.files();
        assertTrue(files.prompt().toAbsolutePath().normalize().startsWith(normalizedRoot));
        assertTrue(files.context().toAbsolutePath().normalize().startsWith(normalizedRoot));
        assertTrue(files.resultSchema().toAbsolutePath().normalize().startsWith(normalizedRoot));
        assertTrue(files.resultJson().toAbsolutePath().normalize().startsWith(normalizedRoot));
        assertTrue(files.patchDiff().toAbsolutePath().normalize().startsWith(normalizedRoot));
        assertTrue(files.testLog().toAbsolutePath().normalize().startsWith(normalizedRoot));
        assertTrue(files.claudeEventsJsonl().toAbsolutePath().normalize().startsWith(normalizedRoot));
        assertTrue(files.dockerMetaJson().toAbsolutePath().normalize().startsWith(normalizedRoot));
    }

    private static RepairJobCommand command(String taskId) {
        return command(taskId, "");
    }

    private static RepairJobCommand command(String taskId, String agentRole) {
        return new RepairJobCommand(
                "repair-1001",
                taskId,
                "FS-1001",
                "OrderService.create 500",
                "修复 OrderService 500",
                "https://github.com/wish/rd-bot",
                "wish",
                "rd-bot",
                "main",
                "repair/task-1001",
                agentRole.isBlank()
                        ? Map.of("ragSummary", "检索到 3 个上下文块")
                        : Map.of("ragSummary", "检索到 3 个上下文块", "agentRole", agentRole),
                Map.of("yolo", "true")
        );
    }
}
