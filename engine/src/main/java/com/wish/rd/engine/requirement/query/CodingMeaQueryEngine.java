package com.wish.rd.engine.requirement.query;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.EvidenceRef;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.manager.ManagerDecideStages;
import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationRound;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure projection from a Coding MEA snapshot onto the frozen read DTO.
 *
 * <p>Does not call stores, finalize, or {@code ManagerPolicy.decide}.
 */
@Service
public class CodingMeaQueryEngine {

    static final String NO_CODING_STAGE = "NO_CODING_STAGE";
    static final String REVISION_UNAVAILABLE = "REVISION_UNAVAILABLE";
    static final String OUTSIDE_PAGE = "OUTSIDE_PAGE";
    static final String NO_UNIQUE_STAGE_BINDING = "NO_UNIQUE_STAGE_BINDING";
    static final String CROSS_TASK_REFUSED = "CROSS_TASK_REFUSED";
    static final String HOST_ONLY_COMMAND = "HOST_ONLY_COMMAND";

    /**
     * Projects one snapshot.
     *
     * @param snapshot consistent raw references
     * @return frozen response
     */
    public CodingMeaResponse query(CodingMeaSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        CodingMeaResponse.StateSlice head = toStateSlice(snapshot.head(), null);
        if (snapshot.codingStageMissing()) {
            return new CodingMeaResponse(
                    CodingMeaResponse.SCHEMA_VERSION,
                    snapshot.taskId(),
                    null,
                    false,
                    NO_CODING_STAGE,
                    snapshot.snapshotReadAtEpochMillis(),
                    snapshot.taskVersion(),
                    snapshot.taskStatus(),
                    snapshot.paused(),
                    head,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new CodingMeaResponse.Page(false, null)
            );
        }
        Map<String, AgentStageRun> stagesById = indexStages(snapshot);
        Map<String, RequirementStageCommand> pageById = new HashMap<>();
        for (RequirementStageCommand command : snapshot.pageCommands()) {
            pageById.put(command.commandId(), command);
        }
        Set<String> preferredRecordIds = new HashSet<>();
        for (ManagerDecision decision : snapshot.decisions()) {
            preferredRecordIds.addAll(decision.targetRecordIds());
        }
        List<CodingMeaResponse.CommandReference> commands = snapshot.pageCommands().stream()
                .map(command -> toCommand(snapshot, command, stagesById))
                .toList();
        List<CodingMeaResponse.DecisionReference> decisions = snapshot.decisions().stream()
                .map(decision -> toDecision(snapshot, decision, pageById, preferredRecordIds))
                .toList();
        List<CodingMeaResponse.MeaLink> links = buildLinks(snapshot, stagesById, pageById);
        return new CodingMeaResponse(
                CodingMeaResponse.SCHEMA_VERSION,
                snapshot.taskId(),
                blankToNull(snapshot.selectedCodingStageRunId()),
                true,
                null,
                snapshot.snapshotReadAtEpochMillis(),
                snapshot.taskVersion(),
                snapshot.taskStatus(),
                snapshot.paused(),
                toStateSlice(snapshot.head(), preferredRecordIds),
                stages(snapshot, AgentRole.CODING_AGENT),
                stages(snapshot, AgentRole.QA_AGENT),
                commands,
                decisions,
                snapshot.remediations().stream().map(this::toRemediation).toList(),
                snapshot.hostVerifications().stream().map(this::toHost).toList(),
                snapshot.auditRuns().stream().map(this::toAudit).toList(),
                links,
                new CodingMeaResponse.Page(
                        snapshot.commandsHasMore(),
                        blankToNull(snapshot.nextCursor())
                )
        );
    }

    private Map<String, AgentStageRun> indexStages(CodingMeaSnapshot snapshot) {
        Map<String, AgentStageRun> byId = new HashMap<>();
        for (AgentStageRun stage : snapshot.stages()) {
            if (!snapshot.taskId().equals(stage.taskId())) {
                continue;
            }
            byId.put(stage.stageRunId(), stage);
        }
        return byId;
    }

    private List<CodingMeaResponse.StageReference> stages(CodingMeaSnapshot snapshot, AgentRole role) {
        return snapshot.stages().stream()
                .filter(stage -> stage.role() == role && snapshot.taskId().equals(stage.taskId()))
                .sorted(Comparator.comparingInt(AgentStageRun::attemptNo)
                        .thenComparing(AgentStageRun::stageRunId))
                .map(this::toStage)
                .toList();
    }

