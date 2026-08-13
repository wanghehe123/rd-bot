package com.wish.rd.bootstrap.controller.admin.knowledge;

import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionAdminEngine;
import com.wish.rd.rag.knowledge.projection.ProjectionAdminConflictException;
import com.wish.rd.rag.knowledge.projection.ProjectionRemoteUnavailableException;
import com.wish.rd.rag.knowledge.projection.model.ExternalTreeListing;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminActionResult;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminDocumentDetail;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminDocumentRow;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminHealth;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminOverview;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminPage;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminTombstone;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFinding;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingType;
import com.wish.rd.rag.knowledge.projection.model.ReconcileReport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenViking 投影管理 API。Controller 只把 {@link KnowledgeProjectionAdminEngine}
 * 的结果译成 DTO，从不直接发 REST。
 */
@RestController
@RequestMapping("/admin/knowledge-base/{knowledgeBaseId}/openviking")
public final class KnowledgeProjectionAdminController {

    private final KnowledgeProjectionAdminEngine adminEngine;

    public KnowledgeProjectionAdminController(KnowledgeProjectionAdminEngine adminEngine) {
        this.adminEngine = adminEngine;
    }

    @GetMapping("/overview")
    public DataResponse<OverviewView> overview(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        return new DataResponse<>(toOverview(adminEngine.overview(knowledgeBaseId, now())));
    }

    @GetMapping("/documents")
    public DataResponse<PageView<DocumentRowView>> documents(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return new DataResponse<>(toDocumentPage(adminEngine.documents(knowledgeBaseId, status, page, size)));
    }

    @GetMapping("/documents/{documentId}")
    public DataResponse<DocumentDetailView> documentDetail(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @PathVariable("documentId") String documentId
    ) {
        return new DataResponse<>(toDetail(adminEngine.documentDetail(knowledgeBaseId, documentId)));
    }

