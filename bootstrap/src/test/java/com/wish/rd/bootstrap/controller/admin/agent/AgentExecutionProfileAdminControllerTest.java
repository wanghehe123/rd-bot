package com.wish.rd.bootstrap.controller.admin.agent;

import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.ModelProviderProfileService;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionProfileAdminControllerTest {

    @Test
    void shouldManageRegisteredProfilesAndAuthorizedTaskOverrideWithoutExposingSecrets() {
        InMemoryAgentExecutionProfileStore profileStore = new InMemoryAgentExecutionProfileStore();
        InMemoryAgentExecutionProfileSnapshotStore snapshotStore = new InMemoryAgentExecutionProfileSnapshotStore();
        RagStreamTaskRegistry tasks = RagStreamTaskRegistry.inMemory();
        var controller = new AgentExecutionProfileAdminController(
                new AgentExecutionProfileService(profileStore),
                new ModelProviderProfileService(new InMemoryModelProviderProfileStore()),
                snapshotStore,
                tasks,
                new AgentRuntimeMutationAccessPolicy("runtime-token")
        );
        var request = new AgentExecutionProfileAdminController.AgentExecutionProfileRequest(
                "pi-coding",
                "CODING_AGENT",
                "Pi coding",
                AgentRuntimeType.PI,
                "provider-1",
                "",
                "",
                0L,
                "coding-tools",
                1L,
                true,
                1L,
                List.of(AgentRuntimeCapability.PI_AGENT_STATE_V2)
        );

        var created = controller.createProfile("project-1", "runtime-token", request);
        assertEquals(List.of(AgentRuntimeCapability.PI_AGENT_STATE_V2), created.capabilities());
        assertEquals(1L, created.version());
        assertEquals(1, controller.listProfiles("project-1").size());
        var updated = controller.updateProfile(
                "project-1",
                "pi-coding",
                "runtime-token",
                new AgentExecutionProfileAdminController.AgentExecutionProfileRequest(
                        "ignored-on-update",
                        "CODING_AGENT",
                        "Pi coding updated",
                        AgentRuntimeType.PI,
                        "provider-1",
                        "",
                        "",
                        0L,
                        "coding-tools",
                        1L,
                        true,
                        1L,
                        List.of(
                                AgentRuntimeCapability.PI_QA_REMEDIATION_V2,
                                AgentRuntimeCapability.PI_AGENT_STATE_V2
                        )
                )
        );
        assertEquals(2L, updated.version());
        assertEquals(
                List.of(
                        AgentRuntimeCapability.PI_AGENT_STATE_V2,
                        AgentRuntimeCapability.PI_QA_REMEDIATION_V2
                ),
                updated.capabilities()
        );
        assertEquals(409, controller.conflict(assertThrows(
                IllegalStateException.class,
                () -> controller.updateProfile("project-1", "pi-coding", "runtime-token", request)
        )).getStatusCode().value());
        controller.bindProjectDefault(
                "project-1", "CODING_AGENT", "runtime-token",
                new AgentExecutionProfileAdminController.ProfileReferenceRequest("pi-coding")
        );

        var task = tasks.createRequirementTask(new CreateRequirementTaskCommand(
                "Requirement", "P1", "ADMIN", "", "", "project-1", "", "",
                "https://github.com/acme/repo.git", "acme", "repo", "main",
                "done", List.of("tests pass"), List.of(), false
        ));
        controller.setTaskOverride(
                task.taskId(), "CODING_AGENT", "runtime-token",
                new AgentExecutionProfileAdminController.ProfileReferenceRequest("pi-coding")
        );
        controller.clearTaskOverride(task.taskId(), "CODING_AGENT", "runtime-token");

        assertThrows(Exception.class, () -> controller.createProfile(
                "project-1", "wrong-token", request
        ));
    }

    @Test
    void shouldReturnOnlyTheSnapshotBelongingToTheRequestedTask() {
        RagStreamTaskRegistry tasks = RagStreamTaskRegistry.inMemory();
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        var controller = new AgentExecutionProfileAdminController(
                new AgentExecutionProfileService(new InMemoryAgentExecutionProfileStore()),
                new ModelProviderProfileService(new InMemoryModelProviderProfileStore()),
                snapshots,
                tasks,
                new AgentRuntimeMutationAccessPolicy("token")
        );
        String json = "{\"runtimeType\":\"PI\"}";
        snapshots.saveIfAbsent(new AgentExecutionProfileSnapshot(
                "snapshot-1", "stage-1", "task-1", "CODING_AGENT", 1,
                AgentRuntimeType.PI, json, AgentExecutionProfileSnapshot.sha256(json), 1L
        ));
        // The task registry must still be consulted before a snapshot is returned.
        assertThrows(Exception.class, () -> controller.getExecutionProfile("task-1", "stage-1"));
    }
}
