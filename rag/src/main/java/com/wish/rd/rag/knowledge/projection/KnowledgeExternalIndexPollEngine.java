package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexSettleCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;
import com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionSettleBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 投影查询侧 Poller：只读远端，把已经发出去的写入收敛成可判定的结果。
 *
 * <p>它拥有所有越过发送边界的行，是这些行唯一的推进者。三条硬规则：
 *
 * <ul>
 *   <li>永不重发。UNKNOWN 只能靠 {@code verifyResource} 这类只读查询收敛。</li>
 *   <li>{@code task completed} 不等于检索就绪。只有版本核验通过，
 *       才允许把 {@code observed_version} 推到 desired。</li>
 *   <li>晚完成的低版本 upsert 若发现 desired 已是 ABSENT 或更高版本，只能记
 *       {@code DRIFTED}/{@code SUPERSEDED}，永远不得把已删除文档写回 {@code IN_SYNC}。</li>
 * </ul>
 *
 * <p>租约是每轮一把的短租约，settle 后立刻释放；崩溃最多让一行等一个租约周期。
 */
public final class KnowledgeExternalIndexPollEngine {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExternalIndexPollEngine.class);

    private final ExternalKnowledgeIndexPort indexPort;
    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeExternalIndexBindingStore bindingStore;
    private final KnowledgeProjectionSettlePort settlePort;
    private final ProjectionWorkerSettings settings;
    private final Supplier<String> leaseOwnerSupplier;

    public KnowledgeExternalIndexPollEngine(
            ExternalKnowledgeIndexPort indexPort,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeProjectionSettlePort settlePort,
            ProjectionWorkerSettings settings,
            Supplier<String> leaseOwnerSupplier
    ) {
        this.indexPort = indexPort;
        this.outboxStore = outboxStore;
        this.bindingStore = bindingStore;
        this.settlePort = settlePort;
        this.settings = settings;
        this.leaseOwnerSupplier = leaseOwnerSupplier;
    }

    /**
     * 跑一轮查询。远端不可用时整轮跳过：轮询一个连不上的服务问不出任何结论，
     * 却会给每一行写一次 {@code row_version}，把写放大压到本地 mutation 提交的同一张表上。
     *
     * @param nowEpochMillis 当前时间
     * @return 本轮核验通过、收敛为 IN_SYNC 的操作数
     */
    public int runOnce(long nowEpochMillis) {
        if (!indexPort.ready()) {
            return 0;
        }
        String owner = leaseOwnerSupplier.get();
        List<KnowledgeExternalIndexOperation> claimed = outboxStore.claimPollBatch(
                owner, nowEpochMillis, nowEpochMillis + settings.pollLeaseMillis(), settings.batchSize());
        int converged = 0;
        for (KnowledgeExternalIndexOperation operation : claimed) {
            if (advance(operation, owner, nowEpochMillis)) {
                converged++;
            }
        }
        return converged;
    }

    private boolean advance(KnowledgeExternalIndexOperation claimed, String owner, long nowEpochMillis) {
        Optional<KnowledgeExternalIndexBinding> found = bindingStore.findByProviderAndDocumentId(
                claimed.provider(), claimed.documentId());
        if (isDelete(claimed)) {
            return convergeDelete(claimed, found.orElse(null), owner, nowEpochMillis);
        }
        if (found.isEmpty()) {
            park(claimed, null, owner, nowEpochMillis, "MISSING_BINDING",
                    "no projection binding for document " + claimed.documentId());
            return false;
        }
        KnowledgeExternalIndexBinding binding = found.get();
        if (claimed.remoteTaskId().isBlank()) {
            return convergeByVerification(claimed, binding, owner, nowEpochMillis, false);
        }
        return inspectTask(claimed, binding, owner, nowEpochMillis);
    }

    /**
     * 删除只靠 {@code inspectResource} 确认缺席。资源还在就继续等，
     * 查询失败只推迟、不写观测——一次 attrs 故障不能把墓碑提前标成已删除。
     */
    private boolean convergeDelete(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            String owner,
            long nowEpochMillis
    ) {
        if (binding == null && claimed.operationType() != ExternalKnowledgeOperationType.DELETE_KNOWLEDGE_BASE) {
            park(claimed, null, owner, nowEpochMillis, "MISSING_BINDING",
                    "no projection binding for document " + claimed.documentId());
            return false;
        }
        String remoteUri = binding == null || binding.remoteUri().isBlank()
                ? claimed.remoteUri()
                : binding.remoteUri();
        ExternalResourceProbe probe;
        try {
            probe = indexPort.inspectResource(remoteUri);
        } catch (RuntimeException ex) {
            log.warn("resource inspect threw for {}: {}", remoteUri, ex.toString());
            defer(claimed, binding, owner, nowEpochMillis, "INSPECT_THREW", "the adapter threw while probing");
            return false;
        }
        if (!probe.failureClass().success()) {
            defer(claimed, binding, owner, nowEpochMillis, probe.errorCode(), probe.errorMessage());
            return false;
        }
        if (probe.exists()) {
            defer(claimed, binding, owner, nowEpochMillis, "", "");
            return false;
        }
        KnowledgeExternalIndexBinding observation = binding == null
                ? null
                : observation(
                        binding,
                        ExternalKnowledgeObservedState.ABSENT,
                        0L,
                        "",
                        ExternalKnowledgeProjectionStatus.DELETED,
                        "",
                        "",
                        nowEpochMillis,
                        "",
                        "",
                        nowEpochMillis);
        return settle(claimed, owner, ExternalIndexSettleCommand.succeeded(), observation, nowEpochMillis)
                .isPresent();
    }

    private boolean inspectTask(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            String owner,
            long nowEpochMillis
    ) {
        ExternalKnowledgeTaskSnapshot snapshot;
        try {
            snapshot = indexPort.inspectTask(claimed.remoteTaskId());
        } catch (RuntimeException ex) {
            log.warn("task inspect threw for {}: {}", claimed.remoteTaskId(), ex.toString());
            defer(claimed, binding, owner, nowEpochMillis, "INSPECT_THREW", "the adapter threw while polling");
            return false;
        }
        if (!snapshot.failureClass().success()) {
            defer(claimed, binding, owner, nowEpochMillis, snapshot.errorCode(), snapshot.errorMessage());
            return false;
        }
        return switch (snapshot.state()) {
            case COMPLETED -> completeTask(claimed, binding, snapshot, owner, nowEpochMillis);
            case FAILED -> {
                retryOrDeadLetter(claimed, binding, owner, nowEpochMillis,
                        blankTo(snapshot.errorCode(), "REMOTE_TASK_FAILED"),
                        blankTo(snapshot.errorMessage(), "the remote indexing task reported failure"));
                yield false;
            }
            case NOT_FOUND -> convergeByVerification(claimed, binding, owner, nowEpochMillis, false);
            default -> {
                defer(claimed, binding, owner, nowEpochMillis, "", "");
                yield false;
            }
        };
    }

    private boolean completeTask(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            ExternalKnowledgeTaskSnapshot snapshot,
            String owner,
            long nowEpochMillis
    ) {
        if (!snapshot.completedCleanly()) {
            park(claimed, binding, owner, nowEpochMillis, "TASK_COMPLETED_WITH_ERRORS",
                    "stage=" + snapshot.stage()
                            + " semanticErrors=" + snapshot.semanticErrorCount()
                            + " embeddingErrors=" + snapshot.embeddingErrorCount());
            return false;
        }
        Optional<KnowledgeExternalIndexOperation> verifying = settle(
                claimed, owner, ExternalIndexSettleCommand.verifying(), null, nowEpochMillis);
        if (verifying.isEmpty()) {
            return false;
        }
        return convergeByVerification(verifying.get(), binding, owner, nowEpochMillis, true);
    }

    /**
     * 只读收敛。{@code afterCleanTask} 决定核验不符时的处置：远端已经宣称完成却对不上，
     * 是契约破裂，等下去不会变好；未知结果的阶梯则允许在墙钟截止前继续等。
     */
    private boolean convergeByVerification(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            String owner,
            long nowEpochMillis,
            boolean afterCleanTask
    ) {
        ExternalKnowledgeVerification verification;
        try {
            verification = indexPort.verifyResource(new ExternalKnowledgeVersionMarker(
                    claimed.provider(),
                    binding.remoteUri(),
                    binding.ownershipMarker(),
                    claimed.knowledgeBaseId(),
                    claimed.documentId(),
                    claimed.syncVersion(),
                    claimed.checksum()));
        } catch (RuntimeException ex) {
            log.warn("verification threw for document {}: {}", claimed.documentId(), ex.toString());
            defer(claimed, binding, owner, nowEpochMillis, "VERIFY_THREW", "the adapter threw while verifying");
            return false;
        }
        if (verification.verified()) {
            return markInSync(claimed, binding, owner, nowEpochMillis);
        }
        boolean answered = verification.failureClass() == ExternalIndexFailureClass.MALFORMED_SUCCESS;
        if (afterCleanTask && answered) {
            park(claimed, binding, owner, nowEpochMillis, verification.errorCode(),
                    "failed checks: " + String.join(",", verification.failedChecks()));
            return false;
        }
        defer(claimed, binding, owner, nowEpochMillis, verification.errorCode(), verification.errorMessage());
        return false;
    }

    private boolean markInSync(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            String owner,
            long nowEpochMillis
    ) {
        boolean superseded = binding.desiredState() == ExternalKnowledgeDesiredState.ABSENT
                || binding.desiredVersion() > claimed.syncVersion();
        if (superseded) {
            KnowledgeExternalIndexBinding observation = observation(
                    binding,
                    ExternalKnowledgeObservedState.DRIFTED,
                    claimed.syncVersion(),
                    claimed.checksum(),
                    ExternalKnowledgeProjectionStatus.DRIFTED,
                    "",
                    claimed.remoteTaskId(),
                    nowEpochMillis,
                    "",
                    "",
                    nowEpochMillis);
            return settle(claimed, owner, ExternalIndexSettleCommand.superseded(), observation, nowEpochMillis)
                    .isPresent();
        }
        KnowledgeExternalIndexBinding observation = observation(
                binding,
                ExternalKnowledgeObservedState.READY,
                claimed.syncVersion(),
                claimed.checksum(),
                ExternalKnowledgeProjectionStatus.IN_SYNC,
                "",
                claimed.remoteTaskId(),
                nowEpochMillis,
                "",
                "",
                nowEpochMillis);
        return settle(claimed, owner, ExternalIndexSettleCommand.succeeded(), observation, nowEpochMillis)
                .isPresent();
    }

    /**
     * 推迟到下一轮。同时守住墙钟截止：一个永远给不出结论的远端不能无限占着队列。
     */
    private void defer(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            String owner,
            long nowEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        long anchor = claimed.publishedAtEpochMillis() > 0L
                ? claimed.publishedAtEpochMillis()
                : claimed.createdAtEpochMillis();
        long age = Math.max(0L, nowEpochMillis - anchor);
        if (age >= settings.unknownOutcomeTimeoutMillis()) {
            park(claimed, binding, owner, nowEpochMillis, "REMOTE_OUTCOME_TIMEOUT",
                    "the remote never produced a verifiable outcome within the deadline");
            return;
        }
        long nextVisibleAt = nowEpochMillis + settings.pollIntervalForAge(age);
        ExternalIndexSettleCommand command;
        if (isDelete(claimed)) {
            command = ExternalIndexSettleCommand.stillVerifying(nextVisibleAt, errorCode, errorMessage);
        } else if (claimed.remoteTaskId().isBlank()) {
            command = ExternalIndexSettleCommand.unknownRemote(nextVisibleAt, errorCode, errorMessage);
        } else {
            command = ExternalIndexSettleCommand.stillWaiting(nextVisibleAt, errorCode, errorMessage);
        }
        settle(claimed, owner, command, null, nowEpochMillis);
    }

    /**
     * 远端任务终态失败时的重投。此时远端没有任何东西在途，
     * 而目标 URI 由 (kbId, docId) 确定性生成，重投是对同一资源的覆盖写，
     * 因此清除发送标记交回提交侧是安全的；预算耗尽则进死信。
     */
    private void retryOrDeadLetter(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            String owner,
            long nowEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        if (claimed.attemptCount() >= claimed.maxAttempts()) {
            settle(claimed, owner, ExternalIndexSettleCommand.deadLetter(errorCode, errorMessage),
                    observation(binding, binding.observedState(), binding.observedVersion(),
                            binding.observedChecksum(), ExternalKnowledgeProjectionStatus.DEAD_LETTER, "",
                            "", binding.lastVerifiedAtEpochMillis(), errorCode, errorMessage, nowEpochMillis),
                    nowEpochMillis);
            return;
        }
        settle(claimed, owner,
                ExternalIndexSettleCommand.retryWait(
                        nowEpochMillis + settings.submitBackoffMillis(claimed.attemptCount()),
                        errorCode, errorMessage),
                observation(binding, binding.observedState(), binding.observedVersion(),
                        binding.observedChecksum(), ExternalKnowledgeProjectionStatus.PENDING, "",
                        "", binding.lastVerifiedAtEpochMillis(), errorCode, errorMessage, nowEpochMillis),
                nowEpochMillis);
    }

    private void park(
            KnowledgeExternalIndexOperation claimed,
            KnowledgeExternalIndexBinding binding,
            String owner,
            long nowEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        String code = blankTo(errorCode, "NEEDS_HUMAN");
        String message = blankTo(errorMessage, code);
        KnowledgeExternalIndexBinding observation = binding == null ? null : observation(
                binding, binding.observedState(), binding.observedVersion(), binding.observedChecksum(),
                ExternalKnowledgeProjectionStatus.NEEDS_HUMAN, "", claimed.remoteTaskId(),
                binding.lastVerifiedAtEpochMillis(), code, message, nowEpochMillis);
        settle(claimed, owner, ExternalIndexSettleCommand.needsHuman(code, message), observation, nowEpochMillis);
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
            log.info("projection poll settle lost the CAS race for {}", expected.eventId());
        }
        return settled;
    }

    private static KnowledgeExternalIndexBinding observation(
            KnowledgeExternalIndexBinding current,
            ExternalKnowledgeObservedState observedState,
            long observedVersion,
            String observedChecksum,
            ExternalKnowledgeProjectionStatus projectionStatus,
            String activeOperationId,
            String remoteTaskId,
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
                observedState,
                observedVersion,
                observedChecksum,
                projectionStatus,
                activeOperationId,
                remoteTaskId,
                current.semanticConfigFingerprint(),
                current.lastSubmittedAtEpochMillis(),
                lastVerifiedAtEpochMillis,
                errorCode,
                errorMessage,
                current.rowVersion(),
                current.createdAtEpochMillis(),
                nowEpochMillis);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isDelete(KnowledgeExternalIndexOperation operation) {
        return operation.operationType() == ExternalKnowledgeOperationType.DELETE_DOCUMENT
                || operation.operationType() == ExternalKnowledgeOperationType.DELETE_KNOWLEDGE_BASE;
    }
}
