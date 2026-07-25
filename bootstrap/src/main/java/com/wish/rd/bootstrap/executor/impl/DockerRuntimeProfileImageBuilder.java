package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Builds a user-uploaded Dockerfile and proves that it keeps the RD-Bot Claude runtime contract. */
@Component
@ConditionalOnProperty(prefix = "rd.executor.docker", name = "enabled", havingValue = "true")
public final class DockerRuntimeProfileImageBuilder {

    private static final long BUILD_TIMEOUT_MILLIS = 10 * 60 * 1000L;
    private static final long SMOKE_TIMEOUT_MILLIS = 60 * 1000L;
    private static final String RUNTIME_CONTRACT_SKILL_DIRECTORY =
            "/home/rdbot/.claude/skills/rd-bot-runtime-contract:ro";
    private static final String EXPECTED_RUNTIME_USER = "rdbot";
    private static final String RUNTIME_EXECUTABLE_FINGERPRINT_COMMAND =
            "sha256sum /usr/local/bin/rd-claude-entrypoint /usr/local/bin/claude";
    private static final Set<String> EXPECTED_ENTRYPOINTS = Set.of(
            "[\"rd-claude-entrypoint\"]",
            "[\"/usr/local/bin/rd-claude-entrypoint\"]"
    );
    private static final String SMOKE_COMMAND = """
            set -eu
            test "$(id -un)" = "rdbot"
            test "${HOME:-}" = "/home/rdbot"
            test -d /home/rdbot/.claude/skills
            test -r /home/rdbot/.claude/skills/rd-bot-runtime-contract/SKILL.md
            test ! -w /home/rdbot/.claude/skills/rd-bot-runtime-contract
            mkdir -p /home/rdbot/.claude/session-env
            session_probe=/home/rdbot/.claude/session-env/runtime-contract-probe
            : > "$session_probe"
            rm "$session_probe"
            command -v claude
            claude --version
            command -v rd-claude-entrypoint
            test -d /work
            """.strip();

    private final Path buildRoot;
    private final ProcessContainerRunner.TimedCommandLauncher commandLauncher;
    private final Set<String> trustedBaseImages;

    @Autowired
    public DockerRuntimeProfileImageBuilder(DockerExecutorProperties properties) {
        this(
                java.util.Objects.requireNonNull(properties, "properties must not be null")
                        .getWorkspaceRoot().resolve("_runtime-profile-builds"),
                DockerRuntimeProfileImageBuilder::launchProcess,
                trustedBaseImages(properties)
        );
    }

    DockerRuntimeProfileImageBuilder(
            Path buildRoot,
            ProcessContainerRunner.TimedCommandLauncher commandLauncher
    ) {
        this(
                buildRoot,
                commandLauncher,
                Set.of("rd-bot/claude-code:local", "rd-bot/claude-code-qa:local")
        );
    }

    DockerRuntimeProfileImageBuilder(
            Path buildRoot,
            ProcessContainerRunner.TimedCommandLauncher commandLauncher,
            Set<String> trustedBaseImages
    ) {
        this.buildRoot = java.util.Objects.requireNonNull(buildRoot, "buildRoot must not be null")
                .toAbsolutePath().normalize();
        this.commandLauncher = java.util.Objects.requireNonNull(commandLauncher, "commandLauncher must not be null");
        this.trustedBaseImages = trustedBaseImages == null ? Set.of() : Set.copyOf(trustedBaseImages);
        if (this.trustedBaseImages.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted Claude runtime base image is required");
        }
    }

    Set<String> trustedBaseImages() {
        return trustedBaseImages;
    }

