package com.wish.rd.bootstrap.controller.admin.projectmemory;

import com.wish.rd.bootstrap.openviking.OpenVikingErrorTranslator;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryAdminMutationService;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryAdminService;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationDeniedException;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationDisabledException;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminDetailView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminMutationResult;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminRevisionView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminSourceView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminSummaryView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryRetrievalAuditView;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmTokenExpiredException;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmTokenInvalidException;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeRowCountMismatchException;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeService;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryPurgeExecuteResult;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryPurgePreviewResult;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.memory.ProjectMemoryGovernanceConflictException;
import com.wish.rd.rag.project.model.RdProject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Project memory governance admin API. Controllers only validate requests, translate views to DTOs,
 * and map domain failures to safe HTTP responses; host principals are never taken from request bodies.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class ProjectMemoryAdminController {

    private final RdProjectService projectService;
    private final ProjectMemoryAdminService adminService;
    private final ProjectMemoryAdminMutationService mutationService;
    private final ProjectMemoryPurgeService purgeService;

    public ProjectMemoryAdminController(
            RdProjectService projectService,
            ProjectMemoryAdminService adminService,
            ProjectMemoryAdminMutationService mutationService,
            ProjectMemoryPurgeService purgeService
    ) {
        this.projectService = projectService;
        this.adminService = adminService;
        this.mutationService = mutationService;
        this.purgeService = purgeService;
    }

    @GetMapping("/admin/projects/{projectId}/memories")
    public DataResponse<MemoryListView> list(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        RdProject project = requireProject(projectId);
        List<ProjectMemoryAdminSummaryView> summaries = adminService.listByProject(projectId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int fromIndex = Math.min(summaries.size(), (safePage - 1) * safeSize);
        int toIndex = Math.min(summaries.size(), fromIndex + safeSize);
        List<SummaryView> pageRecords = summaries.subList(fromIndex, toIndex).stream()
                .map(this::toSummary)
                .toList();
        return new DataResponse<>(new MemoryListView(
                pageRecords,
                summaries.size(),
                safePage,
                safeSize,
                mutationService.mutationsEnabled(),
                !project.enabled()
        ));
    }

    @GetMapping("/admin/projects/{projectId}/memories/{memoryId}")
    public DataResponse<DetailView> detail(
            @PathVariable("projectId") String projectId,
            @PathVariable("memoryId") String memoryId
    ) {
        requireProject(projectId);
        return new DataResponse<>(toDetail(adminService.getDetail(projectId, memoryId)));
    }

    @PostMapping(
            value = "/admin/projects/{projectId}/memories/{memoryId}/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public DataResponse<MutationView> confirm(
            @PathVariable("projectId") String projectId,
            @PathVariable("memoryId") String memoryId,
            @RequestBody(required = false) ConfirmRequest request
    ) {
        requireWritableProject(projectId);
        ConfirmRequest body = requireBody(request);
        requireMemoryId(memoryId, body.revisionId() == null ? "" : body.revisionId());
        if (body.revisionId() == null || body.revisionId().isBlank()) {
            throw new IllegalArgumentException("revisionId must not be blank");
        }
        if (body.expectedMemoryRowVersion() == null) {
            throw new IllegalArgumentException("expectedMemoryRowVersion must not be null");
        }
        if (body.expectedRevisionRowVersion() == null) {
            throw new IllegalArgumentException("expectedRevisionRowVersion must not be null");
        }
        String requestId = requireRequestId(body.requestId());
        return new DataResponse<>(toMutation(mutationService.confirm(
                new ProjectMemoryAdminMutationService.ProjectMemoryConfirmRequest(
                        projectId,
                        memoryId,
                        body.revisionId(),
                        body.expectedMemoryRowVersion(),
                        body.expectedRevisionRowVersion(),
                        requestId,
                        body.requestedActor()
                )
        )));
    }

    @PostMapping(
            value = "/admin/projects/{projectId}/memories/{memoryId}/correct",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public DataResponse<MutationView> correct(
            @PathVariable("projectId") String projectId,
            @PathVariable("memoryId") String memoryId,
            @RequestBody(required = false) CorrectRequest request
    ) {
        requireWritableProject(projectId);
        CorrectRequest body = requireBody(request);
        if (body.revisionId() == null || body.revisionId().isBlank()) {
            throw new IllegalArgumentException("revisionId must not be blank");
        }
        if (body.expectedMemoryRowVersion() == null) {
            throw new IllegalArgumentException("expectedMemoryRowVersion must not be null");
        }
        if (body.expectedRevisionRowVersion() == null) {
            throw new IllegalArgumentException("expectedRevisionRowVersion must not be null");
        }
        if (body.newRevisionId() == null || body.newRevisionId().isBlank()) {
            throw new IllegalArgumentException("newRevisionId must not be blank");
        }
        if (body.correctedContentHash() == null || body.correctedContentHash().isBlank()) {
            throw new IllegalArgumentException("correctedContentHash must not be blank");
        }
        String requestId = requireRequestId(body.requestId());
        return new DataResponse<>(toMutation(mutationService.correct(
                new ProjectMemoryAdminMutationService.ProjectMemoryCorrectRequest(
                        projectId,
                        memoryId,
                        body.revisionId(),
                        body.expectedMemoryRowVersion(),
                        body.expectedRevisionRowVersion(),
                        text(body.correctedTitle()),
                        text(body.correctedSummary()),
                        text(body.correctedContentJson()),
                        body.correctedContentHash(),
                        body.newRevisionId(),
                        requestId,
                        body.requestedActor()
                )
        )));
    }

    @PostMapping(
            value = "/admin/projects/{projectId}/memories/{memoryId}/invalidate",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public DataResponse<MutationView> invalidate(
            @PathVariable("projectId") String projectId,
            @PathVariable("memoryId") String memoryId,
            @RequestBody(required = false) RevisionMutationRequest request
    ) {
        requireWritableProject(projectId);
        RevisionMutationRequest body = requireBody(request);
        if (body.revisionId() == null || body.revisionId().isBlank()) {
            throw new IllegalArgumentException("revisionId must not be blank");
        }
        if (body.expectedMemoryRowVersion() == null) {
            throw new IllegalArgumentException("expectedMemoryRowVersion must not be null");
        }
        if (body.expectedRevisionRowVersion() == null) {
            throw new IllegalArgumentException("expectedRevisionRowVersion must not be null");
        }
        String requestId = requireRequestId(body.requestId());
        return new DataResponse<>(toMutation(mutationService.invalidate(
                new ProjectMemoryAdminMutationService.ProjectMemoryInvalidateRequest(
                        projectId,
                        memoryId,
                        body.revisionId(),
                        body.expectedMemoryRowVersion(),
                        body.expectedRevisionRowVersion(),
                        requestId,
                        body.requestedActor()
                )
        )));
    }

    @PostMapping(
            value = "/admin/projects/{projectId}/memories/{memoryId}/soft-delete",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public DataResponse<MutationView> softDelete(
            @PathVariable("projectId") String projectId,
            @PathVariable("memoryId") String memoryId,
            @RequestBody(required = false) SoftDeleteRequest request
    ) {
        requireWritableProject(projectId);
        SoftDeleteRequest body = requireBody(request);
        if (body.expectedMemoryRowVersion() == null) {
            throw new IllegalArgumentException("expectedMemoryRowVersion must not be null");
        }
        String requestId = requireRequestId(body.requestId());
        return new DataResponse<>(toMutation(mutationService.softDelete(
                new ProjectMemoryAdminMutationService.ProjectMemorySoftDeleteRequest(
                        projectId,
                        memoryId,
                        body.expectedMemoryRowVersion(),
                        requestId,
                        body.requestedActor()
                )
        )));
    }

    @PostMapping(
            value = "/admin/projects/{projectId}/memories/purge/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public DataResponse<PurgePreviewView> purgePreview(
            @PathVariable("projectId") String projectId,
            @RequestBody(required = false) PurgePreviewRequest request
    ) {
        requireProject(projectId);
        PurgePreviewRequest body = requireBody(request);
        String reason = requireReason(body.reason());
        String requestId = requireRequestId(body.requestId());
        return new DataResponse<>(toPurgePreview(purgeService.preview(
                new ProjectMemoryPurgeService.ProjectMemoryPurgePreviewRequest(
                        projectId, reason, requestId, body.requestedActor()
                )
        )));
    }

    @PostMapping(
            value = "/admin/projects/{projectId}/memories/purge/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public DataResponse<PurgeExecuteView> purgeExecute(
            @PathVariable("projectId") String projectId,
            @RequestBody(required = false) PurgeExecuteRequest request
    ) {
        requireProject(projectId);
        PurgeExecuteRequest body = requireBody(request);
        String reason = requireReason(body.reason());
        if (body.confirmToken() == null || body.confirmToken().isBlank()) {
            throw new IllegalArgumentException("confirmToken must not be blank");
        }
        String requestId = requireRequestId(body.requestId());
        return new DataResponse<>(toPurgeExecute(purgeService.execute(
                new ProjectMemoryPurgeService.ProjectMemoryPurgeExecuteRequest(
                        projectId, reason, body.confirmToken(), requestId, body.requestedActor()
                )
        )));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> badRequest(IllegalArgumentException exception) {
        if (isNotFoundSignal(exception)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorView(safe(exception.getMessage())));
        }
        return ResponseEntity.badRequest().body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorView> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler({ProjectMemoryGovernanceConflictException.class, IllegalStateException.class})
    public ResponseEntity<ErrorView> conflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler(ProjectMemoryMutationDeniedException.class)
    public ResponseEntity<ErrorView> forbidden(ProjectMemoryMutationDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler(ProjectMemoryMutationDisabledException.class)
    public ResponseEntity<ErrorView> mutationsDisabled(ProjectMemoryMutationDisabledException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler({
            ProjectMemoryPurgeConfirmTokenExpiredException.class,
            ProjectMemoryPurgeConfirmTokenInvalidException.class
    })
    public ResponseEntity<ErrorView> purgeConfirmFailure(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.GONE).body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler(ProjectMemoryPurgeRowCountMismatchException.class)
    public ResponseEntity<ErrorView> purgeRowCountMismatch(ProjectMemoryPurgeRowCountMismatchException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorView(safe(exception.getMessage())));
    }

    private RdProject requireProject(String projectId) {
        return projectService.get(projectId);
    }

    private void requireWritableProject(String projectId) {
        RdProject project = requireProject(projectId);
        if (!project.enabled()) {
            throw new ProjectMemoryMutationDeniedException("project is disabled: " + projectId);
        }
    }

    private static <T> T requireBody(T body) {
        if (body == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return body;
    }

    private static String requireRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        return normalized;
    }

    private static String requireReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return normalized;
    }

    private static void requireMemoryId(String pathMemoryId, String ignored) {
        if (pathMemoryId == null || pathMemoryId.isBlank()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
    }

    private static boolean isNotFoundSignal(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        return message.contains("unknown memory")
                || message.contains("does not belong to project")
                || message.contains("unknown revision");
    }

    private SummaryView toSummary(ProjectMemoryAdminSummaryView view) {
        return new SummaryView(
                view.memoryId(),
                view.projectId(),
                view.scopeRole(),
                view.memoryType().name(),
                view.logicalKey(),
                view.memoryRowVersion(),
                view.headRevisionId(),
                view.headVersion(),
                view.headStatus().name(),
                view.deleted()
        );
    }

    private DetailView toDetail(ProjectMemoryAdminDetailView view) {
        return new DetailView(
                view.memoryId(),
                view.projectId(),
                view.scopeRole(),
                view.memoryType().name(),
                view.logicalKey(),
                view.memoryRowVersion(),
                view.headRevisionId(),
                view.headVersion(),
                view.deleted(),
                view.revisions().stream().map(this::toRevision).toList(),
                view.sources().stream().map(this::toSource).toList(),
                view.retrievalAudits().stream().map(this::toAudit).toList()
        );
    }

    private RevisionView toRevision(ProjectMemoryAdminRevisionView view) {
        return new RevisionView(
                view.revisionId(),
                view.version(),
                view.status().name(),
                view.title(),
                view.summary(),
                view.contentHash(),
                view.rowVersion(),
                view.head()
        );
    }

    private SourceView toSource(ProjectMemoryAdminSourceView view) {
        return new SourceView(
                view.sourceId(),
                view.revisionId(),
                view.projectId(),
                view.taskId(),
                view.stageRunId(),
                view.artifactId(),
                view.sourceUri(),
                view.sourceContentHash(),
                view.repositoryRevision(),
                view.extractorVersion(),
                view.schemaVersion(),
                view.redactedSummary(),
                view.originReferencesAvailable()
        );
    }

    private AuditView toAudit(ProjectMemoryRetrievalAuditView view) {
        return new AuditView(
                view.memoryId(),
                view.revisionId(),
                view.revisionVersion(),
                view.querySummary(),
                view.examinedRowCount(),
                view.observedAtEpochMillis()
        );
    }

    private MutationView toMutation(ProjectMemoryAdminMutationResult result) {
        return new MutationView(
                result.memoryId(),
                result.revisionId(),
                result.memoryRowVersion(),
                result.revisionRowVersion(),
                result.revisionStatus().name(),
                result.memoryDeleted(),
                result.operatorId(),
                result.requestId()
        );
    }

    private PurgePreviewView toPurgePreview(ProjectMemoryPurgePreviewResult result) {
        return new PurgePreviewView(
                result.projectId(),
                result.operatorId(),
                result.reason(),
                toPurgeCounts(result.expectedCounts()),
                result.confirmToken(),
                result.confirmTokenExpiresAtEpochMillis(),
                result.requestId(),
                purgeService.purgeEnabled()
        );
    }

    private PurgeExecuteView toPurgeExecute(ProjectMemoryPurgeExecuteResult result) {
        return new PurgeExecuteView(
                result.projectId(),
                result.operatorId(),
                result.reason(),
                toPurgeCounts(result.expectedCounts()),
                toPurgeCounts(result.actualCounts()),
                result.requestId()
        );
    }

    private static PurgeCountsView toPurgeCounts(com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts counts) {
        return new PurgeCountsView(
                counts.memoryCount(),
                counts.revisionCount(),
                counts.sourceCount(),
                counts.operationCount(),
                counts.legacyLinkCount(),
                counts.totalRowCount()
        );
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String safe(String message) {
        return OpenVikingErrorTranslator.safeMessage(message == null ? "" : message);
    }

    public record DataResponse<T>(T data) {}

    public record ErrorView(String message) {}

    public record MemoryListView(
            List<SummaryView> records,
            int total,
            int page,
            int size,
            boolean mutationsEnabled,
            boolean projectReadOnly
    ) {}

    public record SummaryView(
            String memoryId,
            String projectId,
            String scopeRole,
            String memoryType,
            String logicalKey,
            long memoryRowVersion,
            String headRevisionId,
            long headVersion,
            String headStatus,
            boolean deleted
    ) {}

    public record DetailView(
            String memoryId,
            String projectId,
            String scopeRole,
            String memoryType,
            String logicalKey,
            long memoryRowVersion,
            String headRevisionId,
            long headVersion,
            boolean deleted,
            List<RevisionView> revisions,
            List<SourceView> sources,
            List<AuditView> retrievalAudits
    ) {}

    public record RevisionView(
            String revisionId,
            long version,
            String status,
            String title,
            String summary,
            String contentHash,
            long rowVersion,
            boolean head
    ) {}

    public record SourceView(
            String sourceId,
            String revisionId,
            String projectId,
            String taskId,
            String stageRunId,
            String artifactId,
            String sourceUri,
            String sourceContentHash,
            String repositoryRevision,
            String extractorVersion,
            String schemaVersion,
            String redactedSummary,
            boolean originReferencesAvailable
    ) {}

    public record AuditView(
            String memoryId,
            String revisionId,
            long revisionVersion,
            String querySummary,
            int examinedRowCount,
            long observedAtEpochMillis
    ) {}

    public record MutationView(
            String memoryId,
            String revisionId,
            long memoryRowVersion,
            long revisionRowVersion,
            String revisionStatus,
            boolean memoryDeleted,
            String operatorId,
            String requestId
    ) {}

    public record ConfirmRequest(
            String revisionId,
            Long expectedMemoryRowVersion,
            Long expectedRevisionRowVersion,
            String requestId,
            String requestedActor
    ) {}

    public record CorrectRequest(
            String revisionId,
            Long expectedMemoryRowVersion,
            Long expectedRevisionRowVersion,
            String correctedTitle,
            String correctedSummary,
            String correctedContentJson,
            String correctedContentHash,
            String newRevisionId,
            String requestId,
            String requestedActor
    ) {}

    public record RevisionMutationRequest(
            String revisionId,
            Long expectedMemoryRowVersion,
            Long expectedRevisionRowVersion,
            String requestId,
            String requestedActor
    ) {}

    public record SoftDeleteRequest(
            Long expectedMemoryRowVersion,
            String requestId,
            String requestedActor
    ) {}

    public record PurgePreviewRequest(
            String reason,
            String requestId,
            String requestedActor
    ) {}

    public record PurgeExecuteRequest(
            String reason,
            String confirmToken,
            String requestId,
            String requestedActor
    ) {}

    public record PurgeCountsView(
            int memoryCount,
            int revisionCount,
            int sourceCount,
            int operationCount,
            int legacyLinkCount,
            int totalRowCount
    ) {}

    public record PurgePreviewView(
            String projectId,
            String operatorId,
            String reason,
            PurgeCountsView expectedCounts,
            String confirmToken,
            long confirmTokenExpiresAtEpochMillis,
            String requestId,
            boolean purgeEnabled
    ) {}

    public record PurgeExecuteView(
            String projectId,
            String operatorId,
            String reason,
            PurgeCountsView expectedCounts,
            PurgeCountsView actualCounts,
            String requestId
    ) {}
}
