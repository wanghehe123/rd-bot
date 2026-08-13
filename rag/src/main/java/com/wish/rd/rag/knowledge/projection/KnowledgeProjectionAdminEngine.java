package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.impl.ProjectionObservationWriter;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;
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
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFinding;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingStatus;
import com.wish.rd.rag.knowledge.projection.model.ReconcileReport;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 投影管理面。只读账本或复用 enqueue/settle/verify/requeue 原语，
 * 从不直接发 REST，也不写 {@code desired_*}。
 */
public final class KnowledgeProjectionAdminEngine {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int TIMELINE_LIMIT = 100;

    private final ExternalKnowledgeIndexPort indexPort;
    private final KnowledgeExternalIndexBindingStore bindingStore;
    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeReconcileFindingStore findingStore;
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeExternalIndexReconcileEngine reconcileEngine;
    private final ProjectionWorkerSettings settings;
    private final Supplier<String> idSupplier;

    public KnowledgeProjectionAdminEngine(
            ExternalKnowledgeIndexPort indexPort,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeReconcileFindingStore findingStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeExternalIndexReconcileEngine reconcileEngine,
            ProjectionWorkerSettings settings,
            Supplier<String> idSupplier
    ) {
        this.indexPort = indexPort;
        this.bindingStore = bindingStore;
        this.outboxStore = outboxStore;
        this.findingStore = findingStore;
        this.documentStore = documentStore;
        this.reconcileEngine = reconcileEngine;
        this.settings = settings;
        this.idSupplier = idSupplier;
    }

    /**
     * 催一下卡住的行。不新建版本，不直接发 REST。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @param nowEpochMillis  当前时间
     * @return 操作结果
     */
    public ProjectionAdminActionResult retry(String knowledgeBaseId, String documentId, long nowEpochMillis) {
        requireOwnedDocument(knowledgeBaseId, documentId);
        Optional<KnowledgeExternalIndexOperation> latest = latestNonTerminal(documentId, knowledgeBaseId);
        if (latest.isEmpty()) {
            return ProjectionAdminActionResult.nothingToRetry();
        }
        KnowledgeExternalIndexOperation current = latest.get();
        if (current.status() != ExternalKnowledgeOperationStatus.RETRY_WAIT
                && current.status() != ExternalKnowledgeOperationStatus.NEEDS_HUMAN) {
            return ProjectionAdminActionResult.nothingToRetry();
        }
        KnowledgeExternalIndexOperation resumed = outboxStore.resumeStalled(
                        current.eventId(), current.rowVersion(), nowEpochMillis)
                .orElseThrow(() -> new ProjectionAdminConflictException("row_version 已过期，请刷新后重试"));
        return ProjectionAdminActionResult.retried(resumed);
    }

