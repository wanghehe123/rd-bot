package com.wish.rd.exec.repair.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materializes one immutable, already-verified H1 Pi resource set into an
 * attempt input directory. The runtime never accepts an arbitrary host path.
 */
@FunctionalInterface
public interface PiResourceManifestMaterializerPort {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    void materialize(AgentExecutionProfileSnapshot snapshot, Path inputDirectory) throws IOException;

    /** Writes the empty verified manifest used by profiles without extensions. */
    static PiResourceManifestMaterializerPort emptyOnly() {
        return (snapshot, inputDirectory) -> {
            try {
                JsonNode snapshotJson = OBJECT_MAPPER.readTree(snapshot == null ? "{}" : snapshot.snapshotJson());
                if (!snapshotJson.path("extensionSetId").asText("").isBlank()) {
                    throw new IOException("selected Pi extension set requires an approved materializer");
                }
            } catch (IOException exception) {
                throw exception;
            }
            Path manifest = inputDirectory.resolve("resource-manifest.json").normalize();
            if (!manifest.startsWith(inputDirectory.toAbsolutePath().normalize())) {
                throw new IOException("Pi resource manifest path escapes input directory");
            }
            String extensionSetId = "empty";
            String json = "{\n"
                    + "  \"protocol\": \"rd-agent-resource-manifest/v1\",\n"
                    + "  \"extensionSetId\": \"" + extensionSetId + "\",\n"
                    + "  \"extensionSetVersion\": 1,\n"
                    + "  \"verificationStatus\": \"VERIFIED\",\n"
                    + "  \"resources\": []\n"
                    + "}\n";
            Files.writeString(manifest, json, StandardCharsets.UTF_8);
        };
    }
}
