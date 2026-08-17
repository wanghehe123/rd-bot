package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepStatus;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Task-scoped host BUILD/STATIC verification listing and evidence download API.
 *
 * <p>JSON fields are frozen for the admin frontend. Content URLs only stream
 * bytes whose artifact row matches both path {@code taskId} and {@code runId}.
 */
@RestController
@ConditionalOnBean(HostVerificationStore.class)
public final class HostVerificationController {

    private final RagStreamTaskRegistry registry;
    private final HostVerificationStore store;
    private final ObjectStorageService objectStorageService;

    /**
     * Creates the read-only admin adapter.
     *
     * @param registry             task registry used to 404 unknown tasks
     * @param store                verification runs, steps, and artifacts
     * @param objectStorageService s3 object reader; {@code file:} URIs are opened locally
     */
    public HostVerificationController(
            RagStreamTaskRegistry registry,
            HostVerificationStore store,
            ObjectStorageService objectStorageService
    ) {
        this.registry = registry;
        this.store = store;
        this.objectStorageService = objectStorageService;
    }

    /**
     * Lists verification runs for a task, including steps and evidence metadata.
     *
     * @param taskId owning RD task id
     * @return envelope with {@code taskId} and {@code runs}; empty when none exist
     */
    @GetMapping("/admin/rd-tasks/{taskId}/host-verifications")
    public HostVerificationListView list(@PathVariable("taskId") String taskId) {
        requireTask(taskId);
        return new HostVerificationListView(
                taskId,
                store.listByTask(taskId).stream().map(run -> toRunView(taskId, run)).toList()
        );
    }

    /**
     * Returns one verification run owned by the task.
     *
     * @param taskId owning RD task id
     * @param runId  verification run id
     * @return run view with steps and artifacts
     */
    @GetMapping("/admin/rd-tasks/{taskId}/host-verifications/{runId}")
    public HostVerificationRunView detail(
            @PathVariable("taskId") String taskId,
            @PathVariable("runId") String runId
    ) {
        requireTask(taskId);
        return toRunView(taskId, requireRun(taskId, runId));
    }

