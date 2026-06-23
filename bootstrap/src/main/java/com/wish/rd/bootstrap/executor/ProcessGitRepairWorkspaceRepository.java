package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.docker.RepairWorkspace;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 基于本机 Git CLI 的 Docker 修复工作区仓库适配器，负责 clone、checkout、commit 和 push。
 */
@Component
@ConditionalOnProperty(prefix = "rd.executor.docker.git", name = "enabled", havingValue = "true")
public class ProcessGitRepairWorkspaceRepository implements RepairWorkspaceRepositoryPort {

    private static final String GIT_BINARY = "git";
    private static final int OUTPUT_LIMIT = 2_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DockerExecutorProperties.GitProperties properties;

    /**
     * 创建本机 Git CLI 仓库适配器。
     *
     * @param properties Docker executor 配置
     */
    public ProcessGitRepairWorkspaceRepository(DockerExecutorProperties properties) {
        DockerExecutorProperties safeProperties = properties == null ? new DockerExecutorProperties() : properties;
        this.properties = safeProperties.getGit();
    }

    @Override
    public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) throws IOException {
        requireCommand(command);
        Path repoDirectory = requireRepoDirectory(workspace);
        Files.createDirectories(repoDirectory);

        if (Files.isDirectory(repoDirectory.resolve(".git"))) {
            runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "fetch", "origin", command.baseBranch()));
            runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "checkout", "-B",
                    command.workBranch(), "origin/" + command.baseBranch()));
        } else {
            requireEmptyDirectory(repoDirectory);
            runGit(List.of(GIT_BINARY, "clone", "--branch", command.baseBranch(), "--single-branch",
                    command.repositoryUrl(), repoDirectory.toString()));
            runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "checkout", "-B", command.workBranch()));
        }

        runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "config", "user.name", properties.getUserName()));
        runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "config", "user.email", properties.getUserEmail()));
        return new RepositoryOperationResult(Map.of(
                "prepared", "true",
                "baseBranch", command.baseBranch(),
                "workBranch", command.workBranch(),
                "repository", repositoryName(command)
        ));
    }

    @Override
    public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) throws IOException {
        requireCommand(command);
        Path repoDirectory = requireRepoDirectory(workspace);
        if (!Files.isDirectory(repoDirectory.resolve(".git"))) {
            throw new IOException("git repository is missing in workspace: " + repoDirectory);
        }

        CommandResult status = runGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "status",
                "--porcelain",
                "--untracked-files=all"
        ));
        if (status.stdout().isBlank()) {
            throw new IOException("no repository changes to publish");
        }

        List<String> publishableFiles = publishableChangedFiles(workspace, status.stdout());
        if (publishableFiles.isEmpty()) {
            throw new IOException("no publishable repository changes to publish");
        }
        runGit(gitAddCommand(repoDirectory, publishableFiles));
        CommandResult staged = runGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "diff",
                "--cached",
                "--name-only"
        ));
        List<String> stagedFiles = plainChangedFileList(staged.stdout());
        if (stagedFiles.isEmpty()) {
            throw new IOException("no staged repository changes to publish");
        }
        runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "commit", "-m", commitMessage(command)));
        String commitSha = runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "rev-parse", "HEAD"))
                .stdout()
                .strip();
        runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "push", "-u", "origin", command.workBranch()));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("pushed", "true");
        metadata.put("workBranch", command.workBranch());
        metadata.put("commitSha", commitSha);
        metadata.put("changedFiles", String.join(",", stagedFiles));
        return new RepositoryOperationResult(metadata);
    }

    private CommandResult runGit(List<String> argv) throws IOException {
        Process process = new ProcessBuilder(argv).start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            boolean completed = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("git command timed out after " + properties.getTimeoutSeconds()
                        + "s: " + sanitizedArgv(argv));
            }
            CommandResult result = new CommandResult(process.exitValue(), await(stdout), await(stderr));
            if (result.exitCode() != 0) {
                throw new IOException("git command failed exitCode=" + result.exitCode()
                        + " command=" + sanitizedArgv(argv)
                        + " stderr=" + limit(result.stderr()));
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted: " + sanitizedArgv(argv), exception);
        }
    }

    private static CompletableFuture<String> readAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream source = inputStream) {
                return new String(source.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private static String await(CompletableFuture<String> output) throws IOException {
        try {
            return output.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static void requireCommand(RepairJobCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        requireText(command.repositoryUrl(), "repositoryUrl");
        requireText(command.baseBranch(), "baseBranch");
        requireText(command.workBranch(), "workBranch");
    }

    private static Path requireRepoDirectory(RepairWorkspace workspace) {
        if (workspace == null || workspace.repoDirectory() == null) {
            throw new IllegalArgumentException("workspace repoDirectory must not be null");
        }
        return workspace.repoDirectory().toAbsolutePath().normalize();
    }

    private static void requireEmptyDirectory(Path repoDirectory) throws IOException {
        try (var files = Files.list(repoDirectory)) {
            if (files.findAny().isPresent()) {
                throw new IOException("workspace repo directory is not empty: " + repoDirectory);
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String commitMessage(RepairJobCommand command) {
        String ticket = command.ticketId().isBlank() ? command.taskId() : command.ticketId();
        return "RD-Bot repair " + ticket;
    }

    private static String repositoryName(RepairJobCommand command) {
        if (!command.repoOwner().isBlank() && !command.repoName().isBlank()) {
            return command.repoOwner() + "/" + command.repoName();
        }
        return "";
    }

    private static List<String> publishableChangedFiles(RepairWorkspace workspace, String statusOutput) throws IOException {
        List<String> structuredFiles = structuredChangedFiles(workspace);
        List<String> sourceFiles = structuredFiles.isEmpty() ? statusChangedFileList(statusOutput) : structuredFiles;
        return sourceFiles.stream()
                .map(ProcessGitRepairWorkspaceRepository::normalizeRepositoryPath)
                .flatMap(Optional::stream)
                .filter(ProcessGitRepairWorkspaceRepository::isPublishablePath)
                .distinct()
                .toList();
    }

    private static List<String> structuredChangedFiles(RepairWorkspace workspace) throws IOException {
        if (workspace == null || workspace.files() == null || workspace.files().resultJson() == null) {
            return List.of();
        }
        Path resultJson = workspace.files().resultJson();
        if (!Files.isRegularFile(resultJson)) {
            return List.of();
        }
        JsonNode changedFiles = OBJECT_MAPPER.readTree(resultJson.toFile()).path("changedFiles");
        if (changedFiles.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode node : changedFiles) {
                values.add(node.asText(""));
            }
            return values;
        }
        if (changedFiles.isTextual()) {
            return List.of(changedFiles.asText().split(","));
        }
        return List.of();
    }

    private static List<String> statusChangedFileList(String statusOutput) {
        return statusOutput.lines()
                .map(line -> line.length() > 3 ? line.substring(3).strip() : line.strip())
                .filter(line -> !line.isBlank())
                .map(ProcessGitRepairWorkspaceRepository::normalizeRepositoryPath)
                .flatMap(Optional::stream)
                .toList();
    }

    private static List<String> plainChangedFileList(String output) {
        return output.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .map(ProcessGitRepairWorkspaceRepository::normalizeRepositoryPath)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<String> normalizeRepositoryPath(String value) {
        String normalized = value == null ? "" : value.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("\u0000")) {
            return Optional.empty();
        }
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            return Optional.empty();
        }
        String repositoryPath = path.toString().replace('\\', '/');
        if (repositoryPath.isBlank() || ".".equals(repositoryPath)) {
            return Optional.empty();
        }
        return Optional.of(repositoryPath);
    }

    private static boolean isPublishablePath(String repositoryPath) {
        return !hasPathSegment(repositoryPath, ".git")
                && !hasPathSegment(repositoryPath, "node_modules")
                && !hasPathSegment(repositoryPath, "dist")
                && !hasPathSegment(repositoryPath, "build")
                && !hasPathSegment(repositoryPath, "coverage")
                && !hasPathSegment(repositoryPath, ".next")
                && !hasPathSegment(repositoryPath, ".cache")
                && !hasPathSegment(repositoryPath, "target");
    }

    private static boolean hasPathSegment(String repositoryPath, String segment) {
        for (String pathSegment : repositoryPath.split("/")) {
            if (pathSegment.equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> gitAddCommand(Path repoDirectory, List<String> files) {
        List<String> argv = new ArrayList<>(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "add",
                "--"
        ));
        argv.addAll(files);
        return argv;
    }

    private static String sanitizedArgv(List<String> argv) {
        List<String> sanitized = new ArrayList<>();
        for (String arg : argv) {
            sanitized.add(sanitizeCredentialUrl(arg));
        }
        return String.join(" ", sanitized);
    }

    private static String sanitizeCredentialUrl(String value) {
        String normalized = value == null ? "" : value;
        return normalized.replaceAll("(https?://)[^/@]+@", "$1<redacted>@");
    }

    private static String limit(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() <= OUTPUT_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, OUTPUT_LIMIT) + "...";
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {

        private CommandResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}
