package com.wish.rd.engine.requirement.query;

import java.util.List;

/**
 * Frozen Coding MEA HTTP/DTO contract (schemaVersion=1).
 *
 * @param schemaVersion always 1
 * @param taskId owning task
 * @param codingStageRunId selected Coding stage, or null
 * @param available whether a Coding neighborhood exists
 * @param unavailableReason reason when unavailable
 * @param snapshotReadAtEpochMillis adapter clock
 * @param taskVersion current task version
 * @param taskStatus current task status
 * @param paused pause flag
 * @param head current audited head slice
 * @param codingStages Coding stages
 * @param qaStages QA stages
 * @param commands page of commands
 * @param decisions manager decisions
 * @param remediations remediation rounds
 * @param hostVerifications host verification runs
 * @param auditRuns audit runs
 * @param links causal links
 * @param page pagination
 */
public record CodingMeaResponse(
        int schemaVersion,
        String taskId,
        String codingStageRunId,
        boolean available,
        String unavailableReason,
        long snapshotReadAtEpochMillis,
        long taskVersion,
        String taskStatus,
        boolean paused,
        StateSlice head,
        List<StageReference> codingStages,
        List<StageReference> qaStages,
        List<CommandReference> commands,
        List<DecisionReference> decisions,
        List<RemediationReference> remediations,
        List<HostVerificationReference> hostVerifications,
        List<AuditRunReference> auditRuns,
        List<MeaLink> links,
        Page page
) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_STATE_RECORDS = 128;
    public static final int CONTRACT_PREVIEW_CHARS = 2000;

    public CodingMeaResponse {
        codingStages = codingStages == null ? List.of() : List.copyOf(codingStages);
        qaStages = qaStages == null ? List.of() : List.copyOf(qaStages);
        commands = commands == null ? List.of() : List.copyOf(commands);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        remediations = remediations == null ? List.of() : List.copyOf(remediations);
        hostVerifications = hostVerifications == null ? List.of() : List.copyOf(hostVerifications);
        auditRuns = auditRuns == null ? List.of() : List.copyOf(auditRuns);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /**
     * One audited-state slice.
     *
     * @param available whether the slice exists
     * @param unavailableReason reason when missing
     * @param stateVersion version or null
     * @param stateHash hash or null
     * @param records bounded records
     * @param recordsTruncated more records exist
     */
    public record StateSlice(
            boolean available,
            String unavailableReason,
            Long stateVersion,
            String stateHash,
            List<AuditedRecordView> records,
            boolean recordsTruncated
    ) {
        public StateSlice {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /**
     * Admin-API-compatible audited record.
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
        public AuditedRecordView {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /**
     * Admin-API-compatible evidence pointer.
     */
    public record EvidenceRefView(String auditRunId, String sourceKind, String uri, String sha256) {
    }

    /**
     * One agent stage reference.
     */
    public record StageReference(
            String stageRunId,
            String role,
            int attemptNo,
            String status,
            String resultArtifactId,
            Long startedAtEpochMillis,
            Long finishedAtEpochMillis
    ) {
    }

    /**
     * One command reference.
     */
    public record CommandReference(
            String commandId,
            String stage,
            String role,
            String status,
            String stageRunId,
            String stageLinkReason,
            int commandAttemptNo,
            String remediationRoundId,
            String remediationKind,
            Integer remediationNo,
            String remediationSourceStageRunId,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
    }

    /**
     * One Manager decision reference.
     */
    public record DecisionReference(
            String managerCommandId,
            int roundNo,
            String sourceCommandId,
            String decisionHash,
            String route,
            String executorRoute,
            List<String> targetRecordIds,
            String boundedContractPreview,
            boolean boundedContractTruncated,
            String rationale,
            long stateVersion,
            String stateHash,
            StateSlice stateAtDecision,
            Long commandCreatedAtEpochMillis
    ) {
        public DecisionReference {
            targetRecordIds = targetRecordIds == null ? List.of() : List.copyOf(targetRecordIds);
        }
    }

    /**
     * One remediation-round reference.
     */
    public record RemediationReference(
            String roundId,
            String kind,
            int remediationNo,
            String sourceStageRunId,
            String targetCodingStageRunId,
            String targetQaStageRunId,
            String firstCommandId,
            String status
    ) {
    }

    /**
     * One host-verification reference.
     */
    public record HostVerificationReference(
            String runId,
            String codingStageRunId,
            String parentRunId,
            String status,
            boolean docsOnly,
            String failureCategory
    ) {
    }

    /**
     * One audit-run reference matching {@code /audit-runs}.
     */
    public record AuditRunReference(
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
        public AuditRunReference {
            verified = verified == null ? List.of() : List.copyOf(verified);
            missing = missing == null ? List.of() : List.copyOf(missing);
            untrusted = untrusted == null ? List.of() : List.copyOf(untrusted);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    /**
     * One causal link.
     */
    public record MeaLink(
            String fromType,
            String fromId,
            String toType,
            String toId,
            String relation,
            boolean available,
            String unavailableReason
    ) {
    }

    /**
     * Command page.
     */
    public record Page(boolean hasMore, String nextCursor) {
    }
}