    private CodingMeaResponse.StageReference toStage(AgentStageRun stage) {
        return new CodingMeaResponse.StageReference(
                stage.stageRunId(),
                stage.role().name(),
                stage.attemptNo(),
                stage.status().name(),
                blankToNull(stage.resultArtifactId()),
                zeroToNull(stage.startedAtEpochMillis()),
                zeroToNull(stage.finishedAtEpochMillis())
        );
    }

    private CodingMeaResponse.CommandReference toCommand(
            CodingMeaSnapshot snapshot,
            RequirementStageCommand command,
            Map<String, AgentStageRun> stagesById
    ) {
        StageLink link = linkCommandToStage(snapshot, command, stagesById);
        return new CodingMeaResponse.CommandReference(
                command.commandId(),
                command.stage(),
                command.role(),
                command.status().name(),
                link.stageRunId(),
                link.reason(),
                command.attemptNo(),
                blankToNull(command.remediationRoundId()),
                command.remediationKind() == null ? null : command.remediationKind().name(),
                command.remediationNo() <= 0 ? null : command.remediationNo(),
                blankToNull(command.remediationSourceStageRunId()),
                command.createdAtEpochMillis(),
                command.updatedAtEpochMillis()
        );
    }

    private StageLink linkCommandToStage(
            CodingMeaSnapshot snapshot,
            RequirementStageCommand command,
            Map<String, AgentStageRun> stagesById
    ) {
        if (!snapshot.taskId().equals(command.taskId())) {
            return new StageLink(null, CROSS_TASK_REFUSED);
        }
        List<String> candidates = new ArrayList<>();
        for (AuditRun run : snapshot.auditRuns()) {
            if (run.commandId().equals(command.commandId()) && !run.subjectStageRunId().isBlank()) {
                candidates.add(run.subjectStageRunId());
            }
        }
        if (!command.targetRetryBindingId().isBlank()) {
            for (TaskRetryAttemptBinding binding : snapshot.retryBindings()) {
                if (binding.bindingId().equals(command.targetRetryBindingId())
                        && binding.kind() == TaskRetryAttemptKind.AGENT_STAGE) {
                    candidates.add(binding.attemptId());
                }
            }
        }
        if (!command.remediationSourceStageRunId().isBlank()) {
            candidates.add(command.remediationSourceStageRunId());
        }
        for (AgentRemediationRound round : snapshot.remediations()) {
            if (round.roundId().equals(command.remediationRoundId())) {
                if (!round.targetCodingStageRunId().isBlank()) {
                    candidates.add(round.targetCodingStageRunId());
                }
                if (!round.targetQaStageRunId().isBlank()) {
                    candidates.add(round.targetQaStageRunId());
                }
            }
        }
        List<String> owned = candidates.stream().distinct()
                .filter(id -> belongs(snapshot, stagesById.get(id)))
                .toList();
        if (owned.size() == 1) {
            return new StageLink(owned.getFirst(), null);
        }
        if (owned.size() > 1) {
            return new StageLink(null, NO_UNIQUE_STAGE_BINDING);
        }
        if (command.stage().startsWith(ManagerDecideStages.PREFIX)
                || "HOST_VERIFY".equals(command.stage())
                || "DETERMINISTIC_REVIEW".equals(command.stage())) {
            return new StageLink(null, HOST_ONLY_COMMAND);
        }
        return new StageLink(null, NO_UNIQUE_STAGE_BINDING);
    }

    private boolean belongs(CodingMeaSnapshot snapshot, AgentStageRun stage) {
        return stage != null && snapshot.taskId().equals(stage.taskId());
    }

