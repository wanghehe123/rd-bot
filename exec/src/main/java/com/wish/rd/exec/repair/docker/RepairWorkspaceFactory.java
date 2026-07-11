package com.wish.rd.exec.repair.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.docker.model.RepairWorkspaceFiles;

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
        writeAttachments(inputDirectory, command);
        Files.writeString(files.prompt(), promptWithAttachmentHint(command), StandardCharsets.UTF_8);
        Files.writeString(files.context(), toContextJson(command), StandardCharsets.UTF_8);
        Files.writeString(files.resultSchema(), resultSchemaJson(command), StandardCharsets.UTF_8);

        return new RepairWorkspace(taskRoot, inputDirectory, repoDirectory, outputDirectory, files);
    }

    private static void writeAttachments(Path inputDirectory, RepairJobCommand command) throws IOException {
        if (command.attachments().isEmpty()) {
            return;
        }
        Path attachmentDirectory = inputDirectory.resolve("attachments").normalize();
        rejectSymlink(attachmentDirectory);
        Files.createDirectories(attachmentDirectory);
        if (Files.isSymbolicLink(attachmentDirectory)) {
            throw new IllegalArgumentException("attachment directory must not be a symbolic link");
        }
        for (RepairInputAttachment attachment : command.attachments()) {
            String filename = safeAttachmentFilename(attachment.filename());
            Path target = attachmentDirectory.resolve(filename).normalize();
            if (!target.startsWith(attachmentDirectory)) {
                throw new IllegalArgumentException("attachment path escapes input directory: " + attachment.filename());
            }
            if (Files.isSymbolicLink(target)) {
                throw new IllegalArgumentException("attachment target must not be a symbolic link: " + filename);
            }
            writeAttachmentAtomically(attachmentDirectory, target, attachment.content());
        }
    }

    private static void writeAttachmentAtomically(Path attachmentDirectory, Path target, byte[] content)
            throws IOException {
        Path temporaryFile = Files.createTempFile(attachmentDirectory, ".rd-upload-", ".tmp");
        try {
            Files.write(temporaryFile, content);
            try {
                Files.move(temporaryFile, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static String promptWithAttachmentHint(RepairJobCommand command) {
        if (command.attachments().isEmpty()) {
            return command.prompt();
        }
        return command.prompt().stripTrailing()
                + "\n\n附件位于 /work/input/attachments，请先检查 context.json 中的附件 manifest。\n";
    }

    private static String safeAttachmentFilename(String filename) {
        String normalized = filename == null ? "" : filename.replace('\\', '/').strip();
        int separator = normalized.lastIndexOf('/');
        String basename = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        basename = basename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (basename.isBlank() || ".".equals(basename) || "..".equals(basename)) {
            throw new IllegalArgumentException("attachment filename is unsafe: " + filename);
        }
        return basename;
    }

    private String resultSchemaJson(RepairJobCommand command) {
        String role = command.contextJson().getOrDefault("agentRole", "").strip().toUpperCase();
        return switch (role) {
            case "REQUIREMENT_REVIEWER" -> REQUIREMENT_REVIEWER_SCHEMA_JSON;
            case "SOLUTION_ARCHITECT" -> SOLUTION_ARCHITECT_SCHEMA_JSON;
            case "QA_AGENT" -> QA_AGENT_SCHEMA_JSON;
            default -> resultSchemaJson;
        };
    }

    private static final String REQUIREMENT_REVIEWER_SCHEMA_JSON = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "RD-Bot Requirement Reviewer Result",
              "type": "object",
              "additionalProperties": true,
              "required": [
                "decision",
                "feasibility",
                "missingInformation",
                "risks",
                "acceptanceCoverage"
              ],
              "properties": {
                "decision": {
                  "type": "string",
                  "enum": ["APPROVED", "NEED_INFO", "REJECTED"]
                },
                "feasibility": {
                  "type": "string",
                  "enum": ["CAN_DO", "NEED_INFO", "UNSAFE"]
                },
                "missingInformation": {
                  "type": "array",
                  "items": {"type": "string"}
                },
                "risks": {
                  "type": "array",
                  "items": {"type": "string"}
                },
                "acceptanceCoverage": {
                  "type": "array",
                  "minItems": 1,
                  "items": {"type": "string", "pattern": "\\\\S"}
                }
              }
            }
            """;

    private static final String SOLUTION_ARCHITECT_SCHEMA_JSON = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "RD-Bot Solution Architect Result",
              "type": "object",
              "additionalProperties": true,
              "required": [
                "summary",
                "affectedFiles",
                "implementationSteps",
                "acceptanceMapping",
                "testPlan"
              ],
              "properties": {
                "summary": {"type": "string", "pattern": "\\\\S"},
                "affectedFiles": {
                  "type": "array",
                  "minItems": 1,
                  "items": {"type": "string", "pattern": "\\\\S"}
                },
                "implementationSteps": {
                  "type": "array",
                  "minItems": 1,
                  "items": {"type": "string", "pattern": "\\\\S"}
                },
                "acceptanceMapping": {
                  "type": "array",
                  "minItems": 1
                },
                "testPlan": {
                  "type": "array",
                  "minItems": 1
                }
              }
            }
            """;

    private static final String QA_AGENT_SCHEMA_JSON = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "RD-Bot QA Agent Result",
              "type": "object",
              "additionalProperties": true,
              "required": [
                "status",
                "summary",
                "acceptanceResults"
              ],
              "properties": {
                "status": {
                  "type": "string",
                  "enum": ["PASSED", "FAILED", "SKIPPED"]
                },
                "summary": {"type": "string", "pattern": "\\\\S"},
                "acceptanceResults": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "additionalProperties": true,
                    "required": [
                      "criteria",
                      "command",
                      "status",
                      "logArtifactId"
                    ],
                    "properties": {
                      "criteria": {"type": "string", "pattern": "\\\\S"},
                      "command": {"type": "string", "pattern": "\\\\S"},
                      "status": {
                        "type": "string",
                        "enum": ["PASSED", "FAILED", "SKIPPED"]
                      },
                      "logArtifactId": {"type": "string", "pattern": "\\\\S"}
                    }
                  }
                }
              }
            }
            """;

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
        context.put("attachments", command.attachments().stream().map(attachment -> Map.of(
                "filename", safeAttachmentFilename(attachment.filename()),
                "mimeType", attachment.mimeType(),
                "size", attachment.content().length,
                "path", "/work/input/attachments/" + safeAttachmentFilename(attachment.filename())
        )).toList());
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(context);
    }
}