    /**
     * 只读核验远端并把观测 CAS 写成 IN_SYNC 或 DRIFTED。不写 outbox。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @param nowEpochMillis  当前时间
     * @return 核验结果
     */
    public ProjectionAdminActionResult verify(String knowledgeBaseId, String documentId, long nowEpochMillis) {
        requireOwnedDocument(knowledgeBaseId, documentId);
        requireProjectionReady("投影已关闭，无法核验远端");
        KnowledgeExternalIndexBinding binding = requireBinding(knowledgeBaseId, documentId);
        ExternalKnowledgeVerification verification = indexPort.verifyResource(new ExternalKnowledgeVersionMarker(
                binding.provider(),
                binding.remoteUri(),
                binding.ownershipMarker(),
                binding.knowledgeBaseId(),
                binding.documentId(),
                binding.desiredVersion(),
                binding.desiredChecksum()));
        if (verification.failureClass() == ExternalIndexFailureClass.CONFIGURATION_BLOCKED) {
            throw new ProjectionAdminConflictException("投影已关闭，无法核验远端");
        }
        if (verification.failureClass() != ExternalIndexFailureClass.NONE
                && verification.failureClass() != ExternalIndexFailureClass.MALFORMED_SUCCESS) {
            throw new ProjectionRemoteUnavailableException(
                    verification.errorMessage().isBlank() ? "远端不可用" : verification.errorMessage());
        }
        boolean inSync = verification.verified()
                && binding.desiredState() == ExternalKnowledgeDesiredState.PRESENT;
        KnowledgeExternalIndexBinding observation = new KnowledgeExternalIndexBinding(
                binding.provider(),
                binding.documentId(),
                binding.knowledgeBaseId(),
                binding.remoteUri(),
                binding.ownershipMarker(),
                binding.desiredState(),
                binding.desiredVersion(),
                binding.desiredChecksum(),
                inSync ? ExternalKnowledgeObservedState.READY : ExternalKnowledgeObservedState.DRIFTED,
                inSync ? binding.desiredVersion() : binding.observedVersion(),
                inSync ? binding.desiredChecksum() : binding.observedChecksum(),
                inSync ? ExternalKnowledgeProjectionStatus.IN_SYNC : ExternalKnowledgeProjectionStatus.DRIFTED,
                binding.activeOperationId(),
                binding.remoteTaskId(),
                binding.semanticConfigFingerprint(),
                binding.lastSubmittedAtEpochMillis(),
                nowEpochMillis,
                inSync ? "" : verification.errorCode(),
                inSync ? "" : verification.errorMessage(),
                binding.rowVersion(),
                binding.createdAtEpochMillis(),
                nowEpochMillis);
        ProjectionObservationWriter.write(bindingStore, observation);
        return ProjectionAdminActionResult.verified(inSync, verification.failedChecks());
    }

