package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.impl.ProjectionObservationWriter;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe;
import com.wish.rd.rag.knowledge.projection.model.ExternalTreeListing;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFinding;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingStatus;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingType;
import com.wish.rd.rag.knowledge.projection.model.ReconcileReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 投影对账引擎：只发现、只入队重建，从不直接改远端。
 *
 * <p>孤儿隔离成 finding，外来所有权永不修复。本轮把缺失的 IN_SYNC 观测改成
 * {@code DRIFTED} 并入队 {@code REBUILD_DOCUMENT}，desired 列仍只属于本地 mutation。
 */
public final class KnowledgeExternalIndexReconcileEngine {

    static final String OWNED_OWNER = "rd-bot";

    private final ExternalKnowledgeIndexPort indexPort;
    private final KnowledgeExternalIndexBindingStore bindingStore;
    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeReconcileFindingStore findingStore;
    private final ProjectionWorkerSettings settings;
    private final long staleAfterMillis;
    private final Supplier<String> idSupplier;

    public KnowledgeExternalIndexReconcileEngine(
            ExternalKnowledgeIndexPort indexPort,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeReconcileFindingStore findingStore,
            ProjectionWorkerSettings settings,
            long staleAfterMillis,
            Supplier<String> idSupplier
    ) {
        this.indexPort = indexPort;
        this.bindingStore = bindingStore;
        this.outboxStore = outboxStore;
        this.findingStore = findingStore;
        this.settings = settings;
        this.staleAfterMillis = Math.max(0L, staleAfterMillis);
        this.idSupplier = idSupplier;
    }

    /**
     * 跑一轮对账。远端不可用时整轮跳过：连不上时编造的缺失发现会把账本刷脏。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param ownedRoot       该 KB 的 owned root，列目录不得越出这个前缀
     * @param nowEpochMillis  当前时间
     * @return 本轮发现汇总
     */
    public ReconcileReport runOnce(String knowledgeBaseId, String ownedRoot, long nowEpochMillis) {
        if (!indexPort.ready()) {
            return ReconcileReport.of(List.of());
        }
        ArrayList<ReconcileFinding> seen = new ArrayList<>();
        List<KnowledgeExternalIndexBinding> bindings = bindingsFor(knowledgeBaseId);
        scanLocalToRemote(knowledgeBaseId, bindings, seen, nowEpochMillis);
        scanRemoteToLocal(knowledgeBaseId, ownedRoot, bindings, seen, nowEpochMillis);
        scanControlPlane(knowledgeBaseId, seen, nowEpochMillis);
        return ReconcileReport.of(seen);
    }

    private void scanLocalToRemote(
            String knowledgeBaseId,
            List<KnowledgeExternalIndexBinding> bindings,
            List<ReconcileFinding> seen,
            long nowEpochMillis
    ) {
        int budget = settings.batchSize();
        for (KnowledgeExternalIndexBinding binding : bindings) {
            if (budget <= 0) {
                return;
            }
            if (binding.desiredState() != ExternalKnowledgeDesiredState.PRESENT) {
                continue;
            }
            if (binding.projectionStatus() == ExternalKnowledgeProjectionStatus.IN_SYNC) {
                continue;
            }
            if (!isStale(binding, nowEpochMillis)) {
                continue;
            }
            budget--;
            if (!hasNonTerminalOperation(binding.documentId())) {
                enqueueRebuild(binding, nowEpochMillis);
            }
            ReconcileFindingType type = binding.observedState() == ExternalKnowledgeObservedState.READY
                    ? ReconcileFindingType.STALE_REMOTE
                    : ReconcileFindingType.MISSING_REMOTE;
            seen.add(recordFinding(
                    knowledgeBaseId,
                    type,
                    binding.remoteUri(),
                    binding.documentId(),
                    "local desired PRESENT is not IN_SYNC",
                    ReconcileFindingStatus.OPEN,
                    nowEpochMillis));
        }
    }

    private void scanRemoteToLocal(
            String knowledgeBaseId,
            String ownedRoot,
            List<KnowledgeExternalIndexBinding> bindings,
            List<ReconcileFinding> seen,
            long nowEpochMillis
    ) {
        ExternalTreeListing tree = indexPort.listTree(ownedRoot);
        if (tree.failureClass() != ExternalIndexFailureClass.NONE) {
            return;
        }
        int budget = settings.batchSize();
        for (ExternalTreeListing.Entry entry : tree.entries()) {
            if (budget <= 0) {
                break;
            }
            if (entry.uri().isBlank() || isOwnedRoot(entry.uri(), ownedRoot)) {
                continue;
            }
            if (!ownedByRdBot(entry.owner())) {
                budget--;
                seen.add(recordFinding(
                        knowledgeBaseId,
                        ReconcileFindingType.FOREIGN_OWNER,
                        entry.uri(),
                        "",
                        "remote owner is not rd-bot",
                        ReconcileFindingStatus.OPEN,
                        nowEpochMillis));
                continue;
            }
            if (boundLocally(entry.uri(), bindings)) {
                continue;
            }
            budget--;
            seen.add(recordFinding(
                    knowledgeBaseId,
                    ReconcileFindingType.ORPHAN_REMOTE,
                    entry.uri(),
                    "",
                    "remote resource has no local binding",
                    ReconcileFindingStatus.QUARANTINED,
                    nowEpochMillis));
        }
        int probeBudget = settings.batchSize();
        for (KnowledgeExternalIndexBinding binding : bindings) {
            if (probeBudget <= 0) {
                return;
            }
            if (binding.projectionStatus() != ExternalKnowledgeProjectionStatus.IN_SYNC) {
                continue;
            }
            if (binding.desiredState() != ExternalKnowledgeDesiredState.PRESENT) {
                continue;
            }
            probeBudget--;
            ExternalResourceProbe probe = indexPort.inspectResource(binding.remoteUri());
            if (probe.failureClass() != ExternalIndexFailureClass.NONE) {
                continue;
            }
            if (probe.exists()) {
                continue;
            }
            markDrifted(binding, nowEpochMillis);
            if (!hasNonTerminalOperation(binding.documentId())) {
                enqueueRebuild(binding, nowEpochMillis);
            }
            seen.add(recordFinding(
                    knowledgeBaseId,
                    ReconcileFindingType.MISSING_REMOTE,
                    binding.remoteUri(),
                    binding.documentId(),
                    "IN_SYNC binding is absent remotely",
                    ReconcileFindingStatus.OPEN,
                    nowEpochMillis));
        }
    }

