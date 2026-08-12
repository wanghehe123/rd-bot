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
        Path taskRoot = prepareWorkspaceRoot(command);
        return createWorkspace(command, taskRoot, taskRoot.resolve("cache"));
    }

    /**
     * Creates a provider-specific attempt workspace while retaining the task-local
     * package cache. Provider fallback must not observe files left by the failed
     * provider in {@code repo/}, {@code input/}, or {@code output/}.
     *
     * @param command   repair execution command
     * @param attemptId immutable provider-attempt id
     * @return isolated provider-attempt workspace
     * @throws IOException when the workspace cannot be created safely
     */
    public RepairWorkspace createProviderAttempt(
            RepairJobCommand command,
            String attemptId
    ) throws IOException {
        Path taskRoot = prepareWorkspaceRoot(command);
        String attemptDirectoryName = requireSafeTaskDirectoryName(attemptId);
        Path attemptsRoot = taskRoot.resolve("provider-attempts").toAbsolutePath().normalize();
        Path attemptRoot = attemptsRoot.resolve(attemptDirectoryName).toAbsolutePath().normalize();
        ensureInsideWorkspaceRoot(attemptRoot);
        rejectSymlink(attemptsRoot);
        rejectSymlink(attemptRoot);
        Files.createDirectories(attemptRoot);
        rejectSymlink(attemptRoot);
        ensureRealPathInsideWorkspaceRoot(attemptRoot);
        return createWorkspace(command, attemptRoot, taskRoot.resolve("cache"));
    }

    private RepairWorkspace createWorkspace(
            RepairJobCommand command,
            Path root,
            Path cacheDirectory
    ) throws IOException {
        Path inputDirectory = root.resolve("input");
        Path repoDirectory = root.resolve("repo");
        Path outputDirectory = root.resolve("output");
        rejectSymlink(inputDirectory);
        rejectSymlink(repoDirectory);
        rejectSymlink(outputDirectory);
        rejectSymlink(cacheDirectory);
        Files.createDirectories(inputDirectory);
        Files.createDirectories(repoDirectory);
        Files.createDirectories(outputDirectory);
        Files.createDirectories(cacheDirectory);
        ensureRealPathInsideWorkspaceRoot(root);
        ensureRealPathInsideWorkspaceRoot(inputDirectory);
        ensureRealPathInsideWorkspaceRoot(repoDirectory);
        ensureRealPathInsideWorkspaceRoot(outputDirectory);
        ensureRealPathInsideWorkspaceRoot(cacheDirectory);

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

        return new RepairWorkspace(root, inputDirectory, repoDirectory, outputDirectory, cacheDirectory, files);
    }

    /**
     * 创建并校验任务根目录，但不写入任意输入、输出或仓库内容。
     *
     * <p>运行器可以先用此目录取得任务级执行锁，避免并发 attempt 在
     * {@link #create(RepairJobCommand)} 写入 {@code input/} 时互相覆盖。</p>
     *
     * @param command 修复执行命令
     * @return 已创建且位于配置工作区根目录内的任务目录
     * @throws IOException 创建或校验根目录失败
     */
    public Path prepareWorkspaceRoot(RepairJobCommand command) throws IOException {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String taskDirectoryName = requireSafeTaskDirectoryName(command.taskId());
        Path taskRoot = workspaceRoot.resolve(taskDirectoryName).toAbsolutePath().normalize();
        ensureInsideWorkspaceRoot(taskRoot);
        rejectSymlink(taskRoot);
        Files.createDirectories(taskRoot);
        rejectSymlink(taskRoot);
        ensureRealPathInsideWorkspaceRoot(taskRoot);
        return taskRoot;
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
                "acceptanceCoverage",
                "budgetEstimate"
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
                },
                "budgetEstimate": {
                  "type": "object",
                  "required": [
                    "initialTokens",
                    "retryReserveTokens",
                    "estimatedTotalTokens",
                    "confidence",
                    "basis",
                    "historicalSamples"
                  ],
                  "properties": {
                    "initialTokens": {"type": "integer", "minimum": 0},
                    "retryReserveTokens": {"type": "integer", "minimum": 0},
                    "estimatedTotalTokens": {"type": "integer", "minimum": 0},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
                    "basis": {"type": "string", "pattern": "\\\\S"},
                    "historicalSamples": {"type": "array"}
                  }
                },
                "next_prompt": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["targetRole", "summary", "handoffArtifact"],
                  "properties": {
                    "targetRole": {"const": "SOLUTION_ARCHITECT"},
                    "summary": {"type": "string", "maxLength": 1200},
                    "handoffArtifact": {"const": "handoff/next.md"}
                  }
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
                },
                "next_prompt": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["targetRole", "summary", "handoffArtifact"],
                  "properties": {
                    "targetRole": {"const": "CODING_AGENT"},
                    "summary": {"type": "string", "maxLength": 1200},
                    "handoffArtifact": {"const": "handoff/next.md"}
                  }
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
                "failureCategory",
                "retryRecommendation",
                "browserValidation",
                "acceptanceResults",
                "evidenceManifestArtifactId"
              ],
              "properties": {
                "status": {
                  "type": "string",
                  "enum": ["PASSED", "FAILED", "SKIPPED"]
                },
                "summary": {"type": "string", "pattern": "\\\\S"},
                "failureCategory": {
                  "type": "string",
                  "enum": [
                    "NONE",
                    "PRODUCT_DEFECT",
                    "REGRESSION",
                    "ENVIRONMENT",
                    "AUTHENTICATION",
                    "QA_INFRASTRUCTURE",
                    "REQUIREMENT_AMBIGUITY",
                    "FLAKY"
                  ]
                },
                "retryRecommendation": {
                  "type": "string",
                  "enum": ["NONE", "CODING_AGENT", "HUMAN"]
                },
                "browserValidation": {
                  "type": "object",
                  "additionalProperties": true,
                  "required": [
                    "required",
                    "performed",
                    "decisionSource",
                    "baseUrl",
                    "browser",
                    "viewports"
                  ],
                  "properties": {
                    "required": {"type": "boolean"},
                    "performed": {"type": "boolean"},
                    "decisionSource": {
                      "type": "string",
                      "enum": [
                        "TASK_OVERRIDE",
                        "PROJECT_PROFILE",
                        "REPOSITORY_CONFIG",
                        "AUTO_DETECTION",
                        "NOT_APPLICABLE",
                        "DOCS_ONLY"
                      ]
                    },
                    "baseUrl": {"type": "string"},
                    "browser": {"type": "string", "pattern": "\\\\S"},
                    "viewports": {
                      "type": "array",
                      "items": {"type": "string", "pattern": "\\\\S"}
                    }
                  }
                },
                "acceptanceResults": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "additionalProperties": true,
                    "required": [
                      "criteria",
                      "scope",
                      "command",
                      "status",
                      "exitCode",
                      "durationMillis",
                      "logArtifactId",
                      "evidenceArtifactIds"
                    ],
                    "properties": {
                      "criteria": {"type": "string", "pattern": "\\\\S"},
                      "scope": {
                        "type": "string",
                        "enum": ["CURRENT", "REGRESSION"]
                      },
                      "command": {"type": "string", "pattern": "\\\\S"},
                      "status": {
                        "type": "string",
                        "enum": ["PASSED", "FAILED", "SKIPPED"]
                      },
                      "exitCode": {"type": "integer"},
                      "durationMillis": {"type": "integer", "minimum": 0},
                      "logArtifactId": {"type": "string", "pattern": "\\\\S"},
                      "evidenceArtifactIds": {
                        "type": "array",
                        "minItems": 1,
                        "items": {"type": "string", "pattern": "\\\\S"}
                      }
                    }
                  }
                },
                "evidenceManifestArtifactId": {"type": "string", "pattern": "\\\\S"},
                "hostAssertionResults": {
                  "type": "array",
                  "minItems": 2,
                  "maxItems": 2,
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["scope", "contentHash", "evidenceArtifactIds"],
                    "properties": {
                      "scope": {"type": "string", "enum": ["CURRENT", "REGRESSION"]},
                      "contentHash": {"type": "string", "pattern": "^sha256:[0-9a-fA-F]{64}$"},
                      "evidenceArtifactIds": {
                        "type": "array",
                        "minItems": 1,
                        "items": {"type": "string", "pattern": "\\\\S"}
                      }
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
