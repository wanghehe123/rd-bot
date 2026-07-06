package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.docker.ContainerControlPort;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopCommand;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 基于 {@link ProcessBuilder} 的 Docker CLI 容器执行适配器。
 *
 * <p>该类只负责把 exec 模块的容器请求翻译为 {@code docker run} argv，并收集进程输出；
 * 不拼接 shell 命令，不在日志或元数据中写入原始密钥值。
 */
@Component
@ConditionalOnProperty(prefix = "rd.executor.docker", name = "enabled", havingValue = "true")
public class ProcessContainerRunner implements ContainerRunnerPort, ContainerControlPort {

    private static final String DOCKER_BINARY = "docker";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DockerExecutorProperties properties;
    private final CommandLauncher commandLauncher;

    /**
     * 创建生产 Docker CLI runner。
     *
     * @param properties Docker 执行配置
     */
    @Autowired
    public ProcessContainerRunner(DockerExecutorProperties properties) {
        this(properties, ProcessContainerRunner::launchProcess);
    }

    /**
     * 创建可注入 launcher 的 runner，供单元测试避免调用真实 Docker。
     *
     * @param properties       Docker 执行配置
     * @param commandLauncher  命令启动器
     */
    public ProcessContainerRunner(DockerExecutorProperties properties, CommandLauncher commandLauncher) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.commandLauncher = Objects.requireNonNull(commandLauncher, "commandLauncher must not be null");
    }

    @Override
    public ContainerRunResult run(ContainerRunRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Files.createDirectories(request.outputDirectory());
        List<String> argv = buildCommand(request);
        CommandResult commandResult = launch(argv, request.env());
        writeDockerMetadata(request, argv, commandResult);
        return new ContainerRunResult(
                commandResult.exitCode(),
                commandResult.durationMillis(),
                commandResult.stdout(),
                commandResult.stderr(),
                artifactIfExists(request.outputDirectory(), "result.json"),
                artifactIfExists(request.outputDirectory(), "patch.diff"),
                artifactIfExists(request.outputDirectory(), "test.log"),
                artifactIfExists(request.outputDirectory(), "claude-events.jsonl"),
                artifactIfExists(request.outputDirectory(), "docker-meta.json"),
                metadata(request, argv, commandResult)
        );
    }

    @Override
    public RepairExecutionStopResult stop(RepairExecutionStopCommand command) {
        if (command == null || command.containerName().isBlank()) {
            return new RepairExecutionStopResult("", "", false, "containerName must not be blank");
        }
        try {
            CommandResult result = launch(List.of(DOCKER_BINARY, "stop", command.containerName()), Map.of());
            boolean stopped = result.exitCode() == 0;
            String message = stopped ? "container stopped" : result.stderr();
            return new RepairExecutionStopResult(command.taskId(), command.containerName(), stopped, message);
        } catch (IOException exception) {
            return new RepairExecutionStopResult(
                    command.taskId(),
                    command.containerName(),
                    false,
                    exception.getMessage()
            );
        }
    }

    /**
     * 构造 {@code docker run} argv。该方法用于测试和诊断，不包含 shell 字符串。
     *
     * @param request 容器执行请求
     * @return Docker CLI argv
     */
    public List<String> buildCommand(ContainerRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        List<String> argv = new ArrayList<>();
        argv.add(DOCKER_BINARY);
        argv.add("run");
        if (request.removeAfterExit()) {
            argv.add("--rm");
        }
        argv.add("--name");
        argv.add(request.containerName());
        if (!request.networkMode().isBlank()) {
            argv.add("--network");
            argv.add(request.networkMode());
        }
        if (request.allowPrivileged()) {
            argv.add("--privileged");
        }
        request.mounts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    argv.add("-v");
                    argv.add(entry.getKey() + ":" + entry.getValue());
                });
        if (!request.workingDirectory().isBlank()) {
            argv.add("-w");
            argv.add(request.workingDirectory());
        }
        appendEnvironment(argv, request.env());
        argv.add(request.image());
        argv.addAll(request.command());
        return List.copyOf(argv);
    }

    private CommandResult launch(List<String> argv, Map<String, String> environment) throws IOException {
        try {
            CommandResult result = commandLauncher.launch(argv, processEnvironment(environment));
            if (result == null) {
                throw new IOException("docker command launcher returned null result");
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("docker command interrupted", exception);
        }
    }

    private static Map<String, String> processEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return Map.of();
        }
        Map<String, String> processEnvironment = new LinkedHashMap<>();
        environment.forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key.strip();
            String normalizedValue = value == null ? "" : value;
            if (!normalizedKey.isBlank() && !normalizedValue.isBlank()) {
                processEnvironment.put(normalizedKey, normalizedValue);
            }
        });
        return Map.copyOf(processEnvironment);
    }

    private static void appendEnvironment(List<String> argv, Map<String, String> env) {
        Set<String> emitted = new LinkedHashSet<>();
        env.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> emitted.add(entry.getKey()))
                .forEach(entry -> {
                    argv.add("-e");
                    argv.add(environmentArgument(entry.getKey(), entry.getValue()));
                });
    }

    private static String environmentArgument(String key, String value) {
        if (value == null || value.isBlank() || isSecretEnvKey(key)) {
            return key;
        }
        return key + "=" + value;
    }

    private static boolean isSecretEnvKey(String key) {
        String normalized = key == null ? "" : key.toUpperCase();
        if ("RD_CLAUDE_AUTH_TOKEN_ENV".equals(normalized) || "RD_CLAUDE_API_KEY_ENV".equals(normalized)) {
            return false;
        }
        return normalized.contains("SECRET")
                || normalized.contains("TOKEN")
                || normalized.contains("PASSWORD")
                || normalized.contains("API_KEY")
                || normalized.contains("ACCESS_KEY")
                || normalized.equals("PAT")
                || normalized.endsWith("_PAT");
    }

    private static CommandResult launchProcess(
            List<String> argv,
            Map<String, String> environment
    ) throws IOException, InterruptedException {
        Instant startedAt = Instant.now();
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        int exitCode = process.waitFor();
        long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
        return new CommandResult(exitCode, durationMillis, await(stdout), await(stderr));
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

    private void writeDockerMetadata(
            ContainerRunRequest request,
            List<String> argv,
            CommandResult commandResult
    ) throws IOException {
        Path dockerMetaJson = request.outputDirectory().resolve("docker-meta.json");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("containerName", request.containerName());
        metadata.put("image", request.image());
        metadata.put("argv", sanitizedArgv(argv));
        metadata.put("workingDirectory", request.workingDirectory());
        metadata.put("networkMode", request.networkMode());
        metadata.put("removeAfterExit", request.removeAfterExit());
        metadata.put("exitCode", commandResult.exitCode());
        metadata.put("durationMillis", commandResult.durationMillis());
        OBJECT_MAPPER.writeValue(dockerMetaJson.toFile(), metadata);
    }

    private Map<String, String> metadata(
            ContainerRunRequest request,
            List<String> argv,
            CommandResult commandResult
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("containerName", request.containerName());
        metadata.put("image", request.image());
        metadata.put("networkMode", request.networkMode());
        metadata.put("removeAfterExit", String.valueOf(request.removeAfterExit()));
        metadata.put("argv", String.join(" ", sanitizedArgv(argv)));
        metadata.put("exitCode", String.valueOf(commandResult.exitCode()));
        metadata.put("durationMillis", String.valueOf(commandResult.durationMillis()));
        metadata.put("workspaceRoot", properties.getWorkspaceRoot().toString());
        return Map.copyOf(metadata);
    }

    private static List<String> sanitizedArgv(List<String> argv) {
        List<String> sanitized = new ArrayList<>();
        for (String arg : argv) {
            if (containsSecretAssignment(arg)) {
                int split = arg.indexOf('=');
                sanitized.add(arg.substring(0, split + 1) + "<redacted>");
            } else {
                sanitized.add(arg);
            }
        }
        return List.copyOf(sanitized);
    }

    private static boolean containsSecretAssignment(String arg) {
        int split = arg == null ? -1 : arg.indexOf('=');
        return split > 0 && isSecretEnvKey(arg.substring(0, split));
    }

    private static Path artifactIfExists(Path outputDirectory, String artifactName) {
        Path artifact = outputDirectory.resolve(artifactName).toAbsolutePath().normalize();
        return Files.isRegularFile(artifact) ? artifact : null;
    }

    /**
     * 可替换命令启动器，单元测试使用 fake 实现避免真实 Docker。
     */
    @FunctionalInterface
    public interface CommandLauncher {

        /**
         * 运行给定 argv。
         *
         * @param argv 进程 argv
         * @param environment 传给 Docker CLI 进程的环境变量
         * @return 进程结果
         * @throws IOException          启动或读取失败
         * @throws InterruptedException 等待进程时被中断
         */
        CommandResult launch(List<String> argv, Map<String, String> environment) throws IOException, InterruptedException;
    }

    /**
     * 进程启动结果。
     *
     * @param exitCode       退出码
     * @param durationMillis 运行耗时毫秒
     * @param stdout         标准输出
     * @param stderr         标准错误
     */
    public record CommandResult(int exitCode, long durationMillis, String stdout, String stderr) {

        public CommandResult {
            durationMillis = Math.max(0L, durationMillis);
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}