    /** Builds and smoke-verifies a standalone Dockerfile before it can be selected by a project role. */
    public VerifiedImage buildAndVerify(String projectId, String role, byte[] dockerfile) {
        String image = imageName(projectId, role);
        if (dockerfile == null || dockerfile.length == 0) {
            throw new IllegalArgumentException("Dockerfile must not be empty");
        }
        Path context = null;
        boolean imageBuilt = false;
        boolean verified = false;
        try {
            Files.createDirectories(buildRoot);
            context = Files.createTempDirectory(buildRoot, "profile-");
            Path dockerfilePath = context.resolve("Dockerfile");
            Files.write(dockerfilePath, dockerfile);
            Path runtimeContractSkill = context.resolve("rd-bot-runtime-contract");
            Files.createDirectories(runtimeContractSkill);
            Files.writeString(
                    runtimeContractSkill.resolve("SKILL.md"),
                    "# RD-Bot runtime contract smoke fixture\n",
                    StandardCharsets.UTF_8
            );
            Path runtimeContractInput = context.resolve("rd-bot-runtime-contract-input");
            Files.createDirectories(runtimeContractInput);
            Files.writeString(runtimeContractInput.resolve("prompt.md"), "Runtime contract smoke.\n", StandardCharsets.UTF_8);
            Files.writeString(runtimeContractInput.resolve("result.schema.json"), "{\"type\":\"object\"}\n", StandardCharsets.UTF_8);
            runOrThrow(List.of(
                    "docker", "build", "--file", dockerfilePath.toString(), "--tag", image, context.toString()
            ), BUILD_TIMEOUT_MILLIS, "Docker image build");
            imageBuilt = true;
            verifyRuntimeImageContract(image);
            ProcessContainerRunner.CommandResult smoke = runOrThrow(List.of(
                    "docker", "run", "--rm",
                    "-v", runtimeContractSkill + ":" + RUNTIME_CONTRACT_SKILL_DIRECTORY,
                    "-v", runtimeContractInput + ":/work/input:ro",
                    image, "/bin/sh", "-lc", SMOKE_COMMAND
            ), SMOKE_TIMEOUT_MILLIS, "Claude Code runtime smoke check");
            verified = true;
            return new VerifiedImage(image, validationSummary(smoke));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to prepare Docker runtime build context", exception);
        } finally {
            if (imageBuilt && !verified) {
                removeFailedImageQuietly(image);
            }
            deleteQuietly(context);
        }
    }

