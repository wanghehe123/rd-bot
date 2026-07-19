package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/** Task-scoped private QA evidence listing and download API. */
@RestController
public final class QaEvidenceController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PRIVATE_EVIDENCE_PREFIX = "s3://rd-qa-evidence/";
    private static final Set<String> QA_EVIDENCE_TYPES = Set.of(
            "QA_COMMAND_LOG",
            "QA_SCREENSHOT",
            "QA_TRACE",
            "QA_CONSOLE_LOG",
            "QA_NETWORK_LOG",
            "QA_HTTP_TRANSCRIPT",
            "QA_VIDEO",
            "QA_EVIDENCE_MANIFEST"
    );
    private static final Set<String> INLINE_EVIDENCE_TYPES = Set.of(
            "QA_SCREENSHOT",
            "QA_VIDEO",
            "QA_COMMAND_LOG",
            "QA_CONSOLE_LOG",
            "QA_NETWORK_LOG",
            "QA_HTTP_TRANSCRIPT",
            "QA_EVIDENCE_MANIFEST"
    );

    private final RagStreamTaskRegistry registry;
    private final AgentStageArtifactStore artifactStore;
    private final ObjectStorageService objectStorageService;

    public QaEvidenceController(
            RagStreamTaskRegistry registry,
            AgentStageArtifactStore artifactStore,
            ObjectStorageService objectStorageService
    ) {
        this.registry = registry;
        this.artifactStore = artifactStore;
        this.objectStorageService = objectStorageService;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/qa-evidence")
    public List<QaEvidenceView> list(@PathVariable("taskId") String taskId) {
        requireTask(taskId);
        return artifactStore.listByTask(taskId).stream()
                .filter(QaEvidenceController::isPrivateQaEvidence)
                .sorted(Comparator
                        .comparingLong(AgentStageArtifact::createdAtEpochMillis)
                        .reversed()
                        .thenComparing(AgentStageArtifact::artifactId))
                .map(this::toView)
                .toList();
    }

    @GetMapping("/admin/rd-tasks/{taskId}/qa-evidence/{artifactId}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable("taskId") String taskId,
            @PathVariable("artifactId") String artifactId
    ) {
        requireTask(taskId);
        AgentStageArtifact artifact = artifactStore.listByTask(taskId).stream()
                .filter(candidate -> candidate.artifactId().equals(artifactId))
                .filter(QaEvidenceController::isPrivateQaEvidence)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QA evidence not found"));
        EvidenceMetadata metadata = metadata(artifact);
        InputStream stream;
        try {
            stream = objectStorageService.openStream(artifact.artifactUri());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QA evidence object is unavailable", exception);
        }
        ContentDisposition disposition = metadata.previewable()
                ? ContentDisposition.inline().filename(downloadFilename(metadata.name()), StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(downloadFilename(metadata.name()), StandardCharsets.UTF_8).build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(mediaType(metadata.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-SHA256", metadata.sha256());
        if (metadata.sizeBytes() > 0L) {
            response.contentLength(metadata.sizeBytes());
        }
        return response.body(new InputStreamResource(stream));
    }

    private QaEvidenceView toView(AgentStageArtifact artifact) {
        EvidenceMetadata metadata = metadata(artifact);
        return new QaEvidenceView(
                artifact.artifactId(),
                artifact.stageRunId(),
                artifact.artifactType(),
                metadata.name(),
                artifact.summary(),
                metadata.contentType(),
                metadata.sizeBytes(),
                metadata.sha256(),
                artifact.createdAtEpochMillis(),
                metadata.previewable(),
                "/admin/rd-tasks/%s/qa-evidence/%s/content".formatted(
                        artifact.taskId(), artifact.artifactId())
        );
    }

    private static EvidenceMetadata metadata(AgentStageArtifact artifact) {
        JsonNode metadata;
        try {
            metadata = OBJECT_MAPPER.readTree(artifact.metadataJson());
        } catch (JsonProcessingException exception) {
            metadata = OBJECT_MAPPER.createObjectNode();
        }
        String name = firstNonBlank(metadata.path("artifactName").asText(""), artifact.summary());
        String contentType = firstNonBlank(
                metadata.path("contentType").asText(""), "application/octet-stream");
        long sizeBytes = nonNegativeLong(metadata.path("bytes").asText(""));
        String sha256 = firstNonBlank(
                metadata.path("sha256").asText(""), artifact.contentHash().replace("sha256:", ""));
        boolean previewable = INLINE_EVIDENCE_TYPES.contains(artifact.artifactType());
        return new EvidenceMetadata(name, contentType, sizeBytes, sha256, previewable);
    }

    private void requireTask(String taskId) {
        try {
            registry.getTask(taskId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private static boolean isPrivateQaEvidence(AgentStageArtifact artifact) {
        return artifact != null
                && QA_EVIDENCE_TYPES.contains(artifact.artifactType())
                && artifact.artifactUri().startsWith(PRIVATE_EVIDENCE_PREFIX);
    }

    private static MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String downloadFilename(String name) {
        String normalized = name == null ? "qa-evidence" : name.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String filename = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        return filename.isBlank() ? "qa-evidence" : filename;
    }

    private static long nonNegativeLong(String value) {
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.strip();
    }

    public record QaEvidenceView(
            String artifactId,
            String stageRunId,
            String type,
            String name,
            String summary,
            String contentType,
            long sizeBytes,
            String sha256,
            long createdAtEpochMillis,
            boolean previewable,
            String contentUrl
    ) {
    }

    private record EvidenceMetadata(
            String name,
            String contentType,
            long sizeBytes,
            String sha256,
            boolean previewable
    ) {
    }
}
