package com.wish.rd.exec.repair.verify;

import com.wish.rd.exec.repair.verify.model.HostVerificationCommandResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one allowlisted host verification command in a working directory.
 *
 * <p>Used by {@code HostVerificationExecutorAdapter}. Splits the command on
 * spaces and launches {@link ProcessBuilder} without {@code /bin/sh -c} wrappers.
 * Never deletes {@code cache/} or {@code node_modules}.
 */
public final class HostVerificationCommandRunner {

    static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

    private final int maxOutputBytes;

    /**
     * Creates a runner that caps combined stdout/stderr at 256 KiB.
     */
    public HostVerificationCommandRunner() {
        this(DEFAULT_MAX_OUTPUT_BYTES);
    }

    /**
     * Creates a runner with an explicit output cap.
     *
     * @param maxOutputBytes maximum captured bytes; values below 1 fall back to the default
     */
    public HostVerificationCommandRunner(int maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes < 1 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes;
    }

    /**
     * Executes {@code command} in {@code workingDirectory} and waits up to {@code timeout}.
     *
     * @param command           allowlisted command line, split on whitespace
     * @param workingDirectory  existing repository root
     * @param timeout           wall-clock budget for this process
     * @return exit code, bounded combined output, and timeout flag
     * @throws IllegalArgumentException when command, directory, or timeout is invalid
     */
    public HostVerificationCommandResult run(String command, Path workingDirectory, Duration timeout) {
        String normalized = command == null ? "" : command.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        List<String> argv = splitCommand(normalized);
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException exception) {
            // 可执行文件不在 PATH 时按约定记 127，供适配器归类为环境失败
            return new HostVerificationCommandResult(
                    127,
                    "command not found: " + normalized + System.lineSeparator() + exception.getMessage(),
                    false
            );
        }
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(process.getInputStream(), collected), "host-verify-cmd-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new HostVerificationCommandResult(-1, outputOf(collected) + "\n[interrupted]", true);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            joinQuietly(reader);
            return new HostVerificationCommandResult(-1, outputOf(collected) + "\n[timed out]", true);
        }
        joinQuietly(reader);
        return new HostVerificationCommandResult(process.exitValue(), outputOf(collected), false);
    }

    private void drain(InputStream stream, ByteArrayOutputStream collected) {
        byte[] buffer = new byte[4096];
        try (InputStream in = stream) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (collected) {
                    int remaining = maxOutputBytes - collected.size();
                    if (remaining <= 0) {
                        continue;
                    }
                    collected.write(buffer, 0, Math.min(read, remaining));
                }
            }
        } catch (IOException ignored) {
            // 进程被杀死时读端关闭是预期的
        }
    }

    private static String outputOf(ByteArrayOutputStream collected) {
        synchronized (collected) {
            return collected.toString(StandardCharsets.UTF_8);
        }
    }

    private static void joinQuietly(Thread reader) {
        try {
            reader.join(2_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> splitCommand(String command) {
        List<String> argv = new ArrayList<>();
        for (String part : command.split("\\s+")) {
            if (!part.isBlank()) {
                argv.add(part);
            }
        }
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        return List.copyOf(argv);
    }
}