    @GetMapping("/tree")
    public DataResponse<TreeView> tree(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "uri", required = false) String uri
    ) {
        return new DataResponse<>(toTree(adminEngine.tree(knowledgeBaseId, uri)));
    }

    @GetMapping("/health")
    public DataResponse<HealthView> health(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        return new DataResponse<>(toHealth(adminEngine.health(knowledgeBaseId)));
    }

    @GetMapping("/dead-letters")
    public DataResponse<PageView<OperationView>> deadLetters(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        ProjectionAdminPage<KnowledgeExternalIndexOperation> result =
                adminEngine.deadLetters(knowledgeBaseId, page, size);
        return new DataResponse<>(new PageView<>(
                result.records().stream().map(this::toOperation).toList(),
                result.total(),
                result.page(),
                result.size()));
    }

    @GetMapping("/tombstones")
    public DataResponse<PageView<TombstoneView>> tombstones(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        ProjectionAdminPage<ProjectionAdminTombstone> result = adminEngine.tombstones(knowledgeBaseId, page, size);
        return new DataResponse<>(new PageView<>(
                result.records().stream().map(this::toTombstone).toList(),
                result.total(),
                result.page(),
                result.size()));
    }

    @PostMapping("/documents/{documentId}/retry")
    public DataResponse<ActionView> retry(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @PathVariable("documentId") String documentId
    ) {
        return new DataResponse<>(toAction(adminEngine.retry(knowledgeBaseId, documentId, now())));
    }

    @PostMapping("/documents/{documentId}/verify")
    public DataResponse<ActionView> verify(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @PathVariable("documentId") String documentId
    ) {
        return new DataResponse<>(toAction(adminEngine.verify(knowledgeBaseId, documentId, now())));
    }

    @PostMapping("/documents/{documentId}/rebuild")
    public DataResponse<ActionView> rebuild(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @PathVariable("documentId") String documentId
    ) {
        return new DataResponse<>(toAction(adminEngine.rebuild(knowledgeBaseId, documentId, now())));
    }

    @PostMapping("/dead-letters/{eventId}/requeue")
    public DataResponse<ActionView> requeue(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @PathVariable("eventId") String eventId,
            @RequestBody(required = false) RequeueRequest request
    ) {
        if (request == null || request.expectedRowVersion() == null) {
            throw new IllegalArgumentException("expectedRowVersion 不能为空");
        }
        return new DataResponse<>(toAction(adminEngine.requeueDeadLetter(
                knowledgeBaseId, eventId, request.expectedRowVersion(), now())));
    }

    @PostMapping("/reconcile")
    public DataResponse<ReconcileView> reconcile(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        return new DataResponse<>(toReconcile(adminEngine.reconcile(knowledgeBaseId, now())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler({ProjectionAdminConflictException.class, IllegalStateException.class})
    public ResponseEntity<ErrorView> conflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorView(safe(exception.getMessage())));
    }

    @ExceptionHandler(ProjectionRemoteUnavailableException.class)
    public ResponseEntity<ErrorView> remoteUnavailable(ProjectionRemoteUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorView(safe(exception.getMessage())));
    }

    private OverviewView toOverview(ProjectionAdminOverview overview) {
        return new OverviewView(
                overview.ready(),
                overview.bindingCounts(),
                overview.outboxCounts(),
                overview.unconvergedCount(),
                overview.oldestUnconvergedAgeMillis());
    }

    private PageView<DocumentRowView> toDocumentPage(ProjectionAdminPage<ProjectionAdminDocumentRow> page) {
        return new PageView<>(
                page.records().stream().map(this::toDocumentRow).toList(),
                page.total(),
                page.page(),
                page.size());
    }

    private DocumentRowView toDocumentRow(ProjectionAdminDocumentRow row) {
        KnowledgeExternalIndexBinding binding = row.binding();
        return new DocumentRowView(
                row.documentId(),
                row.sourceName(),
                row.tombstone(),
                binding == null ? "" : binding.desiredState().name(),
                binding == null ? 0L : binding.desiredVersion(),
                binding == null ? "" : binding.desiredChecksum(),
                binding == null ? "" : binding.observedState().name(),
                binding == null ? 0L : binding.observedVersion(),
                binding == null ? "" : binding.observedChecksum(),
                binding == null ? "" : binding.projectionStatus().name(),
                binding == null ? "" : binding.remoteUri(),
                binding == null ? "" : binding.remoteTaskId(),
                binding == null ? "" : binding.activeOperationId(),
                binding == null ? 0L : binding.lastVerifiedAtEpochMillis(),
                binding == null ? "" : binding.lastErrorCode(),
                binding == null ? "" : binding.lastErrorMessage());
    }

    private DocumentDetailView toDetail(ProjectionAdminDocumentDetail detail) {
        return new DocumentDetailView(
                toBinding(detail.binding()),
                detail.operations().stream().map(this::toOperation).toList(),
                detail.lastErrorCode(),
                detail.lastErrorMessage());
    }

    private BindingView toBinding(KnowledgeExternalIndexBinding binding) {
        return new BindingView(
                binding.documentId(),
                binding.knowledgeBaseId(),
                binding.remoteUri(),
                binding.desiredState().name(),
                binding.desiredVersion(),
                binding.desiredChecksum(),
                binding.observedState().name(),
                binding.observedVersion(),
                binding.observedChecksum(),
                binding.projectionStatus().name(),
                binding.remoteTaskId(),
                binding.activeOperationId(),
                binding.semanticConfigFingerprint(),
                binding.lastVerifiedAtEpochMillis(),
                binding.lastErrorCode(),
                binding.lastErrorMessage(),
                binding.rowVersion());
    }

    private OperationView toOperation(KnowledgeExternalIndexOperation operation) {
        return new OperationView(
                operation.eventId(),
                operation.operationType().name(),
                operation.status().name(),
                operation.syncVersion(),
                operation.remoteUri(),
                operation.remoteTaskId(),
                operation.remoteOperationId(),
                operation.attemptCount(),
                operation.maxAttempts(),
                operation.rowVersion(),
                operation.lastErrorCode(),
                operation.lastErrorMessage(),
                operation.createdAtEpochMillis(),
                operation.updatedAtEpochMillis());
    }

    private TombstoneView toTombstone(ProjectionAdminTombstone tombstone) {
        KnowledgeExternalIndexBinding binding = tombstone.binding();
        return new TombstoneView(
                tombstone.documentId(),
                tombstone.sourceName(),
                tombstone.deletedAtEpochMillis(),
                binding == null ? "" : binding.projectionStatus().name(),
                binding == null ? "" : binding.remoteUri());
    }

    private TreeView toTree(ExternalTreeListing listing) {
        return new TreeView(
                listing.entries().stream()
                        .map(entry -> new TreeEntryView(entry.uri(), entry.name(), entry.directory(), entry.owner()))
                        .toList(),
                listing.errorCode(),
                listing.errorMessage());
    }

    private HealthView toHealth(ProjectionAdminHealth health) {
        return new HealthView(
                health.ready(),
                health.workerBatchSize(),
                health.workerLeaseMillis(),
                health.unknownOutcomeTimeoutMillis(),
                health.semanticConfigFingerprint(),
                health.openFindingCount(),
                health.findingCounts());
    }

    private ActionView toAction(ProjectionAdminActionResult result) {
        return new ActionView(
                result.applied(),
                result.outcome(),
                result.eventId(),
                result.operationStatus(),
                result.failedChecks(),
                result.message());
    }

    private ReconcileView toReconcile(ReconcileReport report) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<ReconcileFindingType, Integer> entry : report.counts().entrySet()) {
            counts.put(entry.getKey().name(), entry.getValue());
        }
        return new ReconcileView(
                counts,
                report.findings().stream().map(this::toFinding).toList());
    }

    private FindingView toFinding(ReconcileFinding finding) {
        return new FindingView(
                finding.id(),
                finding.findingType().name(),
                finding.remoteUri(),
                finding.documentId(),
                finding.detail(),
                finding.status().name(),
                finding.firstSeenAtEpochMillis(),
                finding.lastSeenAtEpochMillis());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String safe(String message) {
        return message == null ? "" : message;
    }

    public record DataResponse<T>(T data) {
    }

    public record ErrorView(String message) {
    }

    public record RequeueRequest(Long expectedRowVersion) {
    }

    public record OverviewView(
            boolean ready,
            Map<String, Long> bindingCounts,
            Map<String, Long> outboxCounts,
            long unconvergedCount,
            long oldestUnconvergedAgeMillis
    ) {
    }

    public record PageView<T>(List<T> records, int total, int page, int size) {
    }

    public record DocumentRowView(
            String documentId,
            String sourceName,
            boolean tombstone,
            String desiredState,
            long desiredVersion,
            String desiredChecksum,
            String observedState,
            long observedVersion,
            String observedChecksum,
            String projectionStatus,
            String remoteUri,
            String remoteTaskId,
            String activeOperationId,
            long lastVerifiedAtEpochMillis,
            String lastErrorCode,
            String lastErrorMessage
    ) {
    }

    public record DocumentDetailView(
            BindingView binding,
            List<OperationView> operations,
            String lastErrorCode,
            String lastErrorMessage
    ) {
    }

    public record BindingView(
            String documentId,
            String knowledgeBaseId,
            String remoteUri,
            String desiredState,
            long desiredVersion,
            String desiredChecksum,
            String observedState,
            long observedVersion,
            String observedChecksum,
            String projectionStatus,
            String remoteTaskId,
            String activeOperationId,
            String semanticConfigFingerprint,
            long lastVerifiedAtEpochMillis,
            String lastErrorCode,
            String lastErrorMessage,
            long rowVersion
    ) {
    }

    public record OperationView(
            String eventId,
            String operationType,
            String status,
            long syncVersion,
            String remoteUri,
            String remoteTaskId,
            String remoteOperationId,
            int attemptCount,
            int maxAttempts,
            long rowVersion,
            String lastErrorCode,
            String lastErrorMessage,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
    }

    public record TombstoneView(
            String documentId,
            String sourceName,
            long deletedAtEpochMillis,
            String projectionStatus,
            String remoteUri
    ) {
    }

    public record TreeView(List<TreeEntryView> entries, String errorCode, String errorMessage) {
    }

    public record TreeEntryView(String uri, String name, boolean directory, String owner) {
    }

    public record HealthView(
            boolean ready,
            int workerBatchSize,
            long workerLeaseMillis,
            long unknownOutcomeTimeoutMillis,
            String semanticConfigFingerprint,
            long openFindingCount,
            Map<String, Long> findingCounts
    ) {
    }

    public record ActionView(
            boolean applied,
            String outcome,
            String eventId,
            String operationStatus,
            List<String> failedChecks,
            String message
    ) {
    }

    public record ReconcileView(Map<String, Integer> counts, List<FindingView> findings) {
    }

    public record FindingView(
            String id,
            String findingType,
            String remoteUri,
            String documentId,
            String detail,
            String status,
            long firstSeenAtEpochMillis,
            long lastSeenAtEpochMillis
    ) {
    }
}
