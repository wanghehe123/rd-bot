package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.threading.RequirementStageCommandFactory;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationContinuation;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementPublicationStageContinuationAdapterTest {

    @Test
    void rejectsZeroFenceAndNegativeVersionContinuationAtTheDurableBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementPublicationContinuation(
                "sha256:" + "c".repeat(64), "task-zero-fence", 5L, 0L, "project-a", "P1"));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPublicationContinuation(
                "sha256:" + "d".repeat(64), "task-negative-version", -1L, 9L, "project-a", "P1"));
    }

    @Test
    void enqueuesPublicationContinuationWithBoundedProviderMetadata() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        AtomicLong ids = new AtomicLong(1L);
        RequirementPublicationStageContinuationAdapter adapter =
                new RequirementPublicationStageContinuationAdapter(
                        store, new SnowflakeIdGenerator(1, 2, ids::getAndIncrement), 3);
        RequirementPublicationContinuation continuation = new RequirementPublicationContinuation(
                "sha256:" + "a".repeat(64), "task-publication", 5L, 9L, "project-a", "P1");

        adapter.enqueue(continuation);

        RequirementStageCommand command = store.find(
                continuation.taskId(), "REQUIREMENT_DELIVERY", continuation.stage()).orElseThrow();
        assertAll(
                () -> assertEquals(command.createdAtEpochMillis() + 60L * 60L * 1000L,
                        command.deadlineEpochMillis()),
                () -> assertEquals("local-provider", command.providerId()),
                () -> assertEquals(ScheduleResourceClass.PROVIDER, command.resourceClass()),
                () -> assertEquals(Set.of(ScheduleResourceClass.PROVIDER), command.resourceRequirements())
        );
    }

    @Test
    void usesCodingAgentProfileForPublicationContinuationWhenAvailable() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        AtomicLong ids = new AtomicLong(2L);
        AgentExecutionProfileService profiles = new AgentExecutionProfileService(
                new InMemoryAgentExecutionProfileStore());
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "profile-coding", "project-a", "CODING_AGENT", "Coding agent", AgentRuntimeType.PI,
                "provider-coding", "", "", 0L, "tool-policy-coding", 1L, true, 1L);
        profiles.register(profile);
        profiles.bindProjectDefault("project-a", "CODING_AGENT", profile.profileId());
        RequirementDeliverySchedulingPolicy policy = new RequirementDeliverySchedulingPolicy(
                FairScheduleLimits.defaults(), java.util.Map.of(), 90_000L, "provider-default");
        RequirementStageCommandFactory factory = new RequirementStageCommandFactory(
                new SnowflakeIdGenerator(1, 2, ids::getAndIncrement), 3, policy, profiles);
        RequirementPublicationStageContinuationAdapter adapter =
                new RequirementPublicationStageContinuationAdapter(store, factory);
        RequirementPublicationContinuation continuation = new RequirementPublicationContinuation(
                "sha256:" + "b".repeat(64), "task-profile", 5L, 9L, "project-a", "P1");

        adapter.enqueue(continuation);

        RequirementStageCommand command = store.find(
                continuation.taskId(), "REQUIREMENT_DELIVERY", continuation.stage()).orElseThrow();
        assertEquals("provider-coding", command.providerId());
    }

    @Test
    void rejectsAConflictingEffectiveCommandReturnedByTheStore() {
        RequirementStageCommandStore store = mock(RequirementStageCommandStore.class);
        AtomicLong ids = new AtomicLong(21L);
        when(store.enqueue(any())).thenAnswer(invocation -> {
            RequirementStageCommand requested = invocation.getArgument(0, RequirementStageCommand.class);
            return new RequirementStageCommand(
                    requested.commandId(), requested.taskId(), requested.taskVersion() + 1L,
                    requested.fencingToken(), requested.role(), requested.stage(), requested.attemptNo(),
                    requested.maxAttempts(), requested.deadlineEpochMillis(), requested.resourceClass(),
                    requested.resourceRequirements(), requested.projectId(), requested.providerId(),
                    requested.priorityRank(), requested.status(), requested.leaseOwner(),
                    requested.leaseUntilEpochMillis(), requested.nextVisibleAtEpochMillis(), requested.lastError(),
                    requested.createdAtEpochMillis(), requested.updatedAtEpochMillis());
        });
        RequirementPublicationStageContinuationAdapter adapter =
                new RequirementPublicationStageContinuationAdapter(
                        store, new SnowflakeIdGenerator(1, 2, ids::getAndIncrement), 3);
        RequirementPublicationContinuation continuation = new RequirementPublicationContinuation(
                "sha256:" + "e".repeat(64), "task-conflict", 5L, 9L, "project-a", "P1");

        assertThrows(IllegalStateException.class, () -> adapter.enqueue(continuation));

        verify(store).enqueue(any(RequirementStageCommand.class));
    }

    @Test
    void classifiesOrdinaryPublicationAsCodingProviderWork() {
        AtomicLong ids = new AtomicLong(3L);
        AgentExecutionProfileService profiles = new AgentExecutionProfileService(
                new InMemoryAgentExecutionProfileStore());
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "profile-coding", "project-a", "CODING_AGENT", "Coding agent", AgentRuntimeType.PI,
                "provider-coding", "", "", 0L, "tool-policy-coding", 1L, true, 1L);
        profiles.register(profile);
        profiles.bindProjectDefault("project-a", "CODING_AGENT", profile.profileId());
        RequirementStageCommandFactory factory = new RequirementStageCommandFactory(
                new SnowflakeIdGenerator(1, 2, ids::getAndIncrement),
                3,
                new RequirementDeliverySchedulingPolicy(
                        FairScheduleLimits.defaults(), java.util.Map.of(), 90_000L, "provider-default"),
                profiles
        );

        RequirementStageCommand command = factory.createPendingCommand(
                "task-publication", 5L, 9L, "REQUIREMENT_DELIVERY", "PUBLICATION",
                "project-a", "P1", "provider-previous", 1_000L);

        assertAll(
                () -> assertEquals("provider-coding", command.providerId()),
                () -> assertEquals(ScheduleResourceClass.PROVIDER, command.resourceClass()),
                () -> assertEquals(Set.of(ScheduleResourceClass.PROVIDER), command.resourceRequirements())
        );
    }
}