    private CodingMeaResponse.DecisionReference toDecision(
            CodingMeaSnapshot snapshot,
            ManagerDecision decision,
            Map<String, RequirementStageCommand> pageById,
            Set<String> preferredRecordIds
    ) {
        String managerStage = ManagerDecideStages.forSource(decision.sourceCommandId());
        RequirementStageCommand managerCommand = snapshot.pageCommands().stream()
                .filter(command -> managerStage.equals(command.stage())
                        || managerStage.equals(command.commandId()))
                .findFirst()
                .orElse(null);
        Long createdAt = managerCommand == null ? null : managerCommand.createdAtEpochMillis();
        String preview = decision.boundedContract();
        boolean truncated = preview.length() > CodingMeaResponse.CONTRACT_PREVIEW_CHARS;
        if (truncated) {
            preview = preview.substring(0, CodingMeaResponse.CONTRACT_PREVIEW_CHARS);
        }
        return new CodingMeaResponse.DecisionReference(
                managerCommand == null ? null : managerCommand.commandId(),
                decision.roundNo(),
                decision.sourceCommandId(),
                decision.decisionHash(),
                decision.route().name(),
                blankToNull(decision.executorRoute()),
                decision.targetRecordIds(),
                preview,
                truncated,
                decision.rationale(),
                decision.stateVersion(),
                decision.stateHash(),
                stateAtDecision(snapshot, decision, preferredRecordIds),
                createdAt
        );
    }

    private CodingMeaResponse.StateSlice stateAtDecision(
            CodingMeaSnapshot snapshot,
            ManagerDecision decision,
            Set<String> preferredRecordIds
    ) {
        AuditedTaskState revision = snapshot.revisionsByVersion().get(decision.stateVersion());
        if (revision == null
                || !snapshot.taskId().equals(revision.taskId())
                || !decision.stateHash().equals(revision.stateHash())) {
            return new CodingMeaResponse.StateSlice(
                    false,
                    REVISION_UNAVAILABLE,
                    decision.stateVersion(),
                    decision.stateHash(),
                    List.of(),
                    false
            );
        }
        return toStateSlice(revision, preferredRecordIds);
    }

    private CodingMeaResponse.StateSlice toStateSlice(AuditedTaskState state, Set<String> preferredRecordIds) {
        if (state == null) {
            return new CodingMeaResponse.StateSlice(false, REVISION_UNAVAILABLE, null, null, List.of(), false);
        }
        List<AuditedRecord> ranked = new ArrayList<>(state.records());
        ranked.sort(Comparator
                .comparing((AuditedRecord record) -> preferred(preferredRecordIds, record.id()) ? 0 : 1)
                .thenComparing((AuditedRecord record) -> record.blocking()
                        && record.status() != AuditedRecordStatus.COMPLETED ? 0 : 1)
                .thenComparing(AuditedRecord::id));
        boolean truncated = ranked.size() > CodingMeaResponse.MAX_STATE_RECORDS;
        List<AuditedRecord> kept = truncated
                ? ranked.subList(0, CodingMeaResponse.MAX_STATE_RECORDS)
                : ranked;
        return new CodingMeaResponse.StateSlice(
                true,
                null,
                state.stateVersion(),
                blankToNull(state.stateHash()),
                kept.stream().map(this::toRecord).toList(),
                truncated
        );
    }

    private static boolean preferred(Set<String> preferredRecordIds, String id) {
        return preferredRecordIds != null && preferredRecordIds.contains(id);
    }

    private CodingMeaResponse.AuditedRecordView toRecord(AuditedRecord record) {
        return new CodingMeaResponse.AuditedRecordView(
                record.id(),
                record.kind().name(),
                record.blocking(),
                record.text(),
                record.status().name(),
                record.evidenceRefs().stream().map(this::toEvidence).toList(),
                record.sourceStageRunId(),
                record.blockedReason()
        );
    }

    private CodingMeaResponse.EvidenceRefView toEvidence(EvidenceRef ref) {
        return new CodingMeaResponse.EvidenceRefView(
                ref.auditRunId(), ref.sourceKind().name(), ref.uri(), ref.sha256());
    }

    private CodingMeaResponse.RemediationReference toRemediation(AgentRemediationRound round) {
        return new CodingMeaResponse.RemediationReference(
                round.roundId(),
                round.kind().name(),
                round.remediationNo(),
                blankToNull(round.sourceStageRunId()),
                blankToNull(round.targetCodingStageRunId()),
                blankToNull(round.targetQaStageRunId()),
                blankToNull(round.firstCommandId()),
                round.status().name()
        );
    }

    private CodingMeaResponse.HostVerificationReference toHost(HostVerificationRun run) {
        return new CodingMeaResponse.HostVerificationReference(
                run.runId(),
                run.codingStageRunId(),
                blankToNull(run.parentRunId()),
                run.status().name(),
                run.docsOnly(),
                blankToNull(run.failureCategory())
        );
    }

