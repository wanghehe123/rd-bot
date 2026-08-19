package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionProfileServiceTest {

    @Test
    void acceptsPiProfileForEveryDeliveryRole() {
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

        // Pi is now the default runtime for all four delivery roles, not only CODING_AGENT.
        assertEquals(profile, service.register(profile));
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

    @Test
    void normalizesCapabilitiesInStableOrderWithoutDuplicates() {
        AgentExecutionProfile profile = profile(
                "pi-capabilities",
                AgentRuntimeType.PI,
                List.of(
                        AgentRuntimeCapability.PI_QA_REMEDIATION_V2,
                        AgentRuntimeCapability.PI_AGENT_STATE_V2,
                        AgentRuntimeCapability.PI_QA_REMEDIATION_V2
                )
        );

        assertEquals(
                List.of(
                        AgentRuntimeCapability.PI_AGENT_STATE_V2,
                        AgentRuntimeCapability.PI_QA_REMEDIATION_V2
                ),
                profile.capabilities()
        );
    }

    @Test
    void rejectsPiCapabilityForClaudeCodeAndModelOnlyCompatibilityProfiles() {
        for (AgentRuntimeType runtimeType : List.of(
                AgentRuntimeType.CLAUDE_CODE,
                AgentRuntimeType.MODEL_ONLY
        )) {
            AgentExecutionProfileService service = new AgentExecutionProfileService(
                    new InMemoryAgentExecutionProfileStore()
            );
            AgentExecutionProfile profile = profile(
                    runtimeType.name().toLowerCase() + "-with-pi-capability",
                    runtimeType,
                    List.of(AgentRuntimeCapability.PI_AGENT_STATE_V2)
            );

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.create(profile)
            );

            assertEquals("PI capabilities require PI runtime", error.getMessage());
        }
    }

    @Test
    void rejectsUnknownCapabilityName() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AgentRuntimeCapability.parse("PI_DO_WHATEVER")
        );

        assertEquals("unsupported agent runtime capability: PI_DO_WHATEVER", error.getMessage());
    }

    @Test
    void updateUsesExpectedVersionAndIncrementsStoredVersion() {
        InMemoryAgentExecutionProfileStore store = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService service = new AgentExecutionProfileService(store);
        AgentExecutionProfile created = service.create(profile(
                "pi-versioned",
                AgentRuntimeType.PI,
                List.of(AgentRuntimeCapability.PI_AGENT_STATE_V2)
        ));

        AgentExecutionProfile updated = service.update(
                new AgentExecutionProfile(
                        created.profileId(),
                        created.projectId(),
                        created.role(),
                        "Pi versioned updated",
                        created.runtimeType(),
                        created.providerProfileId(),
                        created.modelOverride(),
                        created.extensionSetId(),
                        created.extensionSetVersion(),
                        created.toolPolicyId(),
                        created.toolPolicyVersion(),
                        created.enabled(),
                        created.version(),
                        List.of(
                                AgentRuntimeCapability.PI_QA_REMEDIATION_V2,
                                AgentRuntimeCapability.PI_AGENT_STATE_V2
                        )
                ),
                created.version()
        );

        assertEquals(2L, updated.version());
        assertEquals(updated, store.find(updated.profileId()).orElseThrow());
        assertEquals(
                List.of(
                        AgentRuntimeCapability.PI_AGENT_STATE_V2,
                        AgentRuntimeCapability.PI_QA_REMEDIATION_V2
                ),
                updated.capabilities()
        );

        IllegalStateException stale = assertThrows(
                IllegalStateException.class,
                () -> service.update(updated, created.version())
        );
        assertEquals("execution profile version conflict: pi-versioned", stale.getMessage());
    }

    private static AgentExecutionProfile profile(String id, AgentRuntimeType runtimeType) {
        return profile(id, runtimeType, List.of());
    }

    private static AgentExecutionProfile profile(
            String id,
            AgentRuntimeType runtimeType,
            List<AgentRuntimeCapability> capabilities
    ) {
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
                1L,
                capabilities
        );
    }
}
