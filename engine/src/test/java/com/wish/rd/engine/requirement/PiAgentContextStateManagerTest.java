package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.rag.project.agent.model.AgentStateSnapshotV2;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.RoleExecutionBudget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiAgentContextStateManagerTest {

    @Test
    void createsJavaOwnedInitialStateWithRoleGoalTimesPhaseAndAvailableBudget() {
        PiAgentContextStateManager manager = new PiAgentContextStateManager();

        AgentStateSnapshotV2 state = manager.createInitialState(request(
                new RoleExecutionBudget("claude-sonnet", 200_000L, 8_192L, 12_000L, "chars/4-v1"),
                List.of("API returns 200", "Regression suite passes"),
                List.of("修复 QA finding BUG-7")
        ));

        assertEquals("rd-agent-state/v2", state.protocol());
        assertEquals("task-1", state.taskId());
        assertEquals("stage-qa-1", state.stageRunId());
        assertEquals(AgentRole.QA_AGENT.name(), state.role());
        assertEquals(2, state.attemptNo());
        assertEquals(AgentRuntimeType.PI.name(), state.runtimeType());
        assertEquals("snapshot-qa-1", state.profileSnapshotId());
        assertTrue(state.currentGoal().contains("验收"));
        assertEquals(1_700_000_000_000L, state.taskStartedAtEpochMillis());
        assertEquals(1_700_000_100_000L, state.stageStartedAtEpochMillis());
        assertEquals("QA_EXECUTION", state.phase());
        assertEquals(AgentStateSnapshotV2.BudgetAvailability.AVAILABLE, state.budget().availability());
        assertEquals(200_000L, state.budget().maxContextTokens());
        assertEquals(8_192L, state.budget().reservedOutputTokens());
        assertEquals(12_000L, state.budget().estimatedInputTokens());
        assertTrue(state.hasValidCanonicalHash());
    }

    @Test
    void createsOneImmutableHostTodoPerAcceptanceAndIncludesRemediationTodo() {
        PiAgentContextStateManager manager = new PiAgentContextStateManager();

        AgentStateSnapshotV2 first = manager.createInitialState(request(
                new RoleExecutionBudget(
                        RoleExecutionBudget.UNAVAILABLE_MODEL,
                        RoleExecutionBudget.UNAVAILABLE_TOKENS,
                        RoleExecutionBudget.UNAVAILABLE_TOKENS,
                        0L,
                        "chars/4-v1"
                ),
                List.of("完整验收 A", "完整验收 B"),
                List.of("必须处理上轮 QA BUG-9")
        ));
        AgentStateSnapshotV2 second = manager.createInitialState(request(
                new RoleExecutionBudget(
                        RoleExecutionBudget.UNAVAILABLE_MODEL,
                        RoleExecutionBudget.UNAVAILABLE_TOKENS,
                        RoleExecutionBudget.UNAVAILABLE_TOKENS,
                        0L,
                        "chars/4-v1"
                ),
                List.of("完整验收 A", "完整验收 B"),
                List.of("必须处理上轮 QA BUG-9")
        ));

        assertEquals(AgentStateSnapshotV2.BudgetAvailability.UNKNOWN, first.budget().availability());
        assertEquals(3, first.todos().size());
        assertEquals(List.of("完整验收 A", "完整验收 B"), first.todos().stream()
                .filter(todo -> todo.kind() == AgentStateSnapshotV2.TodoKind.ACCEPTANCE)
                .map(AgentStateSnapshotV2.Todo::title)
                .toList());
        assertTrue(first.todos().stream()
                .filter(todo -> todo.kind() == AgentStateSnapshotV2.TodoKind.ACCEPTANCE)
                .allMatch(todo -> todo.owner() == AgentStateSnapshotV2.TodoOwner.HOST && todo.required()));
        assertTrue(first.todos().stream().anyMatch(todo ->
                todo.kind() == AgentStateSnapshotV2.TodoKind.REMEDIATION
                        && todo.title().equals("必须处理上轮 QA BUG-9")));
        assertNotEquals(first.todos().get(0).todoId(), first.todos().get(1).todoId());
        assertEquals(first.todos(), second.todos());
        assertEquals(first.canonicalHash(), second.canonicalHash());
    }

    @Test
    void createsHashBoundAttachmentWithoutTruncatingLongAcceptanceContent() {
        PiAgentContextStateManager manager = new PiAgentContextStateManager();
        String criterion = "验收证据必须完整：" + "测".repeat(300);

        PiAgentContextStateManager.InitialStateBundle bundle = manager.prepareInitialState(request(
                unavailableBudget(), List.of(criterion), List.of()
        ));

        assertEquals(1, bundle.attachments().size());
        PiAgentContextStateManager.AcceptanceAttachment attachment = bundle.attachments().getFirst();
        assertEquals(criterion, attachment.content());
        assertEquals(criterion.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, attachment.bytes());
        assertTrue(attachment.path().startsWith("attachments/state-acceptance-AC-001-"));
        AgentStateSnapshotV2.Todo todo = bundle.state().todos().getFirst();
        assertEquals(attachment.hash(), todo.acceptanceContentHash());
        assertEquals(attachment.path(), todo.attachment().path());
        assertEquals(attachment.hash(), todo.attachment().hash());
    }

    @Test
    void failsClosedWhenHostObligationsOrAttachmentsExceedLimits() {
        PiAgentContextStateManager manager = new PiAgentContextStateManager();
        List<String> thirtyThree = java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> "criterion-" + index)
                .toList();
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> manager.prepareInitialState(request(unavailableBudget(), thirtyThree, List.of()))
        ).getMessage().contains("32"));

        String oversized = "x".repeat(PiAgentContextStateManager.MAX_ATTACHMENT_ITEM_BYTES + 1);
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> manager.prepareInitialState(request(unavailableBudget(), List.of(oversized), List.of()))
        ).getMessage().contains("attachment item"));

        String boundedItem = "x".repeat(7_000);
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> manager.prepareInitialState(request(
                        unavailableBudget(), List.of(
                                boundedItem, boundedItem, boundedItem, boundedItem, boundedItem
                        ), List.of()
                ))
        ).getMessage().contains("total attachment"));
    }

    @Test
    void failsBeforeDispatchWhenCanonicalStateCannotFitInjectionLimit() {
        PiAgentContextStateManager manager = new PiAgentContextStateManager();
        PiAgentContextStateManager.InitialStateRequest bounded = new PiAgentContextStateManager.InitialStateRequest(
                "task-1", "stage-qa-1", AgentRole.QA_AGENT, 2, AgentRuntimeType.PI,
                "snapshot-qa-1", "", 1_700_000_000_000L, 1_700_000_100_000L,
                "QA_EXECUTION", unavailableBudget(), List.of("must remain complete"), List.of(),
                1_700_000_100_000L, 256
        );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> manager.prepareInitialState(bounded)
        );
        assertTrue(failure.getMessage().contains("maxInjectedStateBytes"));
    }

    private static RoleExecutionBudget unavailableBudget() {
        return new RoleExecutionBudget(
                RoleExecutionBudget.UNAVAILABLE_MODEL,
                RoleExecutionBudget.UNAVAILABLE_TOKENS,
                RoleExecutionBudget.UNAVAILABLE_TOKENS,
                0L,
                "chars/4-v1"
        );
    }

    private static PiAgentContextStateManager.InitialStateRequest request(
            RoleExecutionBudget budget,
            List<String> acceptanceCriteria,
            List<String> remediationTodos
    ) {
        return new PiAgentContextStateManager.InitialStateRequest(
                "task-1",
                "stage-qa-1",
                AgentRole.QA_AGENT,
                2,
                AgentRuntimeType.PI,
                "snapshot-qa-1",
                "",
                1_700_000_000_000L,
                1_700_000_100_000L,
                "QA_EXECUTION",
                budget,
                acceptanceCriteria,
                remediationTodos,
                1_700_000_100_000L
        );
    }
}
