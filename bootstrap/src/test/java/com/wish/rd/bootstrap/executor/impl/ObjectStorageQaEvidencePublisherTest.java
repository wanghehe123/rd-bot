package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStorageQaEvidencePublisherTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldMoveQaEvidenceToPrivateObjectStorageAndPreserveIntegrityMetadata() throws Exception {
        Path screenshot = tempDirectory.resolve("current.png");
        Files.write(screenshot, "real-png-evidence".getBytes(StandardCharsets.UTF_8));
        RepairArtifact qaArtifact = new RepairArtifact(
                RepairArtifactType.QA_SCREENSHOT,
                "qa-evidence/screenshots/current.png",
                screenshot.toUri().toString(),
                "current requirement screenshot",
                Map.of(
                        "bytes", Long.toString(Files.size(screenshot)),
                        "sha256", "17936c80f4b354ff178943a99fb320891d2e4acea9028133becccb990444f163",
                        "contentType", "image/png"
                )
        );
        RepairArtifact nonQaArtifact = new RepairArtifact(
                RepairArtifactType.PATCH_DIFF,
                "patch.diff",
                tempDirectory.resolve("patch.diff").toUri().toString(),
                "patch",
                Map.of()
        );
        RepairExecutionResult result = new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "QA passed",
                "",
                List.of(qaArtifact, nonQaArtifact),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();

        RepairExecutionResult published = new ObjectStorageQaEvidencePublisher(storage).publish(result);

        RepairArtifact publishedScreenshot = published.artifacts().getFirst();
        assertTrue(publishedScreenshot.uri().startsWith("s3://rd-qa-evidence/"));
        assertEquals(qaArtifact.metadataJson(), publishedScreenshot.metadataJson());
        assertEquals("real-png-evidence", new String(
                storage.openStream(publishedScreenshot.uri()).readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(nonQaArtifact, published.artifacts().getLast());
    }
}
