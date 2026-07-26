package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionProfileServiceTest {

    @Test
    void rejectsPiProfileForNonCodingRole() {
        AgentExecutionProfileService service = new AgentExecutionProfileService(
                new InMemoryAgentExecutionProfileStore()
        );

        AgentExecutionProfile profile = new AgentExecutionProfile(
                "pi-qa",
                "project-1",
                "QA_AGENT",
                "Pi QA",
                AgentRuntimeType.PI,
                "long-cat",
                "",
                "",
                "coding-v1",
                true,
                1L
        );

        assertThrows(IllegalArgumentException.class, () -> service.register(profile));
    }

    @Test
    void resolvesAuthorizedTaskOverrideBeforeProjectDefault() {
        InMemoryAgentExecutionProfileStore store = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService service = new AgentExecutionProfileService(store);
        AgentExecutionProfile projectDefault = profile("claude-default", AgentRuntimeType.CLAUDE_CODE);
        AgentExecutionProfile taskOverride = profile("pi-canary", AgentRuntimeType.PI);

        service.register(projectDefault);
        service.register(taskOverride);
        service.bindProjectDefault("project-1", "CODING_AGENT", projectDefault.profileId());
        service.setTaskOverride("task-1", "project-1", "CODING_AGENT", taskOverride.profileId());

        assertEquals(
                Optional.of(taskOverride),
                service.resolve("project-1", "task-1", "CODING_AGENT")
        );
    }

    @Test
    void rejectsOverrideFromAnotherProjectOrRole() {
        InMemoryAgentExecutionProfileStore store = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService service = new AgentExecutionProfileService(store);
        AgentExecutionProfile profile = profile("pi-canary", AgentRuntimeType.PI);
        service.register(profile);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.setTaskOverride("task-1", "project-1", "QA_AGENT", profile.profileId())
        );
    }

    private static AgentExecutionProfile profile(String id, AgentRuntimeType runtimeType) {
        return new AgentExecutionProfile(
                id,
                "project-1",
                "CODING_AGENT",
                id,
                runtimeType,
                "long-cat",
                "",
                "",
                "coding-v1",
                true,
                1L
        );
    }
}
