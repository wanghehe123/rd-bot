package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutionProfileResolver;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotService;
import com.wish.rd.rag.project.agent.AgentToolPolicyService;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentToolPolicyStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentToolPolicy;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementExecutionProfileResolverTest {

    @Test
    void shouldPrepareEligiblePiTargetSnapshotWithoutPersistingIt() {
        InMemoryAgentExecutionProfileStore profiles = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profiles);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "pi-remediation", "project-1", "CODING_AGENT", "Pi remediation", AgentRuntimeType.PI,
                "provider-1", "", "", 0L, "tool-v1", 1L, true, 8L,
                List.of(AgentRuntimeCapability.PI_QA_REMEDIATION_V2));
        profileService.register(profile);
        profileService.bindProjectDefault("project-1", "CODING_AGENT", profile.profileId());
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();

        AgentExecutionProfileSnapshot prepared = resolver(profileService, snapshots, false).prepareSnapshot(
                task(), AgentRole.CODING_AGENT, "future-coding-stage", 2,
                AgentRuntimeCapability.PI_QA_REMEDIATION_V2);

        assertEquals("future-coding-stage", prepared.stageRunId());
        assertEquals(2, prepared.attemptNo());
        assertEquals(AgentRuntimeType.PI, prepared.runtimeType());
        assertTrue(prepared.hasCapability(AgentRuntimeCapability.PI_QA_REMEDIATION_V2));
        assertTrue(prepared.hasValidIntegrityHash());
        assertTrue(snapshots.findByStageRunId("future-coding-stage").isEmpty(),
                "prepareSnapshot must not create a snapshot row");
    }

    @Test
    void shouldFailClosedWhenPreparedTargetIsNotEligiblePiV2() {
        InMemoryAgentExecutionProfileStore profiles = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profiles);
        AgentExecutionProfile claude = profile("claude", AgentRuntimeType.CLAUDE_CODE);
        profileService.register(claude);
        profileService.bindProjectDefault("project-1", "CODING_AGENT", claude.profileId());
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        EngineRequirementExecutionProfileResolver resolver = resolver(profileService, snapshots, false);

        assertThrows(IllegalStateException.class, () -> resolver.prepareSnapshot(
                task(), AgentRole.CODING_AGENT, "future-claude", 2,
                AgentRuntimeCapability.PI_QA_REMEDIATION_V2));

        AgentExecutionProfile piWithoutCapability = new AgentExecutionProfile(
                "pi-legacy", "project-1", "CODING_AGENT", "Pi legacy", AgentRuntimeType.PI,
                "provider-1", "", "", 0L, "tool-v1", 1L, true, 1L, List.of());
        profileService.register(piWithoutCapability);
        profileService.bindProjectDefault("project-1", "CODING_AGENT", piWithoutCapability.profileId());
        assertThrows(IllegalStateException.class, () -> resolver.prepareSnapshot(
                task(), AgentRole.CODING_AGENT, "future-pi-legacy", 2,
                AgentRuntimeCapability.PI_QA_REMEDIATION_V2));
        assertTrue(snapshots.findByStageRunId("future-claude").isEmpty());
        assertTrue(snapshots.findByStageRunId("future-pi-legacy").isEmpty());
    }

    @Test
    void shouldFreezeSortedCapabilitiesAndProfileVersionInCanonicalSnapshot() {
        InMemoryAgentExecutionProfileStore profiles = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profiles);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "pi-v2", "project-1", "CODING_AGENT", "Pi v2", AgentRuntimeType.PI,
                "provider-1", "", "", 0L, "tool-v1", 1L, true, 7L,
                List.of(
                        AgentRuntimeCapability.PI_QA_REMEDIATION_V2,
                        AgentRuntimeCapability.PI_AGENT_STATE_V2
                )
        );
        profileService.register(profile);
        profileService.bindProjectDefault("project-1", "CODING_AGENT", profile.profileId());
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();

        resolver(profileService, snapshots, false)
                .resolve(task(), AgentRole.CODING_AGENT, "stage-capabilities", 1);

        AgentExecutionProfileSnapshot snapshot = snapshots.findByStageRunId("stage-capabilities")
                .orElseThrow();
        assertEquals(7L, snapshot.profileVersion());
        assertEquals(
                List.of(
                        AgentRuntimeCapability.PI_AGENT_STATE_V2,
                        AgentRuntimeCapability.PI_QA_REMEDIATION_V2
                ),
                snapshot.capabilities()
        );
        assertTrue(snapshot.snapshotJson().contains(
                "\"capabilities\":[\"PI_AGENT_STATE_V2\",\"PI_QA_REMEDIATION_V2\"]"
        ));
        assertTrue(snapshot.hasValidIntegrityHash());
    }

    @Test
    void shouldFreezeAgentStateV2SchemaWhenCapabilityAndKillSwitchAreOn() {
        InMemoryAgentExecutionProfileStore profiles = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profiles);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "pi-state-v2", "project-1", "CODING_AGENT", "Pi state v2", AgentRuntimeType.PI,
                "provider-1", "", "", 0L, "tool-v1", 1L, true, 3L,
                List.of(AgentRuntimeCapability.PI_AGENT_STATE_V2)
        );
        profileService.register(profile);
        profileService.bindProjectDefault("project-1", "CODING_AGENT", profile.profileId());
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        EngineRequirementExecutionProfileResolver resolver = new EngineRequirementExecutionProfileResolver(
                profileService,
                new AgentExecutionProfileSnapshotService(snapshots),
                snapshots,
                false,
                null,
                null,
                "FACTS_V1",
                true,
                8192
        );

        resolver.resolve(task(), AgentRole.CODING_AGENT, "stage-state-v2", 1);
        String snapshotJson = snapshots.findByStageRunId("stage-state-v2").orElseThrow().snapshotJson();

        assertTrue(snapshotJson.contains("\"agentStateSchemaVersion\":\"rd-agent-state/v2\""));
        assertTrue(snapshotJson.contains("\"dynamicStateEnabled\":true"));
        assertTrue(snapshotJson.contains("\"capabilities\":[\"PI_AGENT_STATE_V2\"]"));
    }

    @Test
    void shouldDecodeLegacySnapshotWithoutCapabilitiesAsDisabled() {
        String legacyJson = "{\"snapshotVersion\":1,\"runtimeType\":\"PI\"}";
        AgentExecutionProfileSnapshot legacy = new AgentExecutionProfileSnapshot(
                "snapshot-legacy", "stage-legacy", "task-legacy", "QA_AGENT", 1,
                AgentRuntimeType.PI, legacyJson, AgentExecutionProfileSnapshot.sha256(legacyJson), 1L
        );

        assertEquals(0L, legacy.profileVersion());
        assertEquals(List.of(), legacy.capabilities());
        assertFalse(legacy.hasCapability(AgentRuntimeCapability.PI_AGENT_STATE_V2));
    }

    @Test
    void shouldFreezeTheAuthorizedTaskOverrideAndReuseItAfterBindingChanges() {
        InMemoryAgentExecutionProfileStore profiles = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profiles);
        AgentExecutionProfile claude = profile("claude", AgentRuntimeType.CLAUDE_CODE);
        AgentExecutionProfile pi = profile("pi", AgentRuntimeType.PI);
        profileService.register(claude);
        profileService.register(pi);
        profileService.bindProjectDefault("project-1", "CODING_AGENT", claude.profileId());
        profileService.setTaskOverride("task-1", "project-1", "CODING_AGENT", pi.profileId());
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        EngineRequirementExecutionProfileResolver resolver = resolver(profileService, snapshots, false);

        RequirementExecutionProfileResolution first = resolver.resolve(
                task(), AgentRole.CODING_AGENT, "stage-1", 1
        );
        profileService.setTaskOverride("task-1", "project-1", "CODING_AGENT", claude.profileId());
        RequirementExecutionProfileResolution second = resolver.resolve(
                task(), AgentRole.CODING_AGENT, "stage-1", 1
        );

        assertEquals(first.snapshotId(), second.snapshotId());
        assertEquals(AgentRuntimeType.PI, snapshots.findByStageRunId("stage-1").orElseThrow().runtimeType());
        assertTrue(snapshots.findByStageRunId("stage-1").orElseThrow().hasValidIntegrityHash());
    }

    @Test
    void shouldProduceCompatibilitySnapshotsWhenNoRegisteredProfileExists() {
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        AgentRuntimeType codingRuntime = resolver(
                new AgentExecutionProfileService(new InMemoryAgentExecutionProfileStore()), snapshots, false
        ).resolve(task(), AgentRole.CODING_AGENT, "stage-coding", 1).resolved()
                ? snapshots.findByStageRunId("stage-coding").orElseThrow().runtimeType()
                : null;
        AgentRuntimeType reviewerRuntime = resolver(
                new AgentExecutionProfileService(new InMemoryAgentExecutionProfileStore()), snapshots, true
        ).resolve(task(), AgentRole.REQUIREMENT_REVIEWER, "stage-reviewer", 1).resolved()
                ? snapshots.findByStageRunId("stage-reviewer").orElseThrow().runtimeType()
                : null;

        assertEquals(AgentRuntimeType.CLAUDE_CODE, codingRuntime);
        assertEquals(AgentRuntimeType.CLAUDE_CODE, reviewerRuntime);
    }

    @Test
    void shouldFreezeQaCompatibilityPolicyWithoutWriteTools() {
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        EngineRequirementExecutionProfileResolver resolver = new EngineRequirementExecutionProfileResolver(
                new AgentExecutionProfileService(new InMemoryAgentExecutionProfileStore()),
                new AgentExecutionProfileSnapshotService(snapshots),
                snapshots,
                false
        );
        resolver.resolve(task(), AgentRole.QA_AGENT, "stage-qa", 1);
        String snapshotJson = snapshots.findByStageRunId("stage-qa").orElseThrow().snapshotJson();
        assertTrue(snapshotJson.contains("\"default-qa\""));
        assertTrue(snapshotJson.contains("\"toolPolicyVersion\":2"));
        assertTrue(snapshotJson.contains("\"read\""));
        assertTrue(snapshotJson.contains("\"deny\""));
        assertTrue(snapshotJson.contains("\"edit\""));
        assertTrue(snapshotJson.contains("\"write\""));
        assertTrue(snapshotJson.contains("\"effectiveAllow\":[\"bash\",\"rd_submit_result\",\"read\"]"));
        assertFalse(snapshotJson.contains("\"effectiveAllow\":[\"read\",\"bash\",\"edit\""));
        assertFalse(snapshotJson.contains("\"effectiveAllow\":[\"bash\",\"edit\""));
        assertFalse(snapshotJson.contains("\"effectiveAllow\":[\"write\""));
    }

    @Test
    void shouldResolveRegisteredQaProfileWithReadOnlyToolPolicyV2() {
        InMemoryAgentExecutionProfileStore profiles = new InMemoryAgentExecutionProfileStore();
        AgentExecutionProfileService profileService = new AgentExecutionProfileService(profiles);
        AgentToolPolicyService toolPolicyService = new AgentToolPolicyService(new InMemoryAgentToolPolicyStore());
        toolPolicyService.register(AgentToolPolicy.defaultQaPolicy());
        AgentExecutionProfile qaProfile = new AgentExecutionProfile(
                "pi-qa-nextjs-kbr",
                "7487468535443230720",
                "QA_AGENT",
                "Pi QA Next.js KBR",
                AgentRuntimeType.PI,
                "longcat-anthropic",
                "",
                "",
                0L,
                "default-qa",
                2L,
                true,
                1L
        );
        profileService.register(qaProfile);
        profileService.bindProjectDefault("7487468535443230720", "QA_AGENT", qaProfile.profileId());
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        EngineRequirementExecutionProfileResolver resolver = new EngineRequirementExecutionProfileResolver(
                profileService,
                new AgentExecutionProfileSnapshotService(snapshots),
                snapshots,
                false,
                null,
                toolPolicyService
        );
        resolver.resolve(qaTask(), AgentRole.QA_AGENT, "stage-qa-kbr", 1);
        String snapshotJson = snapshots.findByStageRunId("stage-qa-kbr").orElseThrow().snapshotJson();
        assertTrue(snapshotJson.contains("\"profileId\":\"pi-qa-nextjs-kbr\""));
        assertTrue(snapshotJson.contains("\"toolPolicyVersion\":2"));
        assertTrue(snapshotJson.contains("\"effectiveAllow\":[\"bash\",\"rd_submit_result\",\"read\"]"));
        assertFalse(snapshotJson.contains("\"effectiveAllow\":[\"read\",\"bash\",\"edit\""));
        assertFalse(snapshotJson.contains("\"effectiveAllow\":[\"bash\",\"edit\""));
        assertFalse(snapshotJson.contains("\"effectiveAllow\":[\"write\""));
    }

    @Test
    void shouldFreezeContextProtocolFieldsInSnapshotJson() {
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        EngineRequirementExecutionProfileResolver resolver = new EngineRequirementExecutionProfileResolver(
                new AgentExecutionProfileService(new InMemoryAgentExecutionProfileStore()),
                new AgentExecutionProfileSnapshotService(snapshots),
                snapshots,
                false,
                null,
                null,
                "FACTS_V1",
                true,
                16384
        );
        resolver.resolve(task(), AgentRole.CODING_AGENT, "stage-context", 1);
        String snapshotJson = snapshots.findByStageRunId("stage-context").orElseThrow().snapshotJson();
        RequirementExecutionProfileResolution resolution = resolver.resolve(
                task(), AgentRole.CODING_AGENT, "stage-context", 1
        );
        assertTrue(snapshotJson.contains("\"contextProtocolVersion\":\"FACTS_V1\""));
        assertEquals("FACTS_V1", resolution.contextProtocolVersion());
        assertTrue(resolution.dynamicStateEnabled());
        assertEquals(snapshotJson, resolution.snapshotJson());
        assertTrue(snapshotJson.contains("\"agentStateSchemaVersion\":\"rd-agent-state/v1\""));
        assertTrue(snapshotJson.contains("\"dynamicStateEnabled\":true"));
        assertTrue(snapshotJson.contains("\"maxInjectedStateBytes\":16384"));
        assertTrue(snapshotJson.contains("\"toolRetryPolicyVersion\":\"rd-tool-retry/v1\""));
        assertTrue(snapshotJson.contains("\"rd_todo_rewrite\""));
        assertTrue(snapshotJson.contains("\"rd_todo_update_status\""));
        assertTrue(snapshotJson.contains("\"rd_record_fact\""));
    }

    private EngineRequirementExecutionProfileResolver resolver(
            AgentExecutionProfileService profileService,
            InMemoryAgentExecutionProfileSnapshotStore snapshotStore,
            boolean openAiChatEnabled
    ) {
        return new EngineRequirementExecutionProfileResolver(
                profileService,
                new AgentExecutionProfileSnapshotService(snapshotStore),
                snapshotStore,
                openAiChatEnabled
        );
    }

    private AgentExecutionProfile profile(String id, AgentRuntimeType runtimeType) {
        return new AgentExecutionProfile(
                id, "project-1", "CODING_AGENT", id, runtimeType, "provider-1", "", "", "tool-v1", true, 1L
        );
    }

    private RdRequirementTask task() {
        return RdRequirementTask.created(
                "task-1",
                new CreateRequirementTaskCommand(
                        "title", "P1", "ADMIN", "", "", "project-1", "", "",
                        "https://github.com/acme/repo.git", "acme", "repo", "main",
                        "expected", List.of("test"), List.of(), false
                ),
                1L
        );
    }

    private RdRequirementTask qaTask() {
        return RdRequirementTask.created(
                "task-qa-1",
                new CreateRequirementTaskCommand(
                        "title", "P1", "ADMIN", "", "", "7487468535443230720", "", "",
                        "https://github.com/acme/repo.git", "acme", "repo", "main",
                        "expected", List.of("test"), List.of(), false
                ),
                1L
        );
    }
}
