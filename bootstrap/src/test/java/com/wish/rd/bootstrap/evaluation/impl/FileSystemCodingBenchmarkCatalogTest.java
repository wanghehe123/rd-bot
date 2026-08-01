package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileSystemCodingBenchmarkCatalogTest {
    private static final List<String> REFERENCED_MANIFESTS = List.of(
            "dataset-manifest.json",
            "environment-manifest.json",
            "knowledge-manifest.json",
            "analysis-plan.json",
            "readiness-report.json",
            "runtime-manifest.json"
    );

    @TempDir
    Path tempDir;

    @Test
    void shouldOnlyExposeSnapshotWhenAllRequiredManifestsShareADigest() throws Exception {
        writeSnapshot("ready", "registry.example/benchmark-agent@sha256:" + "a".repeat(64));
        FileSystemCodingBenchmarkCatalog catalog = catalog();

        List<CodingBenchmarkSnapshot> snapshots = catalog.readySnapshots();

        assertEquals(List.of("ready"), snapshots.stream().map(CodingBenchmarkSnapshot::snapshotId).toList());
        assertEquals("ready", snapshots.getFirst().displayLabel());
        assertEquals(20, snapshots.getFirst().caseCount());
    }

    @Test
    void shouldRejectSnapshotWithAFloatingImageTag() throws Exception {
        writeSnapshot("floating", "registry.example/benchmark-agent:latest");
        FileSystemCodingBenchmarkCatalog catalog = catalog();

        assertEquals(List.of(), catalog.readySnapshots());
    }

    @Test
    void shouldRejectSnapshotWhenAReferencedManifestHashChangesAfterReadiness() throws Exception {
        writeSnapshot("tampered", "registry.example/benchmark-agent@sha256:" + "a".repeat(64));
        Files.writeString(tempDir.resolve("snapshots/tampered/dataset-manifest.json"), "{}", StandardCharsets.UTF_8);

        assertEquals(List.of(), catalog().readySnapshots());
    }

    @Test
    void shouldRejectSnapshotWithASymlinkedRequiredManifest() throws Exception {
        writeSnapshot("symlinked", "registry.example/benchmark-agent@sha256:" + "a".repeat(64));
        Path snapshotRoot = tempDir.resolve("snapshots/symlinked");
        Path dataset = snapshotRoot.resolve("dataset-manifest.json");
        Path movedDataset = snapshotRoot.resolve("dataset-source.json");
        Files.move(dataset, movedDataset);
        Files.createSymbolicLink(dataset, movedDataset.getFileName());

        assertEquals(List.of(), catalog().readySnapshots());
    }

    private FileSystemCodingBenchmarkCatalog catalog() {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setRepositoryRoot(tempDir);
        properties.setCodingBenchmarkRoot(tempDir.resolve("snapshots"));
        return new FileSystemCodingBenchmarkCatalog(properties, new ObjectMapper());
    }

    private void writeSnapshot(String snapshotId, String imageReference) throws Exception {
        Path snapshotRoot = tempDir.resolve("snapshots").resolve(snapshotId);
        Files.createDirectories(snapshotRoot);
        String digest = "sha256:" + "b".repeat(64);
        ObjectMapper objectMapper = new ObjectMapper();
        for (String manifest : REFERENCED_MANIFESTS) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotId", snapshotId);
            payload.put("snapshotDigest", digest);
            if (manifest.equals("environment-manifest.json")) {
                payload.put("images", List.of(imageReference));
            }
            if (manifest.equals("readiness-report.json")) {
                payload.put("ready", true);
            }
            if (manifest.equals("dataset-manifest.json")) {
                payload.put("caseCount", 20);
            }
            Files.writeString(snapshotRoot.resolve(manifest), objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8);
        }
        Map<String, String> manifestHashes = new LinkedHashMap<>();
        for (String manifest : REFERENCED_MANIFESTS) {
            manifestHashes.put(manifest, sha256(snapshotRoot.resolve(manifest)));
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("snapshotId", snapshotId);
        provenance.put("snapshotDigest", digest);
        provenance.put("manifestSha256", manifestHashes);
        Files.writeString(snapshotRoot.resolve("benchmark-provenance.json"),
                objectMapper.writeValueAsString(provenance), StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return "sha256:" + java.util.HexFormat.of().formatHex(digest);
    }
}