    /**
     * 为当前 desired version 入队 REBUILD_DOCUMENT。唯一键吸收重复。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @param nowEpochMillis  当前时间
     * @return 入队或已在队列中
     */
    public ProjectionAdminActionResult rebuild(String knowledgeBaseId, String documentId, long nowEpochMillis) {
        requireOwnedDocument(knowledgeBaseId, documentId);
        KnowledgeExternalIndexBinding binding = requireBinding(knowledgeBaseId, documentId);
        String eventId = idSupplier.get();
        KnowledgeExternalIndexOperation requested = new KnowledgeExternalIndexOperation(
                eventId,
                ExternalIndexIdempotencyKeys.document(
                        binding.provider(),
                        binding.documentId(),
                        binding.desiredVersion(),
                        ExternalKnowledgeOperationType.REBUILD_DOCUMENT),
                binding.provider(),
                ExternalKnowledgeOperationType.REBUILD_DOCUMENT,
                binding.knowledgeBaseId(),
                binding.documentId(),
                binding.desiredVersion(),
                binding.desiredChecksum(),
                binding.remoteUri(),
                "",
                "",
                ExternalKnowledgeOperationStatus.PENDING,
                "",
                "",
                "",
                0L,
                0,
                8,
                nowEpochMillis,
                0L,
                "",
                "",
                0L,
                nowEpochMillis,
                nowEpochMillis
        );
        try {
            KnowledgeExternalIndexOperation enqueued = outboxStore.enqueue(requested);
            if (enqueued.eventId().equals(eventId)) {
                return ProjectionAdminActionResult.rebuilt(enqueued);
            }
            return ProjectionAdminActionResult.alreadyQueued(enqueued);
        } catch (IllegalStateException ex) {
            Optional<KnowledgeExternalIndexOperation> existing = outboxStore.findByDocument(
                            binding.provider(), binding.documentId(), TIMELINE_LIMIT)
                    .stream()
                    .filter(operation -> operation.operationType() == ExternalKnowledgeOperationType.REBUILD_DOCUMENT
                            && operation.syncVersion() == binding.desiredVersion())
                    .findFirst();
            return existing.map(ProjectionAdminActionResult::alreadyQueued)
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * 人工把死信重新放回收敛路径。
     *
     * @param knowledgeBaseId    知识库 ID
     * @param eventId            outbox 行 ID
     * @param expectedRowVersion 期望 rowVersion
     * @param nowEpochMillis     当前时间
     * @return 重入队结果
     */
    public ProjectionAdminActionResult requeueDeadLetter(
            String knowledgeBaseId,
            String eventId,
            long expectedRowVersion,
            long nowEpochMillis
    ) {
        KnowledgeExternalIndexOperation current = outboxStore.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("死信不存在: " + eventId));
        if (!current.knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new IllegalArgumentException("死信不属于该知识库: kb=" + knowledgeBaseId + " event=" + eventId);
        }
        KnowledgeExternalIndexOperation requeued = outboxStore.requeueDeadLetter(
                        eventId, expectedRowVersion, nowEpochMillis)
                .orElseThrow(() -> new ProjectionAdminConflictException("row_version 已过期，请刷新后重试"));
        return ProjectionAdminActionResult.requeued(requeued);
    }

    /**
     * 跑一轮对账。投影关闭时拒绝，避免把连不上编造成缺失发现。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param nowEpochMillis  当前时间
     * @return 本轮发现
     */
    public ReconcileReport reconcile(String knowledgeBaseId, long nowEpochMillis) {
        requireProjectionReady("投影已关闭，无法对账远端");
        return reconcileEngine.runOnce(
                knowledgeBaseId, OpenVikingProjectionUris.knowledgeBaseRoot(knowledgeBaseId), nowEpochMillis);
    }

    /**
     * 账本总览：分状态计数与最老未收敛年龄。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param nowEpochMillis  当前时间
     * @return 总览
     */
    public ProjectionAdminOverview overview(String knowledgeBaseId, long nowEpochMillis) {
        Map<String, Long> bindingCounts = toNameCounts(bindingStore.countByProjectionStatus(
                KnowledgeExternalIndexBinding.OPENVIKING, knowledgeBaseId));
        Map<ExternalKnowledgeOperationStatus, Long> outboxCounts = outboxStore.countByStatus(
                KnowledgeExternalIndexBinding.OPENVIKING, knowledgeBaseId);
        long unconverged = 0L;
        long oldestCreated = 0L;
        for (Map.Entry<ExternalKnowledgeOperationStatus, Long> entry : outboxCounts.entrySet()) {
            if (entry.getKey().terminal()) {
                continue;
            }
            unconverged += entry.getValue();
            for (KnowledgeExternalIndexOperation operation : outboxStore.findByStatus(
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    knowledgeBaseId,
                    entry.getKey(),
                    0,
                    10_000)) {
                if (oldestCreated == 0L || operation.createdAtEpochMillis() < oldestCreated) {
                    oldestCreated = operation.createdAtEpochMillis();
                }
            }
        }
        long age = oldestCreated <= 0L ? 0L : Math.max(0L, nowEpochMillis - oldestCreated);
        return new ProjectionAdminOverview(
                indexPort.ready(), bindingCounts, toNameCounts(outboxCounts), unconverged, age);
    }

    /**
     * 文档映射表。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param status          可选 projection_status
     * @param page            1-based 页码
     * @param size            页大小
     * @return 分页行
     */
    public ProjectionAdminPage<ProjectionAdminDocumentRow> documents(
            String knowledgeBaseId,
            String status,
            int page,
            int size
    ) {
        PageWindow window = pageWindow(page, size);
        ExternalKnowledgeProjectionStatus filter = parseProjectionStatus(status);
        int total = countBindings(knowledgeBaseId, filter);
        List<ProjectionAdminDocumentRow> rows = bindingStore.findByKnowledgeBase(
                        KnowledgeExternalIndexBinding.OPENVIKING, knowledgeBaseId, filter, window.offset(), window.size())
                .stream()
                .map(this::toDocumentRow)
                .toList();
        return new ProjectionAdminPage<>(rows, total, window.page(), window.size());
    }

    /**
     * 一篇文档的绑定与操作时间线。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @return 详情
     */
    public ProjectionAdminDocumentDetail documentDetail(String knowledgeBaseId, String documentId) {
        requireOwnedDocument(knowledgeBaseId, documentId);
        KnowledgeExternalIndexBinding binding = requireBinding(knowledgeBaseId, documentId);
        List<KnowledgeExternalIndexOperation> operations = outboxStore.findByDocument(
                binding.provider(), documentId, TIMELINE_LIMIT);
        String errorCode = binding.lastErrorCode();
        String errorMessage = binding.lastErrorMessage();
        if (errorCode.isBlank() && errorMessage.isBlank() && !operations.isEmpty()) {
            errorCode = operations.getFirst().lastErrorCode();
            errorMessage = operations.getFirst().lastErrorMessage();
        }
        return new ProjectionAdminDocumentDetail(binding, operations, errorCode, errorMessage);
    }

    /**
     * 死信列表。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param page            1-based 页码
     * @param size            页大小
     * @return 分页死信
     */
    public ProjectionAdminPage<KnowledgeExternalIndexOperation> deadLetters(
            String knowledgeBaseId,
            int page,
            int size
    ) {
        PageWindow window = pageWindow(page, size);
        long total = outboxStore.countByStatus(KnowledgeExternalIndexBinding.OPENVIKING, knowledgeBaseId)
                .getOrDefault(ExternalKnowledgeOperationStatus.DEAD_LETTER, 0L);
        List<KnowledgeExternalIndexOperation> rows = outboxStore.findByStatus(
                KnowledgeExternalIndexBinding.OPENVIKING,
                knowledgeBaseId,
                ExternalKnowledgeOperationStatus.DEAD_LETTER,
                window.offset(),
                window.size());
        return new ProjectionAdminPage<>(rows, Math.toIntExact(Math.min(Integer.MAX_VALUE, total)),
                window.page(), window.size());
    }

    /**
     * 本地墓碑：deleted_at 非空的文档及其绑定观测。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param page            1-based 页码
     * @param size            页大小
     * @return 分页墓碑
     */
    public ProjectionAdminPage<ProjectionAdminTombstone> tombstones(
            String knowledgeBaseId,
            int page,
            int size
    ) {
        PageWindow window = pageWindow(page, size);
        List<ProjectionAdminTombstone> all = documentStore.listByKnowledgeBaseId(knowledgeBaseId).stream()
                .filter(document -> document.deletedAtEpochMillis() > 0L)
                .sorted(Comparator.comparingLong(KnowledgeDocument::deletedAtEpochMillis).reversed()
                        .thenComparing(KnowledgeDocument::id, Comparator.reverseOrder()))
                .map(document -> new ProjectionAdminTombstone(
                        document.id(),
                        document.sourceName(),
                        document.deletedAtEpochMillis(),
                        bindingStore.findByProviderAndDocumentId(
                                KnowledgeExternalIndexBinding.OPENVIKING, document.id()).orElse(null)))
                .toList();
        int from = Math.min(window.offset(), all.size());
        int to = Math.min(from + window.size(), all.size());
        return new ProjectionAdminPage<>(all.subList(from, to), all.size(), window.page(), window.size());
    }

    /**
     * 只读列远端树。请求 URI 必须落在该 KB owned root 之下。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param uri             可选子路径，空则用 owned root
     * @return 列举结果
     */
    public ExternalTreeListing tree(String knowledgeBaseId, String uri) {
        String ownedRoot = OpenVikingProjectionUris.knowledgeBaseRoot(knowledgeBaseId);
        String requested = uri == null || uri.isBlank() ? ownedRoot : uri.strip();
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(requested, ownedRoot)) {
            throw new IllegalArgumentException("URI 不在该知识库 owned root 之内: " + requested);
        }
        return indexPort.listTree(requested);
    }