    /** A rejected image is unreferenced, unlike a previously verified project profile, and can be safely removed. */
    private void removeFailedImageQuietly(String image) {
        try {
            commandLauncher.launch(List.of("docker", "image", "rm", "--force", image), Map.of(), SMOKE_TIMEOUT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Preserve the validation failure; an operator can inspect a rare failed cleanup separately.
        }
    }

    private void verifyRuntimeImageContract(String image) {
        ProcessContainerRunner.CommandResult inspection = runOrThrow(List.of(
                "docker", "image", "inspect", "--format={{json .Config.Entrypoint}}|{{.Config.User}}", image
        ), SMOKE_TIMEOUT_MILLIS, "Docker image runtime contract inspection");
        String contract = inspection.stdout() == null ? "" : inspection.stdout().strip();
        String[] fields = contract.split("\\|", 2);
        String entrypoint = fields.length == 2 ? fields[0].strip() : "";
        String user = fields.length == 2 ? fields[1].strip() : "";
        if (!EXPECTED_ENTRYPOINTS.contains(entrypoint) || !EXPECTED_RUNTIME_USER.equals(user)) {
            throw new IllegalArgumentException(
                    "Docker image runtime contract failed: image must retain ENTRYPOINT rd-claude-entrypoint "
                            + "and USER rdbot"
            );
        }
        String candidateFingerprint = runtimeExecutableFingerprint(image);
        boolean retainsTrustedExecutables = trustedBaseImages.stream()
                .map(this::runtimeExecutableFingerprint)
                .anyMatch(candidateFingerprint::equals);
        if (!retainsTrustedExecutables) {
            throw new IllegalArgumentException(
                    "Docker image runtime contract failed: image changed trusted Claude executable files"
            );
        }
    }

    /**
     * The profile grammar permits only package installation, but package build/install hooks can still be
     * surprisingly powerful. Compare the executable bytes against a configured base image before credentials
     * are ever injected into the resulting profile.
     */
    private String runtimeExecutableFingerprint(String image) {
        ProcessContainerRunner.CommandResult fingerprint = runOrThrow(List.of(
                "docker", "run", "--rm", "--entrypoint", "/bin/sh", image, "-lc",
                RUNTIME_EXECUTABLE_FINGERPRINT_COMMAND
        ), SMOKE_TIMEOUT_MILLIS, "Docker image trusted executable fingerprint");
        return (fingerprint.stdout() == null ? "" : fingerprint.stdout())
                .replace("\r", "")
                .strip();
    }

    private ProcessContainerRunner.CommandResult runOrThrow(
            List<String> argv,
            long timeoutMillis,
            String operation
    ) {
        try {
            ProcessContainerRunner.CommandResult result = commandLauncher.launch(argv, Map.of(), timeoutMillis);
            if (result == null) {
                throw new IllegalStateException(operation + " launcher returned no result");
            }
            if (result.timedOut() || result.exitCode() != 0) {
                throw new IllegalArgumentException(
                        operation + " failed" + (result.timedOut() ? " (timed out)" : "") + ": "
                                + diagnostic(result)
                );
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " could not start", exception);
        }
    }

    private static String imageName(String projectId, String role) {
        String safeProjectId = safePart(projectId, "projectId");
        String safeRole = safePart(role, "role").toLowerCase(java.util.Locale.ROOT);
        return "rd-bot/project-runtime-" + safeProjectId + "-" + safeRole + "-"
                + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String safePart(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(fieldName + " contains unsafe Docker tag characters");
        }
        return normalized;
    }

    private static String validationSummary(ProcessContainerRunner.CommandResult result) {
        String output = diagnostic(result);
        String prefix = ProjectRuntimeProfileService.RUNTIME_PROFILE_CONTRACT_MARKER
                + "; Claude Code runtime contract verified";
        return output.isBlank() ? prefix : prefix + ": " + output;
    }

    private static Set<String> trustedBaseImages(DockerExecutorProperties properties) {
        DockerExecutorProperties safe = java.util.Objects.requireNonNull(properties, "properties must not be null");
        Set<String> images = new LinkedHashSet<>();
        addTrustedBaseImage(images, safe.getImage());
        addTrustedBaseImage(images, safe.getQaImage());
        return Set.copyOf(images);
    }

    private static void addTrustedBaseImage(Set<String> images, String image) {
        String normalized = image == null ? "" : image.strip();
        if (!normalized.isBlank()) {
            images.add(normalized);
        }
    }

    private static String diagnostic(ProcessContainerRunner.CommandResult result) {
        String combined = ((result.stdout() == null ? "" : result.stdout()) + "\n"
                + (result.stderr() == null ? "" : result.stderr()))
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .strip();
        return combined.length() <= 2_000 ? combined : combined.substring(0, 2_000) + "...";
    }

    private static ProcessContainerRunner.CommandResult launchProcess(
            List<String> argv,
            Map<String, String> environment,
            long timeoutMillis
    ) throws IOException, InterruptedException {
        Instant started = Instant.now();
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.environment().putAll(environment == null ? Map.of() : environment);
        Process process = processBuilder.start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        boolean timedOut = timeoutMillis > 0 && !process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        int exitCode;
        if (timedOut) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            exitCode = 124;
        } else {
            exitCode = process.exitValue();
        }
        return new ProcessContainerRunner.CommandResult(
                exitCode,
                Duration.between(started, Instant.now()).toMillis(),
                await(stdout),
                await(stderr),
                timedOut
        );
    }

    private static CompletableFuture<String> readAsync(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream source = input) {
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

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // A short-lived build directory can be safely left for later operator inspection.
                }
            });
        } catch (IOException ignored) {
            // Same best-effort cleanup policy as above.
        }
    }

    /** Result returned only after build and runtime compatibility checks both succeed. */
    public record VerifiedImage(String image, String validationSummary) {
        public VerifiedImage {
            image = image == null ? "" : image.strip();
            validationSummary = validationSummary == null ? "" : validationSummary.strip();
        }
    }
}
