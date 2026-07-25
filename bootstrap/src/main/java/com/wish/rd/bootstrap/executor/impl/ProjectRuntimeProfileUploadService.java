package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfileCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Validates, builds, stores and activates standalone per-role Dockerfile uploads. */
@Component
@ConditionalOnBean(DockerRuntimeProfileImageBuilder.class)
public final class ProjectRuntimeProfileUploadService {

    public static final String DOCKERFILE_BUCKET = "rd-project-runtime-dockerfiles";
    private static final int MAX_DOCKERFILE_BYTES = 256 * 1024;
    private static final java.util.regex.Pattern APT_UPDATE = java.util.regex.Pattern.compile("apt-get update");
    private static final java.util.regex.Pattern APT_INSTALL = java.util.regex.Pattern.compile(
            "apt-get install -y(?: --no-install-recommends)?(?: [A-Za-z0-9][A-Za-z0-9.+:=-]*)+"
    );
    private static final java.util.regex.Pattern APT_LIST_CLEANUP = java.util.regex.Pattern.compile(
            "rm -rf /var/lib/apt/lists/\\*"
    );

    private final ProjectRuntimeProfileService profileService;
    private final ObjectStorageService objectStorageService;
    private final DockerRuntimeProfileImageBuilder imageBuilder;

    public ProjectRuntimeProfileUploadService(
            ProjectRuntimeProfileService profileService,
            ObjectStorageService objectStorageService,
            DockerRuntimeProfileImageBuilder imageBuilder
    ) {
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.objectStorageService = Objects.requireNonNull(objectStorageService, "objectStorageService must not be null");
        this.imageBuilder = Objects.requireNonNull(imageBuilder, "imageBuilder must not be null");
    }

    /**
     * Activates a profile only after the uploaded Dockerfile has completed both Docker build and Claude smoke checks.
     */
    public ProjectRuntimeProfile upload(
            String projectId,
            String role,
            String agentType,
            String originalFilename,
            byte[] dockerfile
    ) {
        String safeAgentType = requireClaudeCodeAgentType(agentType);
        byte[] bytes = requireDockerfile(dockerfile);
        String safeFilename = requireDockerfileName(originalFilename);
        DockerRuntimeProfileImageBuilder.VerifiedImage verified = imageBuilder.buildAndVerify(projectId, role, bytes);
        StoredIngestionFile stored = objectStorageService.upload(
                DOCKERFILE_BUCKET,
                new ByteArrayInputStream(bytes),
                bytes.length,
                safeFilename,
                "text/plain"
        );
        if (stored == null || stored.size() != bytes.length || stored.url() == null || !stored.url().startsWith("s3://")) {
            throw new IllegalStateException("Dockerfile object storage upload did not return a private complete object");
        }
        try {
            return profileService.save(new ProjectRuntimeProfileCommand(
                    projectId,
                    role,
                    safeAgentType,
                    verified.image(),
                    stored.url(),
                    sha256(bytes),
                    safeFilename,
                    verified.validationSummary()
            ));
        } catch (RuntimeException exception) {
            try {
                objectStorageService.delete(stored.url());
            } catch (RuntimeException ignored) {
                // Keep the original persistence error as the actionable failure.
            }
            throw exception;
        }
    }

