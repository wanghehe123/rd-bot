package com.wish.rd.exec.repair.execution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;

class RepairExecutionContractTest {

    @Test
    void shouldRejectBlankRequiredIds() {
        assertThrows(IllegalArgumentException.class, () -> new RepairJobCommand(
                "",
                "task-1001",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new RepairJobCommand(
                "repair-1001",
                " ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void shouldNormalizeNullableCommandFields() {
        RepairJobCommand command = new RepairJobCommand(
                " repair-1001 ",
                " task-1001 ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals("repair-1001", command.repairRecordId());
        assertEquals("task-1001", command.taskId());
        assertEquals("", command.ticketId());
        assertEquals("", command.ticketTitle());
        assertEquals("", command.prompt());
        assertEquals("", command.repositoryUrl());
        assertEquals("", command.repoOwner());
        assertEquals("", command.repoName());
        assertEquals("", command.baseBranch());
        assertEquals("", command.workBranch());
        assertTrue(command.contextJson().isEmpty());
        assertTrue(command.policyJson().isEmpty());
    }

    @Test
    void shouldCopyCommandMaps() {
        Map<String, String> contextJson = new HashMap<>();
        contextJson.put("ragSummary", "检索到 3 个上下文块");
        contextJson.put("optionalContext", null);
        Map<String, String> policyJson = new HashMap<>();
        policyJson.put("yolo", "true");
        policyJson.put("optionalPolicy", null);

        RepairJobCommand command = new RepairJobCommand(
                "repair-1001",
                "task-1001",
                "FS-1001",
                "OrderService.create 500",
                "修复 OrderService",
                "https://github.com/example/order",
                "example",
                "order",
                "main",
                "repair/FS-1001",
                contextJson,
                policyJson
        );

        contextJson.put("ragSummary", "外部变更");
        policyJson.put("yolo", "false");

        assertEquals("检索到 3 个上下文块", command.contextJson().get("ragSummary"));
        assertEquals("", command.contextJson().get("optionalContext"));
        assertEquals("true", command.policyJson().get("yolo"));
        assertEquals("", command.policyJson().get("optionalPolicy"));
        assertThrows(UnsupportedOperationException.class, () -> command.contextJson().put("other", "value"));
    }

    @Test
    void shouldCarryExecutionResultFieldsAndNormalizeNulls() {
        RepairExecutionResult result = new RepairExecutionResult(
                RepairExecutionStatus.FAILED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertEquals("", result.summary());
        assertEquals("", result.pullRequestUrl());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.rawResultJson().isEmpty());
        assertTrue(result.dockerMetadataJson().isEmpty());
        assertTrue(result.githubMetadataJson().isEmpty());
        assertTrue(result.testMetadataJson().isEmpty());
        assertTrue(result.riskMetadataJson().isEmpty());
        assertEquals("", result.errorMessage());
    }

    @Test
    void shouldRejectNullExecutionStatus() {
        assertThrows(IllegalArgumentException.class, () -> new RepairExecutionResult(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void shouldCopyResultArtifactsAndMetadata() {
        Map<String, String> rawResultJson = new HashMap<>();
        rawResultJson.put("status", "SUCCESS");
        rawResultJson.put("optionalRaw", null);
        Map<String, String> dockerMetadataJson = new HashMap<>();
        dockerMetadataJson.put("exitCode", "0");
        dockerMetadataJson.put("optionalDocker", null);
        Map<String, String> githubMetadataJson = new HashMap<>();
        githubMetadataJson.put("provider", "github");
        Map<String, String> testMetadataJson = new HashMap<>();
        testMetadataJson.put("status", "PASSED");
        Map<String, String> riskMetadataJson = new HashMap<>();
        riskMetadataJson.put("riskLevel", "LOW");
        List<RepairArtifact> artifacts = new ArrayList<>();
        RepairArtifact artifact = new RepairArtifact(
                RepairArtifactType.PATCH_DIFF,
                "patch.diff",
                "s3://rd-bot/repair/task-1001/patch.diff",
                "修复补丁",
                Map.of("bytes", "128")
        );
        artifacts.add(artifact);
        RepairExecutionResult result = new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "修复完成",
                "https://github.com/example/order/pull/1",
                artifacts,
                rawResultJson,
                dockerMetadataJson,
                githubMetadataJson,
                testMetadataJson,
                riskMetadataJson,
                null
        );

        artifacts.clear();
        rawResultJson.put("status", "外部变更");
        dockerMetadataJson.put("exitCode", "1");

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("修复完成", result.summary());
        assertEquals("https://github.com/example/order/pull/1", result.pullRequestUrl());
        assertEquals(List.of(artifact), result.artifacts());
        assertEquals("SUCCESS", result.rawResultJson().get("status"));
        assertEquals("", result.rawResultJson().get("optionalRaw"));
        assertEquals("0", result.dockerMetadataJson().get("exitCode"));
        assertEquals("", result.dockerMetadataJson().get("optionalDocker"));
        assertEquals("github", result.githubMetadataJson().get("provider"));
        assertEquals("PASSED", result.testMetadataJson().get("status"));
        assertEquals("LOW", result.riskMetadataJson().get("riskLevel"));
        assertEquals("", result.errorMessage());
        assertThrows(UnsupportedOperationException.class, () -> result.artifacts().add(artifact));
    }

    @Test
    void shouldNormalizeArtifactFields() {
        RepairArtifact artifact = new RepairArtifact(null, null, null, null, null);

        assertEquals(RepairArtifactType.OTHER, artifact.type());
        assertEquals("", artifact.name());
        assertEquals("", artifact.uri());
        assertEquals("", artifact.summary());
        assertTrue(artifact.metadataJson().isEmpty());
    }

    @Test
    void shouldCopyArtifactMetadataAndNormalizeNullValues() {
        Map<String, String> metadataJson = new HashMap<>();
        metadataJson.put("bytes", "128");
        metadataJson.put("optional", null);

        RepairArtifact artifact = new RepairArtifact(
                RepairArtifactType.TEST_LOG,
                "test.log",
                "file:///tmp/test.log",
                "测试日志",
                metadataJson
        );

        metadataJson.put("bytes", "256");

        assertEquals("128", artifact.metadataJson().get("bytes"));
        assertEquals("", artifact.metadataJson().get("optional"));
        assertThrows(UnsupportedOperationException.class, () -> artifact.metadataJson().put("other", "value"));
    }

    @Test
    void shouldExposeExecutorPort() {
        RepairJobCommand command = new RepairJobCommand(
                "repair-1001",
                "task-1001",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        RepairExecutorPort executor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.NEED_INFO,
                "需要更多上下文",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        RepairExecutionResult result = executor.execute(command);

        assertEquals(RepairExecutionStatus.NEED_INFO, result.status());
        assertEquals("需要更多上下文", result.summary());
    }

    @Test
    void shouldNotImportBootstrapOrEngine() throws IOException {
        for (Path sourceFile : executionSourceFiles()) {
            String source = Files.readString(sourceFile);

            assertFalse(source.contains("import com.wish.rd.bootstrap."),
                    () -> sourceFile + " must not import bootstrap");
            assertFalse(source.contains("import com.wish.rd.engine."),
                    () -> sourceFile + " must not import engine");
        }
    }

    @Test
    void publicContractsShouldHaveJavadoc() throws IOException {
        Map<String, String> publicTypes = Map.of(
                "RepairArtifact", "model/RepairArtifact.java",
                "RepairArtifactType", "model/RepairArtifactType.java",
                "RepairExecutionResult", "model/RepairExecutionResult.java",
                "RepairExecutionStatus", "model/RepairExecutionStatus.java",
                "RepairExecutorPort", "RepairExecutorPort.java",
                "RepairJobCommand", "model/RepairJobCommand.java"
        );

        for (Map.Entry<String, String> entry : publicTypes.entrySet()) {
            String source = Files.readString(executionSourceRoot().resolve(entry.getValue()));
            Pattern declarationWithJavadoc = Pattern.compile(
                    "/\\*\\*.*?\\*/\\s*(?:@[\\w.]+\\s*)*public\\s+(record|interface|enum)\\s+"
                            + entry.getKey() + "\\b",
                    Pattern.DOTALL
            );

            assertTrue(declarationWithJavadoc.matcher(source).find(),
                    () -> entry.getValue() + " must document its public contract with JavaDoc");
        }
    }

    private static List<Path> executionSourceFiles() throws IOException {
        try (var files = Files.list(executionSourceRoot())) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static Path executionSourceRoot() {
        Path currentDirectory = Path.of("").toAbsolutePath();
        Path moduleRelative = currentDirectory.resolve("src/main/java/com/wish/rd/exec/repair/execution");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = currentDirectory.resolve("exec/src/main/java/com/wish/rd/exec/repair/execution");
        if (Files.isDirectory(reactorRelative)) {
            return reactorRelative;
        }
        throw new IllegalStateException("Cannot find execution source root from " + currentDirectory);
    }
}
