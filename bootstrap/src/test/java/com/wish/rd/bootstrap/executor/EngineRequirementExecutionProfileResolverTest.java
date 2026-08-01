package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutionProfileResolver;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotService;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementExecutionProfileResolverTest {

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
        assertEquals(AgentRuntimeType.MODEL_ONLY, reviewerRuntime);
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
        assertTrue(snapshotJson.contains("\"read\""));
        assertTrue(snapshotJson.contains("\"deny\""));
        assertTrue(snapshotJson.contains("\"edit\""));
        assertTrue(snapshotJson.contains("\"write\""));
        assertFalse(snapshotJson.contains("\"effectiveAllow\":[\"read\",\"bash\",\"edit\""));
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
}
