package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.pi.impl.FileSystemPiResourceManifestMaterializer;
import com.wish.rd.exec.repair.pi.model.VerifiedPiResource;
import com.wish.rd.exec.repair.pi.model.VerifiedPiResourceSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemPiResourceManifestMaterializerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void materializesOnlyTheFrozenVerifiedResourceSet() throws Exception {
        Path approvedRoot = temporaryDirectory.resolve("approved-cache");
        Path bundle = approvedRoot.resolve("bundle-a");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("index.mjs"), "export default {};\n");
        String digest = FileSystemPiResourceManifestMaterializer.sha256(bundle);
        PiVerifiedResourceSetStore store = (setId, version) -> Optional.of(
                new VerifiedPiResourceSet(
                        setId,
                        version,
                        List.of(new VerifiedPiResource("extension-a", "1.0.0", bundle, digest))
                )
        );
        FileSystemPiResourceManifestMaterializer materializer = new FileSystemPiResourceManifestMaterializer(
                approvedRoot,
                store
        );
        Path input = temporaryDirectory.resolve("input");

        materializer.materialize(snapshot("set-a", 7), input);

        JsonNode manifest = OBJECT_MAPPER.readTree(
                Files.readString(input.resolve("resource-manifest.json"))
        );
        assertEquals("set-a", manifest.path("extensionSetId").asText());
        assertEquals(7, manifest.path("extensionSetVersion").asInt());
        assertEquals("VERIFIED", manifest.path("verificationStatus").asText());
        assertEquals(1, manifest.path("resources").size());
        assertEquals(
                "export default {};\n",
                Files.readString(input.resolve("extensions/extension-a-1.0.0/index.mjs"))
        );
        assertEquals(digest, manifest.path("resources").get(0).path("sha256").asText());
    }

    @Test
    void failsClosedWhenTheApprovedBundleDigestDoesNotMatch() throws Exception {
        Path approvedRoot = temporaryDirectory.resolve("approved-cache");
        Path bundle = approvedRoot.resolve("bundle-a");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("index.mjs"), "tampered\n");
        PiVerifiedResourceSetStore store = (setId, version) -> Optional.of(
                new VerifiedPiResourceSet(
                        setId,
                        version,
                        List.of(new VerifiedPiResource("extension-a", "1.0.0", bundle, "0".repeat(64)))
                )
        );

        FileSystemPiResourceManifestMaterializer materializer = new FileSystemPiResourceManifestMaterializer(
                approvedRoot,
                store
        );

        assertThrows(Exception.class, () -> materializer.materialize(snapshot("set-a", 7), temporaryDirectory.resolve("input")));
        assertTrue(Files.notExists(temporaryDirectory.resolve("input/resource-manifest.json")));
    }

    private AgentExecutionProfileSnapshot snapshot(String extensionSetId, long extensionSetVersion) throws Exception {
        String json = OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
                "extensionSetId", extensionSetId,
                "extensionSetVersion", extensionSetVersion
        ));
        return new AgentExecutionProfileSnapshot(
                "snapshot-1",
                "stage-1",
                "task-1",
                "CODING_AGENT",
                1,
                com.wish.rd.rag.project.agent.model.AgentRuntimeType.PI,
                json,
                AgentExecutionProfileSnapshot.sha256(json),
                1L
        );
    }
}