    /**
     * 健康卡。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 健康快照
     */
    public ProjectionAdminHealth health(String knowledgeBaseId) {
        List<ReconcileFinding> findings = findingStore.listByKnowledgeBase(
                KnowledgeExternalIndexBinding.OPENVIKING, knowledgeBaseId);
        LinkedHashMap<String, Long> findingCounts = new LinkedHashMap<>();
        long open = 0L;
        for (ReconcileFinding finding : findings) {
            findingCounts.merge(finding.findingType().name(), 1L, Long::sum);
            if (finding.status() != ReconcileFindingStatus.RESOLVED) {
                open++;
            }
        }
        String fingerprint = bindingStore.findByKnowledgeBase(
                        KnowledgeExternalIndexBinding.OPENVIKING, knowledgeBaseId, null, 0, 20)
                .stream()
                .map(KnowledgeExternalIndexBinding::semanticConfigFingerprint)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        return new ProjectionAdminHealth(
                indexPort.ready(),
                settings.batchSize(),
                settings.workerLeaseMillis(),
                settings.unknownOutcomeTimeoutMillis(),
                fingerprint,
                open,
                findingCounts);
    }

    private void requireOwnedDocument(String knowledgeBaseId, String documentId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank() || documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("知识库或文档 ID 不能为空");
        }
        Optional<KnowledgeDocument> document = documentStore.findById(documentId);
        Optional<KnowledgeExternalIndexBinding> binding = bindingStore.findByProviderAndDocumentId(
                KnowledgeExternalIndexBinding.OPENVIKING, documentId);
        if (document.isPresent() && !document.get().knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new IllegalArgumentException("文档不属于该知识库: kb=" + knowledgeBaseId + " doc=" + documentId);
        }
        if (binding.isPresent() && !binding.get().knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new IllegalArgumentException("文档不属于该知识库: kb=" + knowledgeBaseId + " doc=" + documentId);
        }
        if (document.isEmpty() && binding.isEmpty()) {
            throw new IllegalArgumentException("文档不属于该知识库: kb=" + knowledgeBaseId + " doc=" + documentId);
        }
    }

    private KnowledgeExternalIndexBinding requireBinding(String knowledgeBaseId, String documentId) {
        KnowledgeExternalIndexBinding binding = bindingStore.findByProviderAndDocumentId(
                        KnowledgeExternalIndexBinding.OPENVIKING, documentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "文档不属于该知识库: kb=" + knowledgeBaseId + " doc=" + documentId));
        if (!binding.knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new IllegalArgumentException("文档不属于该知识库: kb=" + knowledgeBaseId + " doc=" + documentId);
        }
        return binding;
    }

    private void requireProjectionReady(String message) {
        if (!indexPort.ready()) {
            throw new ProjectionAdminConflictException(message);
        }
    }

    private Optional<KnowledgeExternalIndexOperation> latestNonTerminal(String documentId, String knowledgeBaseId) {
        return outboxStore.findByDocument(KnowledgeExternalIndexBinding.OPENVIKING, documentId, TIMELINE_LIMIT)
                .stream()
                .filter(operation -> operation.knowledgeBaseId().equals(knowledgeBaseId))
                .filter(operation -> !operation.status().terminal())
                .findFirst();
    }

    private ProjectionAdminDocumentRow toDocumentRow(KnowledgeExternalIndexBinding binding) {
        Optional<KnowledgeDocument> document = documentStore.findById(binding.documentId());
        return new ProjectionAdminDocumentRow(
                binding.documentId(),
                document.map(KnowledgeDocument::sourceName).orElse(""),
                document.map(item -> item.deletedAtEpochMillis() > 0L).orElse(false),
                binding);
    }

    private int countBindings(String knowledgeBaseId, ExternalKnowledgeProjectionStatus filter) {
        Map<ExternalKnowledgeProjectionStatus, Long> counts = bindingStore.countByProjectionStatus(
                KnowledgeExternalIndexBinding.OPENVIKING, knowledgeBaseId);
        if (filter == null) {
            return Math.toIntExact(Math.min(Integer.MAX_VALUE,
                    counts.values().stream().mapToLong(Long::longValue).sum()));
        }
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, counts.getOrDefault(filter, 0L)));
    }

    private static ExternalKnowledgeProjectionStatus parseProjectionStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ExternalKnowledgeProjectionStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的投影状态: " + status);
        }
    }

    private static PageWindow pageWindow(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return new PageWindow(safePage, safeSize, (safePage - 1) * safeSize);
    }

    private static Map<String, Long> toNameCounts(Map<? extends Enum<?>, Long> counts) {
        LinkedHashMap<String, Long> named = new LinkedHashMap<>();
        counts.forEach((key, value) -> named.put(key.name(), value));
        return named;
    }

    private record PageWindow(int page, int size, int offset) {
    }
}
