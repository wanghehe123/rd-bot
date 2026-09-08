package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.manager.impl.InMemoryManagerDecisionStore;
import com.wish.rd.engine.requirement.query.CodingMeaCursor;
import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.CodingMeaQueryEngine;
import com.wish.rd.engine.requirement.query.CodingMeaSnapshot;
import com.wish.rd.engine.requirement.remediation.impl.InMemoryAgentRemediationRoundStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresCodingMeaReadAdapterTest {

    private RagStreamTaskRegistry registry;
    private InMemoryAgentStageRunStore stages;
    private InMemoryRequirementStageCommandStore commands;
    private PostgresCodingMeaReadAdapter adapter;
    private RdRequirementTask task;

    @BeforeEach
    void setUp() {
        registry = RagStreamTaskRegistry.inMemory();
        stages = new InMemoryAgentStageRunStore();
        commands = new InMemoryRequirementStageCommandStore();
        adapter = new PostgresCodingMeaReadAdapter(
                registry,
                stages,
                commands,
                new InMemoryManagerDecisionStore(),
                new InMemoryAuditedTaskStateStore(),
                new InMemoryAgentRemediationRoundStore(),
                new InMemoryHostVerificationStore(),
                new InMemoryTaskRetryAttemptBindingStore()
        );
        task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "快照任务", "P1", "ADMIN", "", "", "project-owned", "owned-key", "Owned Project",
                "https://github.com/acme/web.git", "", "", "main", "页面可用",
                List.of("真实浏览器通过"), List.of(), false));
    }

    @Test
    void missingCodingStageIsEmptyNeighborhood() {
        CodingMeaSnapshot snapshot = adapter.readSnapshot(task.taskId(), "", 50, "");
        assertTrue(snapshot.codingStageMissing());
        assertEquals("", snapshot.selectedCodingStageRunId());
        assertTrue(new CodingMeaQueryEngine().query(snapshot).unavailableReason()
                .equals("NO_CODING_STAGE"));
    }

    @Test
    void foreignCodingStageIsNotFound() {
        RdRequirementTask other = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "他任务", "P1", "ADMIN", "", "", "project-owned", "owned-key", "Owned Project",
                "https://github.com/acme/web.git", "", "", "main", "页面可用",
                List.of("真实浏览器通过"), List.of(), false));
        stages.save(AgentStageRun.pending(
                "stage-b", other.taskId(), AgentRole.CODING_AGENT, 1, other.taskId() + ":C:1", 1L));
        assertThrows(CodingMeaNotFoundException.class,
                () -> adapter.readSnapshot(task.taskId(), "stage-b", 50, ""));
    }

    @Test
    void sameCreatedAtOrdersByIdAndCursorStaysOnSelection() {
        stages.save(AgentStageRun.pending(
                "stage-c", task.taskId(), AgentRole.CODING_AGENT, 1, task.taskId() + ":C:1", 1L));
        commands.enqueue(command("cmd-a", "MATERIAL_READY", "REQUIREMENT_DELIVERY", 5_000L));
        commands.enqueue(command("cmd-b", "CONTEXT_BUILDING", "REQUIREMENT_DELIVERY", 5_000L));
        commands.enqueue(command("cmd-c", "POLICY_APPLY", "REQUIREMENT_DELIVERY", 5_000L));

        CodingMeaSnapshot first = adapter.readSnapshot(task.taskId(), "stage-c", 2, "");
        assertEquals(List.of("cmd-a", "cmd-b"),
                first.pageCommands().stream().map(RequirementStageCommand::commandId).toList());
        assertTrue(first.commandsHasMore());

        CodingMeaSnapshot second = adapter.readSnapshot(
                task.taskId(), "stage-c", 2, first.nextCursor());
        assertEquals(List.of("cmd-c"),
                second.pageCommands().stream().map(RequirementStageCommand::commandId).toList());
        assertFalse(second.commandsHasMore());

        String stolen = CodingMeaCursor.encode(task.taskId(), "other-stage", 5_000L, "cmd-a");
        assertThrows(com.wish.rd.engine.requirement.query.CodingMeaBadRequestException.class,
                () -> adapter.readSnapshot(task.taskId(), "stage-c", 2, stolen));
    }

    @Test
    void readDoesNotEnqueueCommands() {
        stages.save(AgentStageRun.pending(
                "stage-c", task.taskId(), AgentRole.CODING_AGENT, 1, task.taskId() + ":C:1", 1L));
        commands.enqueue(command("cmd-a", "MATERIAL_READY", "REQUIREMENT_DELIVERY", 1_000L));
        int before = commands.listIdsByTask(task.taskId()).size();
        adapter.readSnapshot(task.taskId(), "", 50, "");
        assertEquals(before, commands.listIdsByTask(task.taskId()).size());
        assertEquals(Set.of("cmd-a"), Set.copyOf(commands.listIdsByTask(task.taskId())));
    }

    private RequirementStageCommand command(String id, String stage, String role, long createdAt) {
        return RequirementStageCommand.pending(
                id, task.taskId(), 1L, 2L, role, stage,
                0, 3, createdAt + 60_000L, ScheduleResourceClass.GENERIC,
                Set.of(ScheduleResourceClass.GENERIC), "project", "memory", "P1", createdAt);
    }
}
