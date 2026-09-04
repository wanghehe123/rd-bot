package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.audit.AcceptanceCriteriaIds;
import com.wish.rd.rag.project.agent.model.AgentStateSnapshotV2;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.RoleExecutionBudget;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Builds deterministic Host-owned initial state for capability-gated Pi stage attempts. */
public final class PiAgentContextStateManager {

    public static final int MAX_HOST_OBLIGATIONS = 32;
    public static final int MAX_INLINE_ACCEPTANCE_BYTES = 512;
    public static final int MAX_ATTACHMENT_ITEM_BYTES = 8_192;
    public static final int MAX_TOTAL_ATTACHMENT_BYTES = 32_768;
    public static final int DEFAULT_MAX_INJECTED_STATE_BYTES = 16_384;

    public AgentStateSnapshotV2 createInitialState(InitialStateRequest request) {
        return prepareInitialState(request).state();
    }

    public InitialStateBundle prepareInitialState(InitialStateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("initial state request must not be null");
        }
        if (request.runtimeType() != AgentRuntimeType.PI) {
            throw new IllegalArgumentException("PI agent state requires PI runtime");
        }
        int hostObligations = request.acceptanceCriteria().size() + request.remediationTodos().size();
        if (hostObligations > MAX_HOST_OBLIGATIONS) {
            throw new IllegalArgumentException(
                    "Host obligations exceed protocol limit " + MAX_HOST_OBLIGATIONS
            );
        }
        List<AgentStateSnapshotV2.Todo> todos = new ArrayList<>();
        List<AcceptanceAttachment> attachments = new ArrayList<>();
        int totalAttachmentBytes = 0;
        for (int index = 0; index < request.acceptanceCriteria().size(); index++) {
            String content = requireText(request.acceptanceCriteria().get(index), "acceptance criterion");
            String criteriaId = AcceptanceCriteriaIds.idAt(index);
            String contentHash = stateContentHash(content);
            int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (contentBytes > MAX_ATTACHMENT_ITEM_BYTES) {
                throw new IllegalArgumentException(
                        "acceptance attachment item exceeds " + MAX_ATTACHMENT_ITEM_BYTES + " bytes: " + criteriaId
                );
            }
            AgentStateSnapshotV2.AttachmentRef attachmentRef = null;
            String todoTitle = content;
            if (contentBytes > MAX_INLINE_ACCEPTANCE_BYTES) {
                totalAttachmentBytes += contentBytes;
                if (totalAttachmentBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
                    throw new IllegalArgumentException(
                            "total attachment bytes exceed " + MAX_TOTAL_ATTACHMENT_BYTES
                    );
                }
                String path = "attachments/state-acceptance-" + criteriaId + "-"
                        + contentHash.substring("sha256:".length(), "sha256:".length() + 12) + ".txt";
                attachments.add(new AcceptanceAttachment(path, content, contentHash, contentBytes));
                attachmentRef = new AgentStateSnapshotV2.AttachmentRef(path, contentHash, contentBytes);
                todoTitle = "验收标准 " + criteriaId + "（完整内容见受控附件 " + path + "）";
            }
            todos.add(new AgentStateSnapshotV2.Todo(
                    stableTodoId(request.taskId(), request.stageRunId(), "ACCEPTANCE", index, content),
                    AgentStateSnapshotV2.TodoOwner.HOST,
                    AgentStateSnapshotV2.TodoKind.ACCEPTANCE,
                    todoTitle,
                    AgentStateSnapshotV2.TodoStatus.PENDING,
                    true,
                    criteriaId,
                    contentHash,
                    attachmentRef,
                    List.of()
            ));
        }
        for (int index = 0; index < request.remediationTodos().size(); index++) {
            String content = requireText(request.remediationTodos().get(index), "remediation todo");
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_ATTACHMENT_ITEM_BYTES) {
                throw new IllegalArgumentException("remediation attachment item exceeds protocol limit");
            }
            todos.add(new AgentStateSnapshotV2.Todo(
                    stableTodoId(request.taskId(), request.stageRunId(), "REMEDIATION", index, content),
                    AgentStateSnapshotV2.TodoOwner.HOST,
                    AgentStateSnapshotV2.TodoKind.REMEDIATION,
                    content,
                    AgentStateSnapshotV2.TodoStatus.PENDING,
                    true,
                    "",
                    stateContentHash(content),
                    List.of()
            ));
        }
        AgentStateSnapshotV2 state = new AgentStateSnapshotV2(
                AgentStateV2Codec.PROTOCOL,
                0L,
                request.taskId(),
                request.stageRunId(),
                request.role().name(),
                request.attemptNo(),
                request.runtimeType().name(),
                request.profileSnapshotId(),
                request.currentGoal().isBlank() ? roleGoal(request.role()) : request.currentGoal(),
                request.taskStartedAtEpochMillis(),
                request.stageStartedAtEpochMillis(),
                request.phase(),
                budget(request.budget()),
                todos,
                request.generatedAtEpochMillis()
        );
        int stateBytes = state.canonicalJson().getBytes(StandardCharsets.UTF_8).length;
        if (stateBytes > request.maxInjectedStateBytes()) {
            throw new IllegalArgumentException(
                    "initial state exceeds maxInjectedStateBytes: " + stateBytes
                            + " > " + request.maxInjectedStateBytes()
            );
        }
        return new InitialStateBundle(state, attachments);
    }

    private static AgentStateSnapshotV2.Budget budget(RoleExecutionBudget budget) {
        if (budget == null || !budget.contextBudgetAvailable() || !budget.reservedOutputAvailable()) {
            return new AgentStateSnapshotV2.Budget(
                    AgentStateSnapshotV2.BudgetAvailability.UNKNOWN,
                    budget == null ? "" : budget.model(),
                    null,
                    null,
                    budget == null ? null : budget.estimatedInputTokens(),
                    budget == null ? "" : budget.estimatorVersion()
            );
        }
        return new AgentStateSnapshotV2.Budget(
                AgentStateSnapshotV2.BudgetAvailability.AVAILABLE,
                budget.model(),
                budget.maxContextTokens(),
                budget.reservedOutputTokens(),
                budget.estimatedInputTokens(),
                budget.estimatorVersion()
        );
    }

    private static String roleGoal(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> "审查并澄清需求，形成可执行且可验收的交付边界";
            case SOLUTION_ARCHITECT -> "制定符合仓库约束、可验证且可实施的技术方案";
            case CODING_AGENT -> "实现当前需求并以真实命令验证交付候选包";
            case QA_AGENT -> "依据验收标准和真实证据完成独立验收，准确报告缺陷";
            case BUG_EVIDENCE_COLLECTOR -> "收集并固化可复现、可验证的缺陷证据";
            case BUG_RAG_RETRIEVER -> "检索与当前缺陷直接相关且可追溯的知识证据";
            case BUG_ACCEPTANCE_PLANNER -> "将缺陷证据转化为明确、可执行的验收计划";
            case BUG_CODING_AGENT -> "修复已确认缺陷并以真实命令验证修复结果";
        };
    }

    private static String stableTodoId(
            String taskId,
            String stageRunId,
            String kind,
            int index,
            String content
    ) {
        String material = taskId + "\n" + stageRunId + "\n" + kind + "\n" + index + "\n" + content;
        return "host-" + kind.toLowerCase() + "-" + sha256Hex(material).substring(0, 20);
    }

    private static String stateContentHash(String content) {
        return "sha256:" + sha256Hex(content);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    public record InitialStateRequest(
            String taskId,
            String stageRunId,
            AgentRole role,
            int attemptNo,
            AgentRuntimeType runtimeType,
            String profileSnapshotId,
            String currentGoal,
            long taskStartedAtEpochMillis,
            long stageStartedAtEpochMillis,
            String phase,
            RoleExecutionBudget budget,
            List<String> acceptanceCriteria,
            List<String> remediationTodos,
            long generatedAtEpochMillis,
            int maxInjectedStateBytes
    ) {
        public InitialStateRequest {
            taskId = requireText(taskId, "taskId");
            stageRunId = requireText(stageRunId, "stageRunId");
            if (role == null) {
                throw new IllegalArgumentException("role must not be null");
            }
            if (attemptNo <= 0) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            if (runtimeType == null) {
                throw new IllegalArgumentException("runtimeType must not be null");
            }
            profileSnapshotId = requireText(profileSnapshotId, "profileSnapshotId");
            currentGoal = currentGoal == null ? "" : currentGoal.strip();
            phase = requireText(phase, "phase");
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
            remediationTodos = remediationTodos == null ? List.of() : List.copyOf(remediationTodos);
            maxInjectedStateBytes = maxInjectedStateBytes <= 0
                    ? DEFAULT_MAX_INJECTED_STATE_BYTES
                    : maxInjectedStateBytes;
        }

        public InitialStateRequest(
                String taskId,
                String stageRunId,
                AgentRole role,
                int attemptNo,
                AgentRuntimeType runtimeType,
                String profileSnapshotId,
                String currentGoal,
                long taskStartedAtEpochMillis,
                long stageStartedAtEpochMillis,
                String phase,
                RoleExecutionBudget budget,
                List<String> acceptanceCriteria,
                List<String> remediationTodos,
                long generatedAtEpochMillis
        ) {
            this(
                    taskId, stageRunId, role, attemptNo, runtimeType, profileSnapshotId,
                    currentGoal, taskStartedAtEpochMillis, stageStartedAtEpochMillis, phase,
                    budget, acceptanceCriteria, remediationTodos, generatedAtEpochMillis,
                    DEFAULT_MAX_INJECTED_STATE_BYTES
            );
        }
    }

    public record AcceptanceAttachment(String path, String content, String hash, int bytes) {
        public AcceptanceAttachment {
            path = requireText(path, "attachment path");
            content = requireText(content, "attachment content");
            hash = requireText(hash, "attachment hash");
            if (bytes <= 0) {
                throw new IllegalArgumentException("attachment bytes must be positive");
            }
        }
    }

    public record InitialStateBundle(
            AgentStateSnapshotV2 state,
            List<AcceptanceAttachment> attachments
    ) {
        public InitialStateBundle {
            if (state == null) {
                throw new IllegalArgumentException("state must not be null");
            }
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }
}
