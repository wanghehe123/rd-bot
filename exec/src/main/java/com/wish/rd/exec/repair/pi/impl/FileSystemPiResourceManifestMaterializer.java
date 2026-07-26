package com.wish.rd.exec.repair.pi.impl;

import com.wish.rd.exec.repair.pi.PiResourceManifestMaterializerPort;
import com.wish.rd.exec.repair.pi.PiVerifiedResourceSetStore;
import com.wish.rd.exec.repair.pi.model.VerifiedPiResource;
import com.wish.rd.exec.repair.pi.model.VerifiedPiResourceSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * H1 materializer for a host-controlled verified bundle cache.
 *
 * <p>The cache is the output of a future platform publisher, not an API-supplied
 * path. A stage receives a copied resource snapshot under its own input
 * directory, so activating a new set never mutates a running container.</p>
 */
public final class FileSystemPiResourceManifestMaterializer implements PiResourceManifestMaterializerPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final String CONTAINER_EXTENSION_ROOT = "/work/input/extensions";

    private final Path approvedCacheRoot;
    private final PiVerifiedResourceSetStore resourceSetStore;

    public FileSystemPiResourceManifestMaterializer(
            Path approvedCacheRoot,
            PiVerifiedResourceSetStore resourceSetStore
    ) {
        this.approvedCacheRoot = requireDirectory(approvedCacheRoot, "approvedCacheRoot");
        this.resourceSetStore = Objects.requireNonNull(resourceSetStore, "resourceSetStore must not be null");
    }

    @Override
    public void materialize(AgentExecutionProfileSnapshot snapshot, Path inputDirectory) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Path inputRoot = requireDirectory(inputDirectory, "inputDirectory");
        JsonNode snapshotJson = OBJECT_MAPPER.readTree(snapshot.snapshotJson());
        String setId = text(snapshotJson.path("extensionSetId"));
        long setVersion = snapshotJson.path("extensionSetVersion").asLong(0L);
        if (setId.isBlank()) {
            writeManifest(inputRoot, "empty", 1L, List.of());
            return;
        }
        if (setVersion <= 0L) {
            throw new IOException("selected Pi extension set version must be positive");
        }
        VerifiedPiResourceSet resourceSet = resourceSetStore.find(setId, setVersion)
                .orElseThrow(() -> new IOException("verified Pi extension set is unavailable: " + setId + "@" + setVersion));
        if (!setId.equals(resourceSet.setId()) || setVersion != resourceSet.version()) {
            throw new IOException("verified Pi extension set identity does not match the frozen snapshot");
        }

        Path extensionRoot = inputRoot.resolve("extensions").normalize();
        Files.createDirectories(extensionRoot);
        List<Map<String, Object>> manifestResources = new ArrayList<>();
        for (VerifiedPiResource resource : resourceSet.resources()) {
            Path source = validateSource(resource);
            String destinationName = safeSegment(resource.resourceId(), "resourceId")
                    + "-" + safeSegment(resource.version(), "resource version");
            Path destination = extensionRoot.resolve(destinationName).normalize();
            if (!destination.startsWith(extensionRoot) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Pi extension destination is invalid or already exists: " + destinationName);
            }
            copyWithoutSymlinks(source, destination);
            String copiedDigest = sha256(destination);
            if (!copiedDigest.equals(resource.sha256())) {
                throw new IOException("Pi extension digest changed while materializing: " + resource.resourceId());
            }
            manifestResources.add(new LinkedHashMap<>(Map.of(
                    "kind", "extension",
                    "resourceId", resource.resourceId(),
                    "version", resource.version(),
                    "path", CONTAINER_EXTENSION_ROOT + "/" + destinationName,
                    "sha256", copiedDigest,
                    "status", "VERIFIED"
            )));
        }
        writeManifest(inputRoot, setId, setVersion, manifestResources);
    }

    /** Uses the same deterministic file/directory hashing rule as the Node bridge. */
    public static String sha256(Path path) throws IOException {
        Path normalized = path == null ? null : path.toAbsolutePath().normalize();
        if (normalized == null || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pi resource does not exist: " + path);
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Pi resource symlinks are not allowed: " + normalized);
        }
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return hashFile(normalized);
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pi resource must be a regular file or directory: " + normalized);
        }
        MessageDigest digest = sha256Digest();
        List<Path> files;
        try (var stream = Files.walk(normalized)) {
            files = stream.sorted(Comparator.comparing(pathItem -> normalized.relativize(pathItem).toString()))
                    .toList();
        }
        for (Path file : files) {
            if (file.equals(normalized)) continue;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Pi resource directory contains a non-regular entry: " + file);
            }
            String relative = normalized.relativize(file).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            updateDigest(digest, file);
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path validateSource(VerifiedPiResource resource) throws IOException {
        if (resource == null
                || resource.cachePath() == null
                || !SAFE_SEGMENT.matcher(resource.resourceId()).matches()
                || !SAFE_SEGMENT.matcher(resource.version()).matches()
                || !resource.sha256().matches("[0-9a-f]{64}")) {
            throw new IOException("verified Pi resource metadata is invalid");
        }
        Path source = resource.cachePath().toAbsolutePath().normalize();
        if (!source.startsWith(approvedCacheRoot)
                || source.equals(approvedCacheRoot)
                || Files.isSymbolicLink(source)
                || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verified Pi resource path is outside the approved cache");
        }
        if (!resource.sha256().equals(sha256(source))) {
            throw new IOException("verified Pi resource digest mismatch: " + resource.resourceId());
        }
        return source;
    }

    private static void copyWithoutSymlinks(Path source, Path destination) throws IOException {
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("Pi extension directory contains a symlink: " + directory);
                }
                Path target = destination.resolve(source.relativize(directory)).normalize();
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("Pi extension contains a non-regular file: " + file);
                }
                Path target = destination.resolve(source.relativize(file)).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("Pi extension copy escaped destination");
                }
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeManifest(
            Path inputRoot,
            String setId,
            long setVersion,
            List<Map<String, Object>> resources
    ) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("protocol", "rd-agent-resource-manifest/v1");
        manifest.put("extensionSetId", setId);
        manifest.put("extensionSetVersion", setVersion);
        manifest.put("verificationStatus", "VERIFIED");
        manifest.put("resources", resources);
        Path target = inputRoot.resolve("resource-manifest.json").normalize();
        if (!target.startsWith(inputRoot) || Files.isSymbolicLink(target)) {
            throw new IOException("Pi resource manifest path escapes input directory");
        }
        Path temporary = inputRoot.resolve("resource-manifest.json.tmp-" + System.nanoTime()).normalize();
        Files.writeString(temporary, OBJECT_MAPPER.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path requireDirectory(Path path, String fieldName) {
        Path normalized = Objects.requireNonNull(path, fieldName + " must not be null")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
        } catch (IOException exception) {
            throw new IllegalArgumentException(fieldName + " cannot be created", exception);
        }
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(fieldName + " must be a real directory");
        }
        return normalized;
    }

    private static String safeSegment(String value, String field) throws IOException {
        if (value == null || !SAFE_SEGMENT.matcher(value).matches()) {
            throw new IOException(field + " contains an unsafe path segment");
        }
        return value;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").strip();
    }

    private static String hashFile(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        updateDigest(digest, path);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
