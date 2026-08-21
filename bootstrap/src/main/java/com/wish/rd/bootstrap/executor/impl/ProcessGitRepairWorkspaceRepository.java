package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final String REQUIREMENT_PUBLICATION_TASK_ID = "requirementPublicationTaskId";
    private static final String REQUIREMENT_PUBLICATION_OPERATION_ID = "requirementPublicationOperationId";
    private static final String REQUIREMENT_PUBLICATION_CANDIDATE_PATCH_SHA256 =
            "requirementPublicationCandidatePatchSha256";

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

        boolean localOnly = "LOCAL_ONLY".equalsIgnoreCase(
                command.policyJson().getOrDefault("repositoryDeliveryMode", "")
        );
        boolean workBranchFetched;
        if (Files.isDirectory(repoDirectory.resolve(".git"))) {
            // 复用的工作区可能残留上一次尝试未提交的改动（例如 agent 写完代码但未交卷），
            // 不清理会让 checkout -B 因 "would be overwritten" 直接失败。工作区本身可抛弃，
            // 候选补丁由附件重放，因此 reset + clean 不会丢失任何需要保留的数据。
            runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "reset", "--hard"));
            runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "clean", "-fd"));
            runGitRetryingTransientNetwork(List.of(
                    GIT_BINARY, "-C", repoDirectory.toString(), "fetch", "origin", command.baseBranch()));
            workBranchFetched = !localOnly && fetchRemoteWorkBranch(repoDirectory, command.workBranch());
        } else {
            requireEmptyDirectory(repoDirectory);
            runGitRetryingTransientNetwork(List.of(
                    GIT_BINARY, "clone", "--branch", command.baseBranch(), "--single-branch",
                    command.repositoryUrl(), repoDirectory.toString()));
            workBranchFetched = !localOnly && fetchRemoteWorkBranch(repoDirectory, command.workBranch());
        }
        runGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "checkout",
                "-B",
                command.workBranch(),
                workBranchFetched ? "FETCH_HEAD" : "origin/" + command.baseBranch()
        ));
        boolean candidatePatchApplied = applyCandidatePatchIfRequested(command, workspace, repoDirectory);

        runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "config", "user.name", properties.getUserName()));
        runGit(List.of(GIT_BINARY, "-C", repoDirectory.toString(), "config", "user.email", properties.getUserEmail()));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("prepared", "true");
        metadata.put("baseBranch", command.baseBranch());
        metadata.put("workBranch", command.workBranch());
        metadata.put("repositoryDeliveryMode", localOnly ? "LOCAL_ONLY" : "PUBLISH");
        metadata.put("checkoutSource", workBranchFetched ? "origin-work-branch" : "origin-base-branch");
        metadata.put("repository", repositoryName(command));
        metadata.put("candidatePatchApplied", Boolean.toString(candidatePatchApplied));
        return new RepositoryOperationResult(metadata);
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

    @Override
    public RepositoryState repositoryState(RepairJobCommand command, RepairWorkspace workspace) throws IOException {
        Path repoDirectory = requireRepoDirectory(workspace);
        if (!Files.isDirectory(repoDirectory.resolve(".git"))) {
            return RepositoryState.unsupported();
        }
        CommandResult status = runGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "status",
                "--porcelain=v1",
                "--untracked-files=all"
        ));
        String porcelain = status.stdout();
        String summary = porcelain.isBlank() ? "" : repositoryStateSummary(porcelain);
        return new RepositoryState(
                true,
                porcelain.isBlank(),
                contentLevelFingerprint(repoDirectory, porcelain),
                summary
        );
    }

    /**
     * 内容级仓库指纹：对 QA 仅执行 git add（暂存状态变化、内容零变化）不敏感。
     *
     * <p>旧实现对 porcelain 全文哈希，状态码从 {@code " M"} 变 {@code "M "} 就会误判
     * “QA 篡改仓库”（django-11019 实跑误拦）。现改为：
     * {@code git diff HEAD}（对暂存/未暂存不敏感）+ 未跟踪文件路径及内容哈希。
     * HEAD 不可用时回退 porcelain 哈希。
     */
    private String contentLevelFingerprint(Path repoDirectory, String porcelain) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CommandResult diff = runGit(List.of(
                    GIT_BINARY, "-C", repoDirectory.toString(), "diff", "HEAD"
            ));
            digest.update(diff.stdout().getBytes(StandardCharsets.UTF_8));
            for (String line : (porcelain == null ? "" : porcelain).split("\n")) {
                if (!line.startsWith("??")) {
                    continue;
                }
                String path = line.substring(2).strip();
                digest.update(("\0" + path + "\0").getBytes(StandardCharsets.UTF_8));
                Path file = repoDirectory.resolve(path);
                if (Files.isRegularFile(file)) {
                    digest.update(Files.readAllBytes(file));
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (IOException exception) {
            // HEAD 不可用（空仓等）：回退到旧的 porcelain 哈希，保持可比较性
            return repositoryStateFingerprint(porcelain);
        }
    }

    private static String repositoryStateFingerprint(String porcelain) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((porcelain == null ? "" : porcelain).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String repositoryStateSummary(String porcelain) {
        int tracked = 0;
        int untracked = 0;
        Map<String, Integer> untrackedPaths = new LinkedHashMap<>();
        List<String> trackedFiles = new ArrayList<>();
        for (String line : (porcelain == null ? "" : porcelain).lines().toList()) {
            if (line.length() < 4) {
                continue;
            }
            String status = line.substring(0, 2);
            String path = line.substring(3).strip();
            if ("??".equals(status)) {
                untracked++;
                untrackedPaths.merge(repositoryPathBucket(path), 1, Integer::sum);
            } else {
                tracked++;
                if (trackedFiles.size() < 5) {
                    trackedFiles.add(path);
                }
            }
        }
        StringBuilder summary = new StringBuilder("tracked=")
                .append(tracked)
                .append(", untracked=")
                .append(untracked);
        if (!untrackedPaths.isEmpty()) {
            summary.append("; untrackedPaths=");
            summary.append(untrackedPaths.entrySet().stream()
                    .limit(8)
                    .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(""));
        }
        if (!trackedFiles.isEmpty()) {
            summary.append("; trackedFiles=").append(String.join(", ", trackedFiles));
        }
        return summary.toString();
    }

    private static String repositoryPathBucket(String rawPath) {
        String path = rawPath == null ? "" : rawPath.strip();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        String[] segments = path.split("/");
        return segments.length >= 2 ? segments[0] + "/" + segments[1] : path;
    }

    private CommandResult runGit(List<String> argv) throws IOException {
        CommandResult result = executeGit(argv);
        if (result.exitCode() != 0) {
            throw new IOException("git command failed exitCode=" + result.exitCode()
                    + " command=" + sanitizedArgv(argv)
                    + " stderr=" + limit(result.stderr()));
        }
        return result;
    }

    /**
     * Retries clone/fetch a bounded number of times when the host TLS stack drops GitHub
     * ({@code SSL_ERROR_SYSCALL} / {@code unable to access}). Clash/TUN blips must not
     * fail a whole Agent role on the first handshake. Failed clones empty the destination
     * so the next attempt (or operator retry) is not blocked by leftover files.
     */
    private CommandResult runGitRetryingTransientNetwork(List<String> argv) throws IOException {
        Path cloneDestination = cloneDestination(argv);
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (attempt > 1 && cloneDestination != null) {
                    emptyDirectoryContents(cloneDestination);
                }
                return runGit(argv);
            } catch (IOException exception) {
                last = exception;
                if (cloneDestination != null) {
                    emptyDirectoryContents(cloneDestination);
                }
                if (attempt == 3 || !isTransientNetworkFailure(exception.getMessage())) {
                    throw exception;
                }
                try {
                    TimeUnit.SECONDS.sleep(attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
        throw last;
    }

    public static boolean isTransientNetworkFailure(String message) {
        String normalized = message == null ? "" : message;
        if (normalized.contains("Authentication failed")
                || normalized.contains("Repository not found")
                || normalized.contains("returned error: 401")
                || normalized.contains("returned error: 403")
                || normalized.contains("returned error: 404")) {
            return false;
        }
        return normalized.contains("SSL_ERROR_SYSCALL")
                || normalized.contains("unable to access")
                || normalized.contains("Could not resolve host")
                || normalized.contains("The remote end hung up")
                || normalized.contains("RPC failed")
                || normalized.contains("Connection reset by peer");
    }

    private static Path cloneDestination(List<String> argv) {
        if (!argv.contains("clone") || argv.size() < 2) {
            return null;
        }
        return Path.of(argv.get(argv.size() - 1));
    }

    private static void emptyDirectoryContents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private CommandResult tryRunGit(List<String> argv) throws IOException {
        return executeGit(argv);
    }

    private CommandResult executeGit(List<String> argv) throws IOException {
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
            return new CommandResult(process.exitValue(), await(stdout), await(stderr));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted: " + sanitizedArgv(argv), exception);
        }
    }

    private boolean fetchRemoteWorkBranch(Path repoDirectory, String workBranch) throws IOException {
        CommandResult result = tryRunGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "fetch",
                "origin",
                workBranch
        ));
        return result.exitCode() == 0;
    }

    private boolean applyCandidatePatchIfRequested(
            RepairJobCommand command,
            RepairWorkspace workspace,
            Path repoDirectory
    ) throws IOException {
        if (!Boolean.parseBoolean(command.policyJson().getOrDefault("applyCandidatePatch", "false"))) {
            return false;
        }
        Path attachmentDirectory = workspace.inputDirectory().resolve("attachments").normalize();
        Path patch = attachmentDirectory.resolve("candidate-patch.diff").normalize();
        if (!patch.startsWith(attachmentDirectory) || !Files.isRegularFile(patch)) {
            throw new IOException("local QA requires a verified candidate-patch.diff attachment");
        }
        // Reset the workspace to a clean HEAD before applying the candidate
        // patch.  Previous QA attempts may have left staged or unstaged
        // changes that cause `git apply --index` to fail with "patch does
        // not apply".
        runGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "reset",
                "--hard",
                "HEAD"
        ));
        runGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "apply",
                "--index",
                "--whitespace=nowarn",
                patch.toString()
        ));
        CommandResult staged = tryRunGit(List.of(
                GIT_BINARY,
                "-C",
                repoDirectory.toString(),
                "diff",
                "--cached",
                "--quiet"
        ));
        if (staged.exitCode() == 0) {
            throw new IOException("candidate-patch.diff did not produce a staged repository change");
        }
        if (staged.exitCode() != 1) {
            throw new IOException("could not verify candidate-patch.diff application: " + limit(staged.stderr()));
        }
        return true;
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

    private static String commitMessage(RepairJobCommand command) throws IOException {
        String publicationTaskId = policyValue(command, REQUIREMENT_PUBLICATION_TASK_ID);
        String operationId = policyValue(command, REQUIREMENT_PUBLICATION_OPERATION_ID);
        String candidatePatchSha256 = policyValue(command, REQUIREMENT_PUBLICATION_CANDIDATE_PATCH_SHA256);
        if (!publicationTaskId.isBlank() || !operationId.isBlank() || !candidatePatchSha256.isBlank()) {
            requireSingleLineMarker(publicationTaskId, REQUIREMENT_PUBLICATION_TASK_ID);
            requireSingleLineMarker(operationId, REQUIREMENT_PUBLICATION_OPERATION_ID);
            requireSingleLineMarker(candidatePatchSha256, REQUIREMENT_PUBLICATION_CANDIDATE_PATCH_SHA256);
            return "RD-Bot requirement " + publicationTaskId
                    + "\n\nrd-operation-id: " + operationId
                    + "\nrd-candidate-patch-sha256: " + candidatePatchSha256;
        }
        String ticket = command.ticketId().isBlank() ? command.taskId() : command.ticketId();
        return "RD-Bot repair " + ticket;
    }

    private static String policyValue(RepairJobCommand command, String key) {
        if (command == null || command.policyJson() == null) {
            return "";
        }
        String value = command.policyJson().getOrDefault(key, "");
        return value == null ? "" : value.strip();
    }

    private static void requireSingleLineMarker(String value, String fieldName) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("requirement publication marker must not be blank: " + fieldName);
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("requirement publication marker must be one line: " + fieldName);
        }
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
