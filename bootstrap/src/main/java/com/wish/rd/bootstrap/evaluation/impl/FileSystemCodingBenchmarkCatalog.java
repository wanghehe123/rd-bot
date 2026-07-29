package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.CodingBenchmarkCatalogPort;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSnapshot;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only catalog that exposes only complete, digest-verified benchmark snapshots under one root.
 */
@Component
public final class FileSystemCodingBenchmarkCatalog implements CodingBenchmarkCatalogPort {
    private static final List<String> REFERENCED_MANIFESTS = List.of(
            "dataset-manifest.json",
            "environment-manifest.json",
            "knowledge-manifest.json",
            "analysis-plan.json",
            "readiness-report.json"
    );
    private static final String PROVENANCE_MANIFEST = "benchmark-provenance.json";
    private static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IMAGE_DIGEST_PATTERN = Pattern.compile(".+@sha256:[a-f0-9]{64}");

    private final EvaluationProperties properties;
    private final ObjectMapper objectMapper;

    public FileSystemCodingBenchmarkCatalog(EvaluationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Returns no entry for malformed, incomplete, symlinked, or non-immutable snapshots. */
    @Override
    public List<CodingBenchmarkSnapshot> readySnapshots() {
        Path configuredRoot = properties.resolvedCodingBenchmarkRoot();
        try {
            if (!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(configuredRoot)) {
                return List.of();
            }
            Path root = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            try (var candidates = Files.list(root)) {
                return candidates
                        .filter(path -> isDirectSnapshotDirectory(path, root))
                        .sorted()
                        .map(path -> loadSnapshot(root, path))
                        .flatMap(Optional::stream)
                        .toList();
            }
        } catch (IOException exception) {
            return List.of();
        }
    }

    private boolean isDirectSnapshotDirectory(Path candidate, Path root) {
        try {
            String name = candidate.getFileName().toString();
            return SNAPSHOT_ID_PATTERN.matcher(name).matches()
                    && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate)
                    && candidate.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root);
        } catch (IOException exception) {
            return false;
        }
    }

    private Optional<CodingBenchmarkSnapshot> loadSnapshot(Path root, Path candidate) {
        try {
            Path snapshotRoot = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!snapshotRoot.startsWith(root) || Files.isSymbolicLink(snapshotRoot)) {
                return Optional.empty();
            }
            String snapshotId = snapshotRoot.getFileName().toString();
            Map<String, JsonNode> manifests = new LinkedHashMap<>();
            for (String manifestName : REFERENCED_MANIFESTS) {
                manifests.put(manifestName, readManifest(snapshotRoot, manifestName));
            }
            JsonNode provenance = readManifest(snapshotRoot, PROVENANCE_MANIFEST);
            String snapshotDigest = sharedSnapshotDigest(snapshotId, manifests, provenance);
            verifyManifestHashes(snapshotRoot, provenance);
            verifyReadiness(manifests.get("readiness-report.json"));
            verifyImmutableImages(manifests.get("environment-manifest.json"));
            int caseCount = manifests.get("dataset-manifest.json").path("caseCount").asInt(-1);
            if (caseCount != 20) {
                return Optional.empty();
            }
            return Optional.of(new CodingBenchmarkSnapshot(snapshotId, snapshotId, snapshotDigest, caseCount));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private JsonNode readManifest(Path snapshotRoot, String manifestName) throws IOException {
        Path manifest = snapshotRoot.resolve(manifestName).normalize();
        if (!manifest.startsWith(snapshotRoot)
                || Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("required manifest is unavailable");
        }
        JsonNode node = objectMapper.readTree(Files.readAllBytes(manifest));
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("manifest must be a JSON object");
        }
        return node;
    }

    private String sharedSnapshotDigest(
            String snapshotId,
            Map<String, JsonNode> manifests,
            JsonNode provenance
    ) {
        String digest = requireDigest(provenance.path("snapshotDigest").asText(""));
        if (!snapshotId.equals(provenance.path("snapshotId").asText(""))) {
            throw new IllegalArgumentException("provenance snapshot id does not match directory");
        }
        for (JsonNode manifest : manifests.values()) {
            if (!snapshotId.equals(manifest.path("snapshotId").asText(""))
                    || !digest.equals(requireDigest(manifest.path("snapshotDigest").asText("")))) {
                throw new IllegalArgumentException("required manifests do not share a snapshot digest");
            }
        }
        return digest;
    }

    private void verifyManifestHashes(Path snapshotRoot, JsonNode provenance) throws IOException {
        JsonNode hashes = provenance.path("manifestSha256");
        if (!hashes.isObject()) {
            throw new IllegalArgumentException("provenance must reference required manifest hashes");
        }
        for (String manifestName : REFERENCED_MANIFESTS) {
            String expected = requireDigest(hashes.path(manifestName).asText(""));
            String actual = sha256(snapshotRoot.resolve(manifestName));
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException("manifest hash mismatch");
            }
        }
    }

    private void verifyReadiness(JsonNode readiness) {
        if (!readiness.path("ready").asBoolean(false)) {
            throw new IllegalArgumentException("benchmark readiness is not approved");
        }
    }

    private void verifyImmutableImages(JsonNode environment) {
        JsonNode images = environment.path("images");
        if (!images.isArray() || images.isEmpty()) {
            throw new IllegalArgumentException("environment must declare immutable images");
        }
        for (JsonNode image : images) {
            String value = image.asText("").toLowerCase(java.util.Locale.ROOT);
            if (!IMAGE_DIGEST_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException("environment contains a floating image reference");
            }
        }
    }

    private static String requireDigest(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!DIGEST_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("expected immutable sha256 digest");
        }
        return normalized;
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
