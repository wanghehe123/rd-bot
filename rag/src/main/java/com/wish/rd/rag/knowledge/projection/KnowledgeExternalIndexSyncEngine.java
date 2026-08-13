package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexSettleCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeSubmission;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionSettleBundle;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 投影提交侧 Worker：把 Outbox 里尚未发出的写入交给外部索引，只负责"发出去"。
 *
 * <p>发送边界是这个类的全部难点。远端写入不可回滚，所以顺序被固定为
 * 先提交发送意图（{@code remote_operation_id}）、再发 HTTP、最后按结果收口。
 * 中间任何一步崩溃，这一行都会带着发送标记留在库里，
 * {@link KnowledgeExternalIndexOutboxStore#claimBatch} 永远不会再把它交回提交路径，
 * 只能由 Poller 查询远端来收敛。宁可多一次查询，也不重放一次可能已生效的写入。
 *
 * <p>Worker 只写 observed 侧字段。desired 由本地 mutation 事务拥有，
 * 两者在同一行上并发推进，绝不能互相覆盖。
 *
 * <p>WP-3 只实现 {@link ExternalKnowledgeOperationType#UPSERT_DOCUMENT}。
 * 删除类操作会被挂起为 {@code NEEDS_HUMAN}：先做一个可见且不循环的信号，
 * 而不是让它在队列里空转，也不是让一个未验证 ownership 的删除路径提前上线。
 */
public final class KnowledgeExternalIndexSyncEngine {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExternalIndexSyncEngine.class);

    private final ExternalKnowledgeIndexPort indexPort;
    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeExternalIndexBindingStore bindingStore;
    private final KnowledgeDocumentRevisionStore revisionStore;
    private final KnowledgeProjectionSettlePort settlePort;
    private final ProjectionWorkerSettings settings;
    private final Supplier<String> leaseOwnerSupplier;

    public KnowledgeExternalIndexSyncEngine(
            ExternalKnowledgeIndexPort indexPort,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeProjectionSettlePort settlePort,
            ProjectionWorkerSettings settings,
            Supplier<String> leaseOwnerSupplier
    ) {
        this.indexPort = indexPort;
        this.outboxStore = outboxStore;
        this.bindingStore = bindingStore;
        this.revisionStore = revisionStore;
        this.settlePort = settlePort;
        this.settings = settings;
        this.leaseOwnerSupplier = leaseOwnerSupplier;
    }

    /**
     * 跑一轮提交。远端不可用时直接返回，不领取任何行：
     * 领取会消耗提交预算，让一次运维故障把队列推向死信。
     *
     * @param nowEpochMillis 当前时间
     * @return 本轮真正发往远端的操作数
     */
    public int runOnce(long nowEpochMillis) {
        if (!indexPort.ready()) {
            return 0;
        }
        String owner = leaseOwnerSupplier.get();
        List<KnowledgeExternalIndexOperation> claimed = outboxStore.claimBatch(
                owner, nowEpochMillis, nowEpochMillis + settings.workerLeaseMillis(), settings.batchSize());
        int dispatched = 0;
        for (KnowledgeExternalIndexOperation operation : claimed) {
            if (dispatch(operation, owner, nowEpochMillis)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    private boolean dispatch(KnowledgeExternalIndexOperation claimed, String owner, long nowEpochMillis) {
        if (claimed.operationType() != ExternalKnowledgeOperationType.UPSERT_DOCUMENT) {
            park(claimed, owner, nowEpochMillis, "UNSUPPORTED_OPERATION",
                    "operation type is not implemented yet: " + claimed.operationType());
            return false;
        }
        Optional<KnowledgeExternalIndexBinding> found = bindingStore.findByProviderAndDocumentId(
                claimed.provider(), claimed.documentId());
        if (found.isEmpty()) {
            park(claimed, owner, nowEpochMillis, "MISSING_BINDING",
                    "no projection binding for document " + claimed.documentId());
            return false;
        }
        KnowledgeExternalIndexBinding binding = found.get();

        if (binding.desiredVersion() > claimed.syncVersion()
                || binding.desiredState() != ExternalKnowledgeDesiredState.PRESENT) {
            settle(claimed, owner, ExternalIndexSettleCommand.superseded(), null, nowEpochMillis);
            return false;
        }
        if (siblingInFlight(claimed)) {
            settle(claimed, owner, ExternalIndexSettleCommand.deferredLocally(
                            nowEpochMillis + settings.pollBackoffMillis(1),
                            "SIBLING_IN_FLIGHT",
                            "another version of the same document is still awaiting a remote outcome"),
                    null, nowEpochMillis);
            return false;
        }

        ExternalKnowledgeUpsertCommand command;
        try {
            command = buildCommand(claimed, binding);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            park(claimed, owner, nowEpochMillis, "INVALID_PAYLOAD", ex.getMessage());
            return false;
        }

        String sendMarker = claimed.idempotencyKey() + "#" + claimed.attemptCount();
        Optional<KnowledgeExternalIndexOperation> sending = settle(
                claimed,
                owner,
                ExternalIndexSettleCommand.aboutToSend(sendMarker, nowEpochMillis),
                observation(binding, ExternalKnowledgeProjectionStatus.PROCESSING, claimed.eventId(),
                        binding.remoteTaskId(), nowEpochMillis, binding.lastVerifiedAtEpochMillis(), "", "",
                        nowEpochMillis),
                nowEpochMillis);
        if (sending.isEmpty()) {
            return false;
        }

        ExternalKnowledgeSubmission submission = submitSafely(command);
        applyOutcome(sending.get(), binding, command, submission, owner, nowEpochMillis);
        return true;
    }

    private ExternalKnowledgeSubmission submitSafely(ExternalKnowledgeUpsertCommand command) {
        try {
            return indexPort.submitUpsert(command);
        } catch (RuntimeException ex) {
            log.warn("external index submit threw for document {}: {}", command.documentId(), ex.toString());
            return ExternalKnowledgeSubmission.failed(
                    ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT,
                    "ADAPTER_THREW",
                    "the adapter threw before classifying the outcome");
        }
    }

    private void applyOutcome(
            KnowledgeExternalIndexOperation sent,
            KnowledgeExternalIndexBinding binding,
            ExternalKnowledgeUpsertCommand command,
            ExternalKnowledgeSubmission submission,
            String owner,
            long nowEpochMillis
    ) {
        if (submission.accepted()) {
            acceptSubmission(sent, binding, command, submission, owner, nowEpochMillis);
            return;
        }
        switch (submission.failureClass()) {
            case UNKNOWN_REMOTE_RESULT -> settle(sent, owner, ExternalIndexSettleCommand.unknownRemote(
                            nowEpochMillis + settings.pollIntervalMillis(),
                            submission.errorCode(), submission.errorMessage()),
                    observation(binding, ExternalKnowledgeProjectionStatus.PROCESSING, sent.eventId(),
                            binding.remoteTaskId(), nowEpochMillis, binding.lastVerifiedAtEpochMillis(),
                            submission.errorCode(), submission.errorMessage(), nowEpochMillis),
                    nowEpochMillis);
            case RETRYABLE_NOT_SENT, RETRYABLE, RETRYABLE_BUSY -> retryOrDeadLetter(
                    sent, binding, submission, owner, nowEpochMillis);
            case CONFIGURATION_BLOCKED, MALFORMED_SUCCESS, FOREIGN -> park(
                    sent, owner, nowEpochMillis, submission.errorCode(), submission.errorMessage());
            default -> settle(sent, owner, ExternalIndexSettleCommand.deadLetter(
                            submission.errorCode(), submission.errorMessage()),
                    observation(binding, ExternalKnowledgeProjectionStatus.DEAD_LETTER, "",
                            binding.remoteTaskId(), binding.lastSubmittedAtEpochMillis(),
                            binding.lastVerifiedAtEpochMillis(),
                            submission.errorCode(), submission.errorMessage(), nowEpochMillis),
                    nowEpochMillis);
        }
    }

    private void acceptSubmission(
            KnowledgeExternalIndexOperation sent,
            KnowledgeExternalIndexBinding binding,
            ExternalKnowledgeUpsertCommand command,
            ExternalKnowledgeSubmission submission,
            String owner,
            long nowEpochMillis
    ) {
        if (!command.resourceRootUri().equals(submission.rootUri())) {
            park(sent, owner, nowEpochMillis, "ROOT_URI_MISMATCH",
                    "the remote accepted a different resource root than the one requested");
            return;
        }
        if (submission.remoteTaskId().isBlank()) {
            park(sent, owner, nowEpochMillis, "MISSING_TASK_ID",
                    "the remote accepted the request without a task id, so the outcome is unobservable");
            return;
        }
        settle(sent, owner,
                ExternalIndexSettleCommand.waitingRemote(
                        submission.remoteTaskId(), nowEpochMillis + settings.pollIntervalMillis()),
                observation(binding, ExternalKnowledgeProjectionStatus.PROCESSING, sent.eventId(),
                        submission.remoteTaskId(), nowEpochMillis, binding.lastVerifiedAtEpochMillis(), "", "",
                        nowEpochMillis),
                nowEpochMillis);
    }

    private void retryOrDeadLetter(
            KnowledgeExternalIndexOperation sent,
            KnowledgeExternalIndexBinding binding,
            ExternalKnowledgeSubmission submission,
            String owner,
            long nowEpochMillis
    ) {
        if (sent.attemptCount() >= sent.maxAttempts()) {
            settle(sent, owner,
                    ExternalIndexSettleCommand.deadLetter(submission.errorCode(), submission.errorMessage()),
                    observation(binding, ExternalKnowledgeProjectionStatus.DEAD_LETTER, "",
                            binding.remoteTaskId(), binding.lastSubmittedAtEpochMillis(),
                            binding.lastVerifiedAtEpochMillis(),
                            submission.errorCode(), submission.errorMessage(), nowEpochMillis),
                    nowEpochMillis);
            return;
        }
        settle(sent, owner,
                ExternalIndexSettleCommand.retryWait(
                        nowEpochMillis + settings.submitBackoffMillis(sent.attemptCount()),
                        submission.errorCode(), submission.errorMessage()),
                observation(binding, ExternalKnowledgeProjectionStatus.PENDING, "",
                        binding.remoteTaskId(), binding.lastSubmittedAtEpochMillis(),
                        binding.lastVerifiedAtEpochMillis(),
                        submission.errorCode(), submission.errorMessage(), nowEpochMillis),
                nowEpochMillis);
    }

    private void park(
            KnowledgeExternalIndexOperation operation,
            String owner,
            long nowEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        String code = errorCode == null || errorCode.isBlank() ? "NEEDS_HUMAN" : errorCode;
        String message = errorMessage == null || errorMessage.isBlank() ? code : errorMessage;
        KnowledgeExternalIndexBinding observation = bindingStore
                .findByProviderAndDocumentId(operation.provider(), operation.documentId())
                .map(binding -> observation(binding, ExternalKnowledgeProjectionStatus.NEEDS_HUMAN, "",
                        binding.remoteTaskId(), binding.lastSubmittedAtEpochMillis(),
                        binding.lastVerifiedAtEpochMillis(), code, message, nowEpochMillis))
                .orElse(null);
        settle(operation, owner, ExternalIndexSettleCommand.needsHuman(code, message), observation, nowEpochMillis);
    }

    private Optional<KnowledgeExternalIndexOperation> settle(
            KnowledgeExternalIndexOperation expected,
            String owner,
            ExternalIndexSettleCommand command,
            KnowledgeExternalIndexBinding observation,
            long nowEpochMillis
    ) {
        Optional<KnowledgeExternalIndexOperation> settled = settlePort.settle(new ProjectionSettleBundle(
                expected.eventId(),
                expected.status(),
                owner,
                expected.rowVersion(),
                command,
                observation,
                nowEpochMillis));
        if (settled.isEmpty()) {
            log.info("projection settle lost the CAS race, another owner holds {}", expected.eventId());
        }
        return settled;
    }

    private boolean siblingInFlight(KnowledgeExternalIndexOperation operation) {
        return outboxStore.listByDocumentId(operation.documentId()).stream()
                .filter(candidate -> !candidate.eventId().equals(operation.eventId()))
                .filter(candidate -> candidate.provider().equals(operation.provider()))
                .anyMatch(candidate -> candidate.status().awaitingRemoteOutcome());
    }

    private ExternalKnowledgeUpsertCommand buildCommand(
            KnowledgeExternalIndexOperation operation,
            KnowledgeExternalIndexBinding binding
    ) {
        KnowledgeDocumentRevision revision = revisionStore.findById(operation.revisionId())
                .or(() -> revisionStore.findByDocumentIdAndChecksum(
                        operation.documentId(), operation.checksum()))
                .orElseThrow(() -> new IllegalStateException(
                        "the frozen revision behind this operation is gone: " + operation.revisionId()));
        if (!revision.checksum().equals(operation.checksum())) {
            throw new IllegalStateException("the revision checksum does not match the queued operation");
        }
        String root = operation.remoteUri().isBlank() ? binding.remoteUri() : operation.remoteUri();
        if (!root.equals(binding.remoteUri())) {
            throw new IllegalStateException("the queued resource root drifted away from the binding");
        }
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(root, OpenVikingProjectionUris.OWNED_ROOT)) {
            throw new IllegalStateException("the resource root is outside the owned namespace");
        }
        return new ExternalKnowledgeUpsertCommand(
                operation.provider(),
                operation.knowledgeBaseId(),
                operation.documentId(),
                operation.syncVersion(),
                operation.checksum(),
                root,
                OpenVikingProjectionUris.SOURCE_FILE_NAME,
                revision.canonicalContent(),
                OpenVikingProjectionUris.ownershipMarker(operation.knowledgeBaseId(), operation.documentId()),
                OpenVikingProjectionUris.ownershipTags(
                        operation.knowledgeBaseId(), operation.documentId(),
                        operation.syncVersion(), operation.checksum()),
                operation.idempotencyKey());
    }

    /**
     * 构造只含 observed 侧字段的绑定快照。desired 三列取当前值原样带回，
     * 由 Store 的 CAS 写入丢弃，绝不参与 SET 子句。
     */
    private static KnowledgeExternalIndexBinding observation(
            KnowledgeExternalIndexBinding current,
            ExternalKnowledgeProjectionStatus projectionStatus,
            String activeOperationId,
            String remoteTaskId,
            long lastSubmittedAtEpochMillis,
            long lastVerifiedAtEpochMillis,
            String errorCode,
            String errorMessage,
            long nowEpochMillis
    ) {
        return new KnowledgeExternalIndexBinding(
                current.provider(),
                current.documentId(),
                current.knowledgeBaseId(),
                current.remoteUri(),
                current.ownershipMarker(),
                current.desiredState(),
                current.desiredVersion(),
                current.desiredChecksum(),
                current.observedState() == null ? ExternalKnowledgeObservedState.UNKNOWN : current.observedState(),
                current.observedVersion(),
                current.observedChecksum(),
                projectionStatus,
                activeOperationId,
                remoteTaskId,
                current.semanticConfigFingerprint(),
                lastSubmittedAtEpochMillis,
                lastVerifiedAtEpochMillis,
                errorCode,
                errorMessage,
                current.rowVersion(),
                current.createdAtEpochMillis(),
                nowEpochMillis);
    }
}
