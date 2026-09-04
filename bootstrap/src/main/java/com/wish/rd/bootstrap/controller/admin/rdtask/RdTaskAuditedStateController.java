package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.audit.CompletionBinding;
import com.wish.rd.engine.requirement.audit.EvidenceRef;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Task-scoped read-only API for Host-owned audited state and audit runs.
 *
 * <p>JSON fields are frozen for the admin frontend. Responses never include
 * {@code promptSnapshot} / {@code executionResultJson}.
 */
@RestController
@ConditionalOnBean(AuditedTaskStateStore.class)
public class RdTaskAuditedStateController {

    private final RagStreamTaskRegistry registry;
    private final AuditedTaskStateStore store;

    /**
     * Creates the read-only admin adapter.
     *
     * @param registry task registry used to 404 unknown or cross-project tasks
     * @param store    Host-owned audited state
     */
    public RdTaskAuditedStateController(RagStreamTaskRegistry registry, AuditedTaskStateStore store) {
        this.registry = registry;
        this.store = store;
    }

    /**
     * Returns the current audited-state head for a requirement task.
     *
     * @param taskId    owning RD task id
     * @param projectId optional project scope; mismatch is 404
     * @return frozen head view, empty when the task has no head yet
     */
    @GetMapping("/admin/rd-tasks/{taskId}/audited-state")
    public AuditedTaskStateView auditedState(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "projectId", required = false) String projectId
    ) {
        RdRequirementTask task = requireRequirementTask(taskId, projectId);
        Optional<AuditedTaskState> head = store.head(task.taskId())
                .filter(state -> task.taskId().equals(state.taskId()));
        Optional<CompletionBinding> binding = store.completionBinding(task.taskId())
                .filter(candidate -> task.taskId().equals(candidate.taskId()));
        return head.map(state -> toStateView(task, state, binding.orElse(null)))
                .orElseGet(() -> emptyStateView(task));
    }

    /**
     * Lists audit runs owned by the task.
     *
     * @param taskId    owning RD task id
     * @param projectId optional project scope; mismatch is 404
     * @return envelope with {@code taskId} and {@code runs}
     */
    @GetMapping("/admin/rd-tasks/{taskId}/audit-runs")
    public AuditRunListView auditRuns(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "projectId", required = false) String projectId
    ) {
        RdRequirementTask task = requireRequirementTask(taskId, projectId);
        List<AuditRunView> runs = store.listAuditRuns(task.taskId()).stream()
                .filter(run -> task.taskId().equals(run.taskId()))
                .map(RdTaskAuditedStateController::toRunView)
                .toList();
        return new AuditRunListView(task.taskId(), task.projectId(), runs);
    }

    private RdRequirementTask requireRequirementTask(String taskId, String projectId) {
        RdTask task;
        try {
            task = registry.getTask(taskId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "audited state is requirement-only");
        }
        String requestedProject = projectId == null ? "" : projectId.strip();
        if (!requestedProject.isBlank() && !requestedProject.equals(requirementTask.projectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "audited state not found");
        }
        return requirementTask;
    }

    private static AuditedTaskStateView emptyStateView(RdRequirementTask task) {
        return new AuditedTaskStateView(
                task.taskId(),
                task.projectId(),
                false,
                0L,
                "",
                "",
                List.of(),
                null,
                null
        );
    }

    private static AuditedTaskStateView toStateView(
            RdRequirementTask task,
            AuditedTaskState state,
            CompletionBinding binding
    ) {
        return new AuditedTaskStateView(
                task.taskId(),
                task.projectId(),
                true,
                state.stateVersion(),
                state.stateHash(),
                state.lastAuditRunId(),
                state.records().stream().map(RdTaskAuditedStateController::toRecordView).toList(),
                toContractRefView(state.contractRef()),
                binding == null ? null : new CompletionBindingView(
                        binding.auditRunId(),
                        binding.stateVersion(),
                        binding.stateHash()
                )
        );
    }

    private static AuditedRecordView toRecordView(AuditedRecord record) {
        return new AuditedRecordView(
                record.id(),
                record.kind().name(),
                record.blocking(),
                record.text(),
                record.status().name(),
                record.evidenceRefs().stream().map(RdTaskAuditedStateController::toEvidenceView).toList(),
                record.sourceStageRunId(),
                record.blockedReason()
        );
    }

    private static EvidenceRefView toEvidenceView(EvidenceRef ref) {
        return new EvidenceRefView(ref.auditRunId(), ref.sourceKind().name(), ref.uri(), ref.sha256());
    }

    private static ContractRefView toContractRefView(AuditedContractRef contractRef) {
        return new ContractRefView(
                contractRef.acceptanceCriteriaHash(),
                contractRef.taskVersionAtFreeze(),
                contractRef.fencingTokenAtFreeze()
        );
    }

    private static AuditRunView toRunView(AuditRun run) {
        return new AuditRunView(
                run.auditRunId(),
                run.subjectStageRunId(),
                run.subjectRole(),
                run.commandId(),
                run.completion().name(),
                run.integrity().name(),
                run.contractAudit().name(),
                run.verified(),
                run.missing(),
                run.untrusted(),
                run.blockers(),
                run.sourceRefs(),
                run.createdAtEpochMillis()
        );
    }

    /**
     * Frozen audited-state envelope.
     */
    public record AuditedTaskStateView(
            String taskId,
            String projectId,
            boolean present,
            long stateVersion,
            String stateHash,
            String lastAuditRunId,
            List<AuditedRecordView> records,
            ContractRefView contractRef,
            CompletionBindingView completionBinding
    ) {
    }

    /**
     * Frozen audited record.
     */
    public record AuditedRecordView(
            String id,
            String kind,
            boolean blocking,
            String text,
            String status,
            List<EvidenceRefView> evidenceRefs,
            String sourceStageRunId,
            String blockedReason
    ) {
    }

    /**
     * Frozen evidence pointer. Durable URI only; no prompt/result payloads.
     */
    public record EvidenceRefView(
            String auditRunId,
            String sourceKind,
            String uri,
            String sha256
    ) {
    }

    /**
     * Frozen contract identity.
     */
    public record ContractRefView(
            String acceptanceCriteriaHash,
            long taskVersionAtFreeze,
            long fencingTokenAtFreeze
    ) {
    }

    /**
     * Frozen completion binding.
     */
    public record CompletionBindingView(
            String auditRunId,
            long stateVersion,
            String stateHash
    ) {
    }

    /**
     * List envelope for {@code GET /admin/rd-tasks/{taskId}/audit-runs}.
     */
    public record AuditRunListView(
            String taskId,
            String projectId,
            List<AuditRunView> runs
    ) {
    }

    /**
     * Frozen audit-run view.
     */
    public record AuditRunView(
            String auditRunId,
            String subjectStageRunId,
            String subjectRole,
            String commandId,
            String completion,
            String integrity,
            String contractAudit,
            List<String> verified,
            List<String> missing,
            List<String> untrusted,
            List<String> blockers,
            List<String> sourceRefs,
            long createdAtEpochMillis
    ) {
    }
}
