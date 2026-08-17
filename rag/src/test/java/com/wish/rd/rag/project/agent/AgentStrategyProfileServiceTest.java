package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStrategyProfileStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStrategyConsole;
import com.wish.rd.rag.project.agent.model.AgentStrategyImageMode;
import com.wish.rd.rag.project.agent.model.AgentStrategyProfile;
import com.wish.rd.rag.project.agent.model.AgentStrategyRoleSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStrategyProfileServiceTest {

    @Test
    void saveRequiresAllFourRolesAndProjectsStableProfileIds() {
        InMemoryAgentExecutionProfileStore profileStore = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profileStore);
        AgentStrategyProfileService service = new AgentStrategyProfileService(
                new InMemoryAgentStrategyProfileStore(),
                profileService
        );

        assertThrows(IllegalArgumentException.class, () -> service.save(new AgentStrategyProfile(
                "prod",
                "project-1",
                "生产",
                true,
                1L,
                List.of(slot("CODING_AGENT"))
        )));

        AgentStrategyProfile saved = service.save(complete("prod", "生产"));
        assertEquals(4, saved.roles().size());
        assertEquals("project-1:prod:CODING_AGENT", AgentStrategyProfile.projectedProfileId("project-1", "prod", "CODING_AGENT"));
        assertEquals(
                AgentRuntimeType.PI,
                profileService.resolve("project-1", "task-1", "CODING_AGENT").map(AgentExecutionProfile::runtimeType).orElse(null)
        );

        service.bindDefault("project-1", "prod");
        assertEquals(
                AgentRuntimeType.PI,
                profileService.resolve("project-1", "task-1", "REQUIREMENT_REVIEWER").map(AgentExecutionProfile::runtimeType).orElse(null)
        );
        assertEquals(
                AgentRuntimeType.PI,
                profileService.resolve("project-1", "task-1", "QA_AGENT").map(AgentExecutionProfile::runtimeType).orElse(null)
        );

        AgentStrategyProfile again = service.save(complete("prod", "生产"));
        assertEquals("project-1:prod:QA_AGENT", AgentStrategyProfile.projectedProfileId(again.projectId(), again.strategyId(), "QA_AGENT"));
        assertEquals("project-1:prod:QA_AGENT", profileStore.find("project-1:prod:QA_AGENT").orElseThrow().profileId());
    }

    @Test
    void sameStrategyIdIsIsolatedPerProject() {
        InMemoryAgentExecutionProfileStore profileStore = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profileStore);
        AgentStrategyProfileService service = new AgentStrategyProfileService(
                new InMemoryAgentStrategyProfileStore(),
                profileService
        );
        service.save(complete("project-1", "default", "默认", AgentRuntimeType.PI));
        service.save(complete("project-2", "default", "默认", AgentRuntimeType.CLAUDE_CODE));

        assertEquals(
                AgentRuntimeType.PI,
                profileService.resolve("project-1", "task-1", "CODING_AGENT").map(AgentExecutionProfile::runtimeType).orElse(null)
        );
        assertEquals(
                AgentRuntimeType.CLAUDE_CODE,
                profileService.resolve("project-2", "task-1", "CODING_AGENT").map(AgentExecutionProfile::runtimeType).orElse(null)
        );
        assertTrue(profileStore.find("project-1:default:CODING_AGENT").isPresent());
        assertTrue(profileStore.find("project-2:default:CODING_AGENT").isPresent());
    }

    @Test
    void rejectsStrategyIdLongerThan64Characters() {
        AgentStrategyProfileService service = new AgentStrategyProfileService(
                new InMemoryAgentStrategyProfileStore(),
                new AgentExecutionProfileService(new InMemoryAgentExecutionProfileStore())
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.save(complete("a".repeat(65), "过长"))
        );
        assertTrue(error.getMessage().contains("64"));
    }

    @Test
    void missingProviderIsRejectedPerRole() {
        AgentStrategyProfileService service = new AgentStrategyProfileService(
                new InMemoryAgentStrategyProfileStore(),
                new AgentExecutionProfileService(new InMemoryAgentExecutionProfileStore())
        );
        AgentStrategyRoleSlot coding = new AgentStrategyRoleSlot(
                "CODING_AGENT",
                AgentRuntimeType.PI,
                "",
                "",
                "",
                0L,
                "legacy-host-bound",
                1L,
                AgentStrategyImageMode.LOCAL_DEFAULT,
                "",
                "",
                "",
                "",
                ""
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.save(new AgentStrategyProfile(
                        "prod",
                        "project-1",
                        "生产",
                        true,
                        1L,
                        List.of(slot("REQUIREMENT_REVIEWER"), slot("SOLUTION_ARCHITECT"), coding, slot("QA_AGENT"))
                ))
        );
        assertTrue(error.getMessage().contains("CODING_AGENT"));
    }

    @Test
    void emptyStoreSynthesizesLegacyCurrentFromRoleBindings() {
        InMemoryAgentExecutionProfileStore profileStore = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profileStore);
        profileService.register(new AgentExecutionProfile(
                "legacy-coding",
                "project-1",
                "CODING_AGENT",
                "旧编码",
                AgentRuntimeType.CLAUDE_CODE,
                "deepseek",
                "",
                "",
                "legacy-host-bound",
                true,
                1L
        ));
        profileService.bindProjectDefault("project-1", "CODING_AGENT", "legacy-coding");

        AgentStrategyProfileService service = new AgentStrategyProfileService(
                new InMemoryAgentStrategyProfileStore(),
                profileService
        );
        AgentStrategyConsole console = service.list("project-1");
        assertTrue(console.synthesizedFromLegacy());
        assertEquals(AgentStrategyProfileService.LEGACY_STRATEGY_ID, console.defaultStrategyId());
        AgentStrategyRoleSlot coding = console.strategies().get(0).slot("CODING_AGENT");
        assertEquals(AgentRuntimeType.CLAUDE_CODE, coding.runtimeType());
        assertEquals("deepseek", coding.providerProfileId());
        assertThrows(IllegalArgumentException.class, () -> service.bindDefault("project-1", AgentStrategyProfileService.LEGACY_STRATEGY_ID));
    }

    @Test
    void customPiImagePersistsWithoutChangingProjectedRuntimeType() {
        InMemoryAgentExecutionProfileStore profileStore = new InMemoryAgentExecutionProfileStore();
        AgentStrategyProfileService service = new AgentStrategyProfileService(
                new InMemoryAgentStrategyProfileStore(),
                new AgentExecutionProfileService(profileStore)
        );
        service.save(complete("prod", "生产"));
        AgentStrategyProfile updated = service.applyCustomImage(
                "project-1",
                "prod",
                "CODING_AGENT",
                "rd-bot/pi-agent:custom",
                "Dockerfile",
                "abc",
                "",
                "FROM pi"
        );
        AgentStrategyRoleSlot coding = updated.slot("CODING_AGENT");
        assertEquals(AgentStrategyImageMode.CUSTOM, coding.imageMode());
        assertEquals("FROM pi", coding.dockerfileText());
        assertEquals(AgentRuntimeType.PI, profileStore.find("project-1:prod:CODING_AGENT").orElseThrow().runtimeType());

        AgentStrategyProfile cleared = service.clearCustomImage("project-1", "prod", "CODING_AGENT");
        assertEquals(AgentStrategyImageMode.LOCAL_DEFAULT, cleared.slot("CODING_AGENT").imageMode());
        assertEquals("", cleared.slot("CODING_AGENT").dockerfileText());
        assertFalse(service.list("project-1").synthesizedFromLegacy());
    }

    private static AgentStrategyProfile complete(String id, String name) {
        return complete("project-1", id, name, AgentRuntimeType.PI);
    }

    private static AgentStrategyProfile complete(
            String projectId,
            String id,
            String name,
            AgentRuntimeType runtimeType
    ) {
        return new AgentStrategyProfile(
                id,
                projectId,
                name,
                true,
                1L,
                List.of(
                        slot("REQUIREMENT_REVIEWER", runtimeType),
                        slot("SOLUTION_ARCHITECT", runtimeType),
                        slot("CODING_AGENT", runtimeType),
                        slot("QA_AGENT", runtimeType)
                )
        );
    }

    private static AgentStrategyRoleSlot slot(String role) {
        return slot(role, AgentRuntimeType.PI);
    }

    private static AgentStrategyRoleSlot slot(String role, AgentRuntimeType runtimeType) {
        return new AgentStrategyRoleSlot(
                role,
                runtimeType,
                "deepseek",
                "",
                "",
                0L,
                "legacy-host-bound",
                1L,
                AgentStrategyImageMode.LOCAL_DEFAULT,
                "",
                "",
                "",
                "",
                ""
        );
    }
}