    private void scanControlPlane(
            String knowledgeBaseId,
            List<ReconcileFinding> seen,
            long nowEpochMillis
    ) {
        long expiredBefore = nowEpochMillis - settings.workerLeaseMillis();
        int budget = settings.batchSize();
        for (KnowledgeExternalIndexOperation operation : outboxStore.listAll()) {
            if (budget <= 0) {
                return;
            }
            if (!operation.knowledgeBaseId().equals(knowledgeBaseId)) {
                continue;
            }
            if (operation.status().terminal()) {
                continue;
            }
            if (operation.leaseUntilEpochMillis() <= 0L || operation.leaseUntilEpochMillis() >= expiredBefore) {
                continue;
            }
            budget--;
            seen.add(recordFinding(
                    knowledgeBaseId,
                    ReconcileFindingType.LEASE_EXPIRED,
                    operation.remoteUri(),
                    operation.documentId(),
                    "non-terminal outbox lease is older than the worker lease",
                    ReconcileFindingStatus.OPEN,
                    nowEpochMillis));
        }
    }

    private void enqueueRebuild(KnowledgeExternalIndexBinding binding, long nowEpochMillis) {
        String eventId = idSupplier.get();
        outboxStore.enqueue(new KnowledgeExternalIndexOperation(
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
        ));
    }

    private void markDrifted(KnowledgeExternalIndexBinding binding, long nowEpochMillis) {
        KnowledgeExternalIndexBinding observation = new KnowledgeExternalIndexBinding(
                binding.provider(),
                binding.documentId(),
                binding.knowledgeBaseId(),
                binding.remoteUri(),
                binding.ownershipMarker(),
                binding.desiredState(),
                binding.desiredVersion(),
                binding.desiredChecksum(),
                ExternalKnowledgeObservedState.ABSENT,
                binding.observedVersion(),
                binding.observedChecksum(),
                ExternalKnowledgeProjectionStatus.DRIFTED,
                binding.activeOperationId(),
                binding.remoteTaskId(),
                binding.semanticConfigFingerprint(),
                binding.lastSubmittedAtEpochMillis(),
                nowEpochMillis,
                "MISSING_REMOTE",
                "IN_SYNC binding is absent remotely",
                binding.rowVersion(),
                binding.createdAtEpochMillis(),
                nowEpochMillis);
        ProjectionObservationWriter.write(bindingStore, observation);
    }

    private ReconcileFinding recordFinding(
            String knowledgeBaseId,
            ReconcileFindingType type,
            String remoteUri,
            String documentId,
            String detail,
            ReconcileFindingStatus status,
            long nowEpochMillis
    ) {
        return findingStore.upsert(new ReconcileFinding(
                idSupplier.get(),
                KnowledgeExternalIndexBinding.OPENVIKING,
                knowledgeBaseId,
                type,
                remoteUri,
                documentId,
                detail,
                status,
                nowEpochMillis,
                nowEpochMillis
        ));
    }

    private List<KnowledgeExternalIndexBinding> bindingsFor(String knowledgeBaseId) {
        return bindingStore.listAll().stream()
                .filter(binding -> binding.knowledgeBaseId().equals(knowledgeBaseId))
                .toList();
    }

    private boolean hasNonTerminalOperation(String documentId) {
        return outboxStore.listByDocumentId(documentId).stream()
                .anyMatch(operation -> !operation.status().terminal());
    }

    private boolean isStale(KnowledgeExternalIndexBinding binding, long nowEpochMillis) {
        return nowEpochMillis - binding.updatedAtEpochMillis() >= staleAfterMillis;
    }

    private static boolean ownedByRdBot(String owner) {
        String normalized = owner == null ? "" : owner.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                || normalized.equals(OWNED_OWNER)
                || normalized.startsWith(OWNED_OWNER + ":")
                || normalized.equals("rd.owner=" + OWNED_OWNER);
    }

    private static boolean boundLocally(String remoteUri, List<KnowledgeExternalIndexBinding> bindings) {
        String normalized = stripSlash(remoteUri);
        for (KnowledgeExternalIndexBinding binding : bindings) {
            String bound = stripSlash(binding.remoteUri());
            if (normalized.equals(bound) || normalized.startsWith(bound + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwnedRoot(String uri, String ownedRoot) {
        return stripSlash(uri).equals(stripSlash(ownedRoot));
    }

    private static String stripSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String stripped = value.strip();
        while (stripped.endsWith("/") && stripped.length() > "viking://".length()) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