    /**
     * Streams one evidence object after dual ownership checks.
     *
     * @param taskId     owning RD task id
     * @param runId      verification run id
     * @param artifactId evidence id
     * @return object bytes; never another task's content
     */
    @GetMapping("/admin/rd-tasks/{taskId}/host-verifications/{runId}/evidence/{artifactId}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable("taskId") String taskId,
            @PathVariable("runId") String runId,
            @PathVariable("artifactId") String artifactId
    ) {
        requireTask(taskId);
        requireRun(taskId, runId);
        HostVerificationArtifact artifact = requireOwnedArtifact(taskId, runId, artifactId);
        InputStream stream;
        try {
            stream = openArtifact(artifact);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "host verification evidence object is unavailable", exception);
        }
        boolean previewable = previewable(artifact);
        ContentDisposition disposition = previewable
                ? ContentDisposition.inline().filename(downloadFilename(artifact), StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(downloadFilename(artifact), StandardCharsets.UTF_8).build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(mediaType(artifact.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-SHA256", artifact.sha256());
        if (artifact.sizeBytes() > 0L) {
            response.contentLength(artifact.sizeBytes());
        }
        return response.body(new InputStreamResource(stream));
    }

    private HostVerificationRunView toRunView(String taskId, HostVerificationRun run) {
        List<HostVerificationStepView> steps = store.listSteps(run.runId()).stream()
                .map(HostVerificationController::toStepView)
                .toList();
        List<HostVerificationArtifactView> artifacts = store.listArtifacts(run.runId()).stream()
                .filter(artifact -> taskId.equals(artifact.taskId()) && run.runId().equals(artifact.runId()))
                .map(artifact -> toArtifactView(taskId, run.runId(), artifact))
                .toList();
        return new HostVerificationRunView(
                run.runId(),
                run.codingStageRunId(),
                run.parentRunId(),
                run.attemptNo(),
                run.status(),
                run.docsOnly(),
                run.failureCategory(),
                run.errorMessage(),
                run.remediationCount(),
                run.createdAtEpochMillis(),
                run.startedAtEpochMillis(),
                run.finishedAtEpochMillis(),
                steps,
                artifacts
        );
    }

    private static HostVerificationStepView toStepView(HostVerificationStep step) {
        return new HostVerificationStepView(
                step.step(),
                step.status(),
                step.commands(),
                step.exitCode(),
                step.durationMillis(),
                step.logArtifactId(),
                step.errorMessage()
        );
    }

    private static HostVerificationArtifactView toArtifactView(
            String taskId,
            String runId,
            HostVerificationArtifact artifact
    ) {
        return new HostVerificationArtifactView(
                artifact.artifactId(),
                artifact.artifactType(),
                artifactName(artifact.relativePath()),
                artifact.relativePath(),
                artifact.contentType(),
                artifact.sizeBytes(),
                artifact.sha256(),
                previewable(artifact),
                "/admin/rd-tasks/%s/host-verifications/%s/evidence/%s/content"
                        .formatted(taskId, runId, artifact.artifactId())
        );
    }

    private void requireTask(String taskId) {
        try {
            registry.getTask(taskId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private HostVerificationRun requireRun(String taskId, String runId) {
        HostVerificationRun run = store.find(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "host verification run not found"));
        if (!taskId.equals(run.taskId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "host verification run not found");
        }
        return run;
    }

    private HostVerificationArtifact requireOwnedArtifact(String taskId, String runId, String artifactId) {
        return store.listArtifacts(runId).stream()
                .filter(candidate -> candidate.artifactId().equals(artifactId))
                .filter(candidate -> taskId.equals(candidate.taskId()) && runId.equals(candidate.runId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "host verification evidence not found"));
    }

    private InputStream openArtifact(HostVerificationArtifact artifact) {
        String uri = artifact.objectUri();
        if (uri.startsWith("s3://")) {
            return objectStorageService.openStream(uri);
        }
        if (uri.startsWith("file:")) {
            try {
                return Files.newInputStream(Path.of(URI.create(uri)));
            } catch (Exception exception) {
                throw new IllegalStateException("host verification file is unavailable: " + uri, exception);
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "host verification evidence object is unavailable");
    }

    private static boolean previewable(HostVerificationArtifact artifact) {
        String contentType = artifact.contentType();
        return contentType.startsWith("text/")
                || contentType.equals("application/json")
                || artifact.artifactType().endsWith("_LOG");
    }

    private static String artifactName(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String filename = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        return filename.isBlank() ? "verify-evidence" : filename;
    }

    private static String downloadFilename(HostVerificationArtifact artifact) {
        return artifactName(artifact.relativePath());
    }

    private static MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * List envelope for {@code GET /admin/rd-tasks/{taskId}/host-verifications}.
     *
     * @param taskId owning task id
     * @param runs   verification run views in attempt order
     */
    public record HostVerificationListView(
            String taskId,
            List<HostVerificationRunView> runs
    ) {
    }

    /**
     * Frozen run view. Field names must stay aligned with the frontend contract.
     */
    public record HostVerificationRunView(
            String runId,
            String codingStageRunId,
            String parentRunId,
            int attemptNo,
            HostVerificationStatus status,
            boolean docsOnly,
            String failureCategory,
            String errorMessage,
            int remediationCount,
            long createdAtEpochMillis,
            long startedAtEpochMillis,
            long finishedAtEpochMillis,
            List<HostVerificationStepView> steps,
            List<HostVerificationArtifactView> artifacts
    ) {
    }

    /**
     * Frozen BUILD/STATIC step view.
     */
    public record HostVerificationStepView(
            HostVerificationStepName step,
            HostVerificationStepStatus status,
            List<String> commands,
            Integer exitCode,
            long durationMillis,
            String logArtifactId,
            String errorMessage
    ) {
    }

    /**
     * Frozen evidence view. {@code objectUri} must never appear here.
     */
    public record HostVerificationArtifactView(
            String artifactId,
            String type,
            String name,
            String relativePath,
            String contentType,
            long sizeBytes,
            String sha256,
            boolean previewable,
            String contentUrl
    ) {
    }
}