    private CodingMeaResponse.AuditRunReference toAudit(AuditRun run) {
        return new CodingMeaResponse.AuditRunReference(
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

    private List<CodingMeaResponse.MeaLink> buildLinks(
            CodingMeaSnapshot snapshot,
            Map<String, AgentStageRun> stagesById,
            Map<String, RequirementStageCommand> pageById
    ) {
        List<CodingMeaResponse.MeaLink> links = new ArrayList<>();
        for (ManagerDecision decision : snapshot.decisions()) {
            links.add(commandLink(
                    snapshot,
                    "DECISION",
                    decision.decisionHash(),
                    decision.sourceCommandId(),
                    "DECIDED_AFTER",
                    pageById
            ));
            String managerStage = ManagerDecideStages.forSource(decision.sourceCommandId());
            snapshot.pageCommands().stream()
                    .filter(command -> managerStage.equals(command.stage()))
                    .findFirst()
                    .ifPresent(manager -> links.add(new CodingMeaResponse.MeaLink(
                            "COMMAND",
                            decision.sourceCommandId(),
                            "COMMAND",
                            manager.commandId(),
                            "CONTINUES_AS",
                            true,
                            null
                    )));
            if (!decision.executorRoute().isBlank()) {
                snapshot.pageCommands().stream()
                        .filter(command -> decision.executorRoute().equals(command.role())
                                || command.stage().endsWith(":" + decision.executorRoute()))
                        .findFirst()
                        .ifPresent(target -> links.add(new CodingMeaResponse.MeaLink(
                                "DECISION",
                                decision.decisionHash(),
                                "COMMAND",
                                target.commandId(),
                                "EXECUTES",
                                true,
                                null
                        )));
            }
        }
        for (HostVerificationRun run : snapshot.hostVerifications()) {
            AgentStageRun coding = stagesById.get(run.codingStageRunId());
            if (coding == null) {
                boolean known = snapshot.stages().stream()
                        .anyMatch(stage -> stage.stageRunId().equals(run.codingStageRunId()));
                links.add(new CodingMeaResponse.MeaLink(
                        "HOST_VERIFY",
                        run.runId(),
                        "STAGE",
                        known ? run.codingStageRunId() : null,
                        "VERIFIES",
                        false,
                        known ? CROSS_TASK_REFUSED : NO_UNIQUE_STAGE_BINDING
                ));
            } else {
                links.add(new CodingMeaResponse.MeaLink(
                        "HOST_VERIFY",
                        run.runId(),
                        "STAGE",
                        run.codingStageRunId(),
                        "VERIFIES",
                        true,
                        null
                ));
            }
        }
        for (AuditRun run : snapshot.auditRuns()) {
            links.add(commandLink(
                    snapshot, "AUDIT", run.auditRunId(), run.commandId(), "AUDITS", pageById));
            if (!run.subjectStageRunId().isBlank()) {
                AgentStageRun stage = stagesById.get(run.subjectStageRunId());
                links.add(new CodingMeaResponse.MeaLink(
                        "AUDIT",
                        run.auditRunId(),
                        "STAGE",
                        stage == null ? null : stage.stageRunId(),
                        "AUDITS",
                        stage != null,
                        stage == null ? NO_UNIQUE_STAGE_BINDING : null
                ));
            }
        }
        return List.copyOf(links);
    }

    private CodingMeaResponse.MeaLink commandLink(
            CodingMeaSnapshot snapshot,
            String fromType,
            String fromId,
            String commandId,
            String relation,
            Map<String, RequirementStageCommand> pageById
    ) {
        if (pageById.containsKey(commandId)) {
            return new CodingMeaResponse.MeaLink(fromType, fromId, "COMMAND", commandId, relation, true, null);
        }
        if (snapshot.knownCommandIds().contains(commandId)) {
            return new CodingMeaResponse.MeaLink(
                    fromType, fromId, "COMMAND", commandId, relation, false, OUTSIDE_PAGE);
        }
        return new CodingMeaResponse.MeaLink(
                fromType, fromId, "COMMAND", null, relation, false, NO_UNIQUE_STAGE_BINDING);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Long zeroToNull(long value) {
        return value <= 0L ? null : value;
    }

    private record StageLink(String stageRunId, String reason) {
    }
}
