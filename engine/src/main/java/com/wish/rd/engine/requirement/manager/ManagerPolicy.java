package com.wish.rd.engine.requirement.manager;

import com.wish.rd.engine.requirement.answer.UserAnswerResumeStages;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;

import java.util.ArrayList;
import java.util.List;

/** Deterministic Host Manager policy (3.0 replay + 3.1 gap repair). */
public final class ManagerPolicy {
    public static final int MAX_CODING_ATTEMPTS = 3;

    private ManagerPolicy() {
    }

    /**
     * Read-only inputs for one Manager round.
     *
     * @param taskId task id
     * @param sourceCommandId triggering command
     * @param previousStage triggering stage
     * @param paused whether the task is paused
     * @param needUserInput whether required operator material is missing
     * @param head audited head, or null
     * @param usedCodingAttempts existing Coding attempts
     * @param usedManagerGapFixRounds existing MANAGER_GAP_FIX rounds
     */
    public record Input(
            String taskId,
            String sourceCommandId,
            String previousStage,
            boolean paused,
            boolean needUserInput,
            AuditedTaskState head,
            int usedCodingAttempts,
            int usedManagerGapFixRounds
    ) {
        public Input {
            taskId = taskId == null ? "" : taskId.strip();
            sourceCommandId = sourceCommandId == null ? "" : sourceCommandId.strip();
            previousStage = previousStage == null ? "" : previousStage.strip();
        }
    }

    /**
     * Policy output before Host assigns {@code roundNo}.
     *
     * @param decision decision with placeholder round 1
     * @param continuation next command identity, or terminal
     * @param gapFix whether a MANAGER_GAP_FIX intent must be minted
     */
    public record Output(ManagerDecision decision, ContinuationSpec continuation, boolean gapFix) {
        public Output {
            if (decision == null) {
                throw new IllegalArgumentException("decision is required");
            }
            continuation = continuation == null ? ContinuationSpec.terminal() : continuation;
        }
    }

    /**
     * Decides the next bounded state change. Placeholder {@code roundNo=1} is replaced at finalize.
     *
     * @param input read-only snapshot
     * @return policy output
     */
    public static Output decide(Input input) {
        if (input == null || input.taskId().isBlank() || input.sourceCommandId().isBlank()) {
            throw new IllegalArgumentException("manager input requires taskId and sourceCommandId");
        }
        long version = input.head() == null ? 1L : input.head().stateVersion();
        String hash = input.head() == null ? "sha256:" + "0".repeat(64) : input.head().stateHash();
        if (input.paused()) {
            return blocked(input, version, hash, "task is paused");
        }
        if (input.needUserInput()) {
            return ask(input, version, hash, "required operator material is missing");
        }
        if (isHostVerify(input.previousStage())) {
            return execute(input, version, hash, List.of(), "", AgentRole.QA_AGENT.name(),
                    new ContinuationSpec(AgentRole.QA_AGENT.name(), "ROLE_EXECUTION:" + AgentRole.QA_AGENT.name()),
                    false, "host-verify succeeded; continue QA");
        }
        if (isQa(input.previousStage())) {
            List<String> pending = pendingBlockingRequirements(input.head());
            if (pending.isEmpty()) {
                return done(input, version, hash);
            }
            if (input.usedCodingAttempts() >= MAX_CODING_ATTEMPTS
                    || input.usedManagerGapFixRounds() >= AgentRemediationKind.MANAGER_GAP_FIX.maximumRounds()) {
                return blocked(input, version, hash, "coding attempt budget exhausted with pending "
                        + String.join(",", pending));
            }
            String contract = "只修复以下已审计缺口，禁止扩大范围：" + String.join(", ", pending);
            return execute(input, version, hash, pending, contract, AgentRole.CODING_AGENT.name(),
                    ContinuationSpec.terminal(), true, "pending blocking acceptance " + pending);
        }
        return blocked(input, version, hash, "unsupported manager subject stage: " + input.previousStage());
    }

    private static Output execute(
            Input input,
            long version,
            String hash,
            List<String> targetIds,
            String contract,
            String executorRoute,
            ContinuationSpec continuation,
            boolean gapFix,
            String rationale
    ) {
        ManagerDecision decision = ManagerDecision.of(
                input.taskId(), 1, input.sourceCommandId(), version, hash, ManagerRoute.EXECUTE,
                targetIds, contract, executorRoute, rationale);
        return new Output(decision, continuation, gapFix);
    }

    private static Output done(Input input, long version, String hash) {
        ManagerDecision decision = ManagerDecision.of(
                input.taskId(), 1, input.sourceCommandId(), version, hash, ManagerRoute.DONE,
                List.of(), "", "DETERMINISTIC_REVIEW", "all blocking requirements completed");
        return new Output(decision, new ContinuationSpec("REQUIREMENT_DELIVERY", "DETERMINISTIC_REVIEW"), false);
    }

    private static Output blocked(Input input, long version, String hash, String rationale) {
        ManagerDecision decision = ManagerDecision.of(
                input.taskId(), 1, input.sourceCommandId(), version, hash, ManagerRoute.BLOCKED,
                List.of(), "", "", rationale);
        return new Output(decision, ContinuationSpec.terminal(), false);
    }

    private static Output ask(Input input, long version, String hash, String rationale) {
        ManagerDecision decision = ManagerDecision.of(
                input.taskId(), 1, input.sourceCommandId(), version, hash, ManagerRoute.ASK,
                List.of(), "", "", rationale);
        return new Output(decision, ContinuationSpec.terminal(), false);
    }

    private static boolean isHostVerify(String stage) {
        return "HOST_VERIFY".equals(stage);
    }

    private static boolean isQa(String stage) {
        return ("ROLE_EXECUTION:" + AgentRole.QA_AGENT.name()).equals(stage)
                || AgentRole.QA_AGENT.name().equals(stage)
                || UserAnswerResumeStages.isResume(stage);
    }

    static List<String> pendingBlockingRequirements(AuditedTaskState head) {
        if (head == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (AuditedRecord record : head.records()) {
            if (record.kind() == AuditedRecordKind.REQUIREMENT
                    && record.blocking()
                    && record.status() == AuditedRecordStatus.PENDING) {
                ids.add(record.id());
            }
        }
        return List.copyOf(ids);
    }
}
