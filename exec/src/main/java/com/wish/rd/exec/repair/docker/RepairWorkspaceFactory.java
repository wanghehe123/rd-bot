package com.wish.rd.exec.repair.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.RepairJobCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 为 Docker Claude Code 执行创建本地工作区和标准输入协议文件。
 */
public class RepairWorkspaceFactory {

    private static final Pattern SAFE_TASK_DIRECTORY = Pattern.compile("[A-Za-z0-9._-]+");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path workspaceRoot;
    private final String resultSchemaJson;

    /**
     * 创建工作区工厂。
     *
     * @param workspaceRoot    工作区根目录
     * @param resultSchemaJson 结构化结果 schema JSON
     */
    public RepairWorkspaceFactory(Path workspaceRoot, String resultSchemaJson) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.resultSchemaJson = resultSchemaJson == null ? "" : resultSchemaJson;
    }

    /**
     * 创建修复任务工作区并写入输入协议文件。
     *
     * @param command 修复执行命令
     * @return 已创建的修复工作区
     * @throws IOException 工作区目录或输入文件写入失败
     */
    public RepairWorkspace create(RepairJobCommand command) throws IOException {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String taskDirectoryName = requireSafeTaskDirectoryName(command.taskId());
        Path taskRoot = workspaceRoot.resolve(taskDirectoryName).toAbsolutePath().normalize();
        ensureInsideWorkspaceRoot(taskRoot);
        rejectSymlink(taskRoot);

        Path inputDirectory = taskRoot.resolve("input");
        Path repoDirectory = taskRoot.resolve("repo");
        Path outputDirectory = taskRoot.resolve("output");
        rejectSymlink(inputDirectory);
        rejectSymlink(repoDirectory);
        rejectSymlink(outputDirectory);
        Files.createDirectories(inputDirectory);
        Files.createDirectories(repoDirectory);
        Files.createDirectories(outputDirectory);
        ensureRealPathInsideWorkspaceRoot(taskRoot);
        ensureRealPathInsideWorkspaceRoot(inputDirectory);
        ensureRealPathInsideWorkspaceRoot(repoDirectory);
        ensureRealPathInsideWorkspaceRoot(outputDirectory);

        RepairWorkspaceFiles files = new RepairWorkspaceFiles(
                inputDirectory.resolve("prompt.md"),
                inputDirectory.resolve("context.json"),
                inputDirectory.resolve("result.schema.json"),
                outputDirectory.resolve("result.json"),
                outputDirectory.resolve("patch.diff"),
                outputDirectory.resolve("test.log"),
                outputDirectory.resolve("claude-events.jsonl"),
                outputDirectory.resolve("docker-meta.json")
        );
        Files.writeString(files.prompt(), command.prompt(), StandardCharsets.UTF_8);
        Files.writeString(files.context(), toContextJson(command), StandardCharsets.UTF_8);
        Files.writeString(files.resultSchema(), resultSchemaJson, StandardCharsets.UTF_8);

        return new RepairWorkspace(taskRoot, inputDirectory, repoDirectory, outputDirectory, files);
    }

    private static String requireSafeTaskDirectoryName(String taskId) {
        String normalized = taskId == null ? "" : taskId.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("taskId contains path traversal: " + taskId);
        }
        if (!SAFE_TASK_DIRECTORY.matcher(normalized).matches()) {
            throw new IllegalArgumentException("taskId contains unsafe characters: " + taskId);
        }
        return normalized;
    }

    private void ensureInsideWorkspaceRoot(Path taskRoot) {
        if (!taskRoot.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("workspace path escapes root: " + taskRoot);
        }
    }

    private static void rejectSymlink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("workspace path must not be a symbolic link: " + path);
        }
    }

    private void ensureRealPathInsideWorkspaceRoot(Path path) throws IOException {
        Path realWorkspaceRoot = workspaceRoot.toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realWorkspaceRoot)) {
            throw new IllegalArgumentException("workspace path escapes root: " + realPath);
        }
    }

    private static String toContextJson(RepairJobCommand command) throws JsonProcessingException {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("taskId", command.taskId());
        context.put("ticketId", command.ticketId());
        context.put("repoOwner", command.repoOwner());
        context.put("repoName", command.repoName());
        context.put("baseBranch", command.baseBranch());
        context.put("workBranch", command.workBranch());
        context.put("contextJson", command.contextJson());
        context.put("policyJson", command.policyJson());
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(context);
    }
}
