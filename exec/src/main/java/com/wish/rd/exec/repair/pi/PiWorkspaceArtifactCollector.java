package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.security.SecretRedactor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Collects public Pi workspace output artifacts for reconciliation after host interruption.
 */
public final class PiWorkspaceArtifactCollector {

    private static final int SAFE_EVENT_PREVIEW_CHARS = 65_536;
    private static final long MAX_TEXT_PREVIEW_BYTES = 256_000L;

    private PiWorkspaceArtifactCollector() {
    }

    public static List<RepairArtifact> collect(Path outputDirectory) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(outputDirectory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !outputDirectory.relativize(path).toString().replace('\\', '/').startsWith("private/"))
                    .sorted(Comparator.comparing(path -> artifactName(outputDirectory, path)))
                    .map(path -> toArtifact(outputDirectory, path))
                    .toList();
        }
    }

    private static RepairArtifact toArtifact(Path outputDirectory, Path path) {
        String name = artifactName(outputDirectory, path);
        return new RepairArtifact(
                artifactType(name),
                name,
                path.toUri().toString(),
                "Pi execution artifact: " + name,
                artifactMetadata(path)
        );
    }

    private static String artifactName(Path outputDirectory, Path path) {
        return outputDirectory.relativize(path).toString().replace('\\', '/');
    }

    private static RepairArtifactType artifactType(String name) {
        String normalized = name == null ? "" : name.replace('\\', '/');
        return switch (normalized) {
            case "result.json" -> RepairArtifactType.RESULT_JSON;
            case "patch.diff" -> RepairArtifactType.PATCH_DIFF;
            case "test.log" -> RepairArtifactType.TEST_LOG;
            case "agent-events.jsonl" -> RepairArtifactType.AGENT_EVENTS;
            case "agent-state-events.jsonl" -> RepairArtifactType.AGENT_STATE_EVENTS;
            case "agent-state-latest.json" -> RepairArtifactType.AGENT_STATE_SNAPSHOT;
            case "agent-effective-context-latest.json" -> RepairArtifactType.AGENT_EFFECTIVE_CONTEXT;
            case "runtime-context-manifest.json" -> RepairArtifactType.RUNTIME_CONTEXT_MANIFEST;
            case "runtime-meta.json" -> RepairArtifactType.AGENT_RUNTIME_META;
            case "pi-protocol-failure-receipt.json" -> RepairArtifactType.PI_PROTOCOL_FAILURE_RECEIPT;
            case "docker-meta.json" -> RepairArtifactType.DOCKER_METADATA;
            case "handoff/next.md" -> RepairArtifactType.HANDOFF_MARKDOWN;
            case "qa-evidence/manifest.json" -> RepairArtifactType.QA_EVIDENCE_MANIFEST;
            default -> qaArtifactType(normalized);
        };
    }

    private static RepairArtifactType qaArtifactType(String name) {
        if (!name.startsWith("qa-evidence/")) {
            return RepairArtifactType.OTHER;
        }
        if ((name.startsWith("qa-evidence/commands/") || name.startsWith("qa-evidence/browser/"))
                && name.endsWith(".log")) {
            return RepairArtifactType.QA_COMMAND_LOG;
        }
        if (name.startsWith("qa-evidence/screenshots/")
                && (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
            return RepairArtifactType.QA_SCREENSHOT;
        }
        if (name.startsWith("qa-evidence/traces/") && name.endsWith(".zip")) {
            return RepairArtifactType.QA_TRACE;
        }
        if (name.startsWith("qa-evidence/console/")) {
            return RepairArtifactType.QA_CONSOLE_LOG;
        }
        if (name.startsWith("qa-evidence/network/")) {
            return RepairArtifactType.QA_NETWORK_LOG;
        }
        if (name.startsWith("qa-evidence/http/")) {
            return RepairArtifactType.QA_HTTP_TRANSCRIPT;
        }
        if (name.startsWith("qa-evidence/video/")) {
            return RepairArtifactType.QA_VIDEO;
        }
        return RepairArtifactType.OTHER;
    }

    private static Map<String, String> artifactMetadata(Path path) {
        Map<String, String> metadata = new LinkedHashMap<>();
        try {
            long size = Files.size(path);
            metadata.put("bytes", String.valueOf(size));
            metadata.put("sha256", sha256(path));
            metadata.put("contentType", contentType(path));
            if ("agent-events.jsonl".equals(path.getFileName().toString())) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                boolean truncated = content.length() > SAFE_EVENT_PREVIEW_CHARS;
                metadata.put(
                        "contentPreview",
                        truncated
                                ? content.substring(0, SAFE_EVENT_PREVIEW_CHARS) + "\n[truncated]"
                                : content
                );
                metadata.put("contentPreviewTruncated", String.valueOf(truncated));
            } else if (isTextArtifact(path) && size <= MAX_TEXT_PREVIEW_BYTES) {
                metadata.put("contentPreview", SecretRedactor.redactFreeform(
                        Files.readString(path, StandardCharsets.UTF_8)
                ));
            }
            return Map.copyOf(metadata);
        } catch (IOException exception) {
            metadata.put("contentPreview", "");
            return Map.copyOf(metadata);
        }
    }

    private static boolean isTextArtifact(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".json")
                || name.endsWith(".jsonl")
                || name.endsWith(".md")
                || name.endsWith(".log")
                || name.endsWith(".txt")
                || name.endsWith(".diff");
    }

    private static String contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".jsonl")) {
            return "application/x-ndjson";
        }
        if (name.endsWith(".md")) {
            return "text/markdown";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".zip")) {
            return "application/zip";
        }
        return "text/plain";
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return "sha256:" + HexFormat(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String HexFormat(byte[] digest) {
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