    private static String requireClaudeCodeAgentType(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!ProjectRuntimeProfileService.SUPPORTED_AGENT_TYPE.equals(normalized)) {
            throw new IllegalArgumentException("agentType must be CLAUDE_CODE");
        }
        return normalized;
    }

    private byte[] requireDockerfile(byte[] value) {
        byte[] bytes = value == null ? new byte[0] : value.clone();
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Dockerfile must not be empty");
        }
        if (bytes.length > MAX_DOCKERFILE_BYTES) {
            throw new IllegalArgumentException("Dockerfile exceeds 256 KiB limit");
        }
        String body = decodeUtf8(bytes);
        if (body.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Dockerfile must not contain NUL bytes");
        }
        validateRuntimeProfileDockerfile(body, imageBuilder.trustedBaseImages());
        return bytes;
    }

    /**
     * Uploaded profiles are intentionally a declarative environment extension, not arbitrary executable images.
     * Provider credentials are later injected into the selected image, so the Dockerfile must retain the trusted
     * Claude runtime and may only install packages from the image's configured apt sources using a small
     * shell-free grammar. Python package installation is deliberately excluded because build hooks execute
     * arbitrary package code before the later provider credentials are injected.
     */
    private static void validateRuntimeProfileDockerfile(String body, java.util.Set<String> trustedBaseImages) {
        List<DockerInstruction> instructions = parseInstructions(body);
        if (instructions.isEmpty()) {
            throw new IllegalArgumentException("Dockerfile must contain a FROM instruction");
        }
        DockerInstruction base = instructions.getFirst();
        if (!"FROM".equals(base.operation())) {
            throw new IllegalArgumentException("Dockerfile must start with a FROM instruction");
        }
        if (!trustedBaseImages.contains(base.arguments())) {
            throw new IllegalArgumentException(
                    "Dockerfile FROM must use one of the configured RD-Bot Claude runtime base images"
            );
        }
        boolean runningAsRoot = false;
        for (int index = 1; index < instructions.size(); index++) {
            DockerInstruction instruction = instructions.get(index);
            if ("FROM".equals(instruction.operation())) {
                throw new IllegalArgumentException("Dockerfile multi-stage builds are not supported");
            }
            if ("COPY".equals(instruction.operation()) || "ADD".equals(instruction.operation())) {
                throw new IllegalArgumentException(
                        "Dockerfile COPY or ADD is not supported because the upload has no project build context"
                );
            }
            if ("ENTRYPOINT".equals(instruction.operation()) || "CMD".equals(instruction.operation())) {
                throw new IllegalArgumentException(
                        "Dockerfile ENTRYPOINT or CMD is not supported because it must retain the RD-Bot Claude runtime contract"
                );
            }
            if ("USER".equals(instruction.operation())) {
                if ("root".equals(instruction.arguments()) && !runningAsRoot) {
                    runningAsRoot = true;
                    continue;
                }
                if ("rdbot".equals(instruction.arguments()) && runningAsRoot) {
                    runningAsRoot = false;
                    continue;
                }
                throw new IllegalArgumentException(
                        "Dockerfile USER may only temporarily switch from rdbot to root and then back to rdbot"
                );
            }
            if (!"RUN".equals(instruction.operation())) {
                throw new IllegalArgumentException(
                        "Dockerfile instruction " + instruction.operation()
                                + " is not supported; profiles may only use FROM, a temporary USER root/rdbot switch, "
                                + "and safe apt dependency installation"
                );
            }
            if (!runningAsRoot) {
                throw new IllegalArgumentException(
                        "Dockerfile RUN is only allowed between USER root and USER rdbot"
                );
            }
            requireSafeDependencyRun(instruction.arguments());
        }
        if (runningAsRoot) {
            throw new IllegalArgumentException("Dockerfile must restore USER rdbot before it ends");
        }
    }

    private static List<DockerInstruction> parseInstructions(String body) {
        List<DockerInstruction> instructions = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (String rawLine : (body == null ? "" : body).replace("\r", "").split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isBlank() || (joined.isEmpty() && line.startsWith("#"))) {
                continue;
            }
            boolean continuation = line.endsWith("\\");
            if (continuation) {
                line = line.substring(0, line.length() - 1).strip();
            }
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(line);
            if (continuation) {
                continue;
            }
            String statement = joined.toString().strip();
            joined.setLength(0);
            int separator = firstWhitespace(statement);
            String operation = (separator < 0 ? statement : statement.substring(0, separator))
                    .toUpperCase(java.util.Locale.ROOT);
            String arguments = separator < 0 ? "" : statement.substring(separator).strip();
            if (operation.isBlank() || arguments.isBlank()) {
                throw new IllegalArgumentException("Dockerfile instruction must include an operation and arguments");
            }
            instructions.add(new DockerInstruction(operation, arguments));
        }
        if (!joined.isEmpty()) {
            throw new IllegalArgumentException("Dockerfile line continuation must end with an instruction");
        }
        return List.copyOf(instructions);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static void requireSafeDependencyRun(String command) {
        String normalized = command == null ? "" : command.strip();
        if (normalized.startsWith("[") || normalized.contains(";") || normalized.contains("|")
                || normalized.contains("`") || normalized.contains("$")) {
            throw unsupportedRun(command);
        }
        String[] segments = normalized.split("&&", -1);
        if (segments.length == 0) {
            throw unsupportedRun(command);
        }
        for (String rawSegment : segments) {
            String segment = rawSegment.strip();
            if (!APT_UPDATE.matcher(segment).matches()
                    && !APT_INSTALL.matcher(segment).matches()
                    && !APT_LIST_CLEANUP.matcher(segment).matches()) {
                throw unsupportedRun(command);
            }
        }
    }

    private static IllegalArgumentException unsupportedRun(String command) {
        return new IllegalArgumentException(
                "Dockerfile RUN must only use plain apt-get update/install or apt list cleanup; "
                        + "arbitrary shell commands and pip build hooks are not allowed"
        );
    }

    private record DockerInstruction(String operation, String arguments) {
    }

    private static String requireDockerfileName(String value) {
        String filename = value == null ? "" : value.replace('\\', '/').strip();
        int slash = filename.lastIndexOf('/');
        filename = slash >= 0 ? filename.substring(slash + 1) : filename;
        if (filename.isBlank() || filename.length() > 160
                || !filename.matches("(?i)(dockerfile(?:[._-][a-z0-9._-]+)?|[a-z0-9._-]+\\.dockerfile)")) {
            throw new IllegalArgumentException("uploaded file must be named Dockerfile, Dockerfile.*, or *.dockerfile");
        }
        return filename;
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Dockerfile must be valid UTF-8", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
