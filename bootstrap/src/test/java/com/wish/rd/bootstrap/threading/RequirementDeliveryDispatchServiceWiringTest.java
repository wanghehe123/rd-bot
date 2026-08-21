package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;

class RequirementDeliveryDispatchServiceWiringTest {

    @Test
    void shouldUseTheGeneralSchedulerWhenRedisStreamAddsAnotherTaskScheduler() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=memory")
                .withUserConfiguration(WiringConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(RequirementDeliveryDispatchService.class));
    }

    @Test
    void postgresModeFailsClosedWhenAuthoritativeStageStoreIsMissing() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(WiringConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("requirement delivery requires an authoritative stage command store "
                                    + "when rd.knowledge.store=postgres");
                });
    }

    @Test
    void explicitMemoryModeMayUseInMemoryStageStore() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=memory")
                .withUserConfiguration(WiringConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RequirementDeliveryDispatchService dispatcher =
                            context.getBean(RequirementDeliveryDispatchService.class);
                    assertThat(ReflectionTestUtils.getField(dispatcher, "stageCommandStore"))
                            .isInstanceOf(InMemoryRequirementStageCommandStore.class);
                });
    }

    @Test
    void postgresModeRejectsAnInMemoryStageStoreBean() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(WiringConfiguration.class, InMemoryStageStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("requirement delivery cannot use an in-memory stage command "
                                    + "store when rd.knowledge.store=postgres");
                });
    }

    @Test
    void productionSchedulingConfigurationSuppliesWeightsAgingAndQuotaPolicyToDispatcher() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "rd.knowledge.store=memory",
                        "rd.requirement-delivery.scheduling.max-per-project=4",
                        "rd.requirement-delivery.scheduling.max-docker=1",
                        "rd.requirement-delivery.scheduling.max-browser-qa=1",
                        "rd.requirement-delivery.scheduling.max-provider=2",
                        "rd.requirement-delivery.scheduling.max-per-provider=1",
                        "rd.requirement-delivery.scheduling.batch-size=1",
                        "rd.requirement-delivery.scheduling.aging-millis=1000",
                        "rd.requirement-delivery.scheduling.command-deadline-millis=120000",
                        "rd.requirement-delivery.scheduling.default-provider-id=provider-default",
                        "rd.requirement-delivery.scheduling.project-weights.project-heavy=2",
                        "rd.requirement-delivery.scheduling.project-weights.project-light=1"
                )
                .withUserConfiguration(WiringConfiguration.class, SchedulingPropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RequirementDeliverySchedulingProperties properties = context.getBean(
                            RequirementDeliverySchedulingProperties.class);
                    RequirementDeliverySchedulingPolicy policy = properties.toPolicy();
                    RequirementDeliveryDispatchService dispatcher = context.getBean(
                            RequirementDeliveryDispatchService.class);

                    assertThat(policy.limits().maxDocker()).isEqualTo(1);
                    assertThat(policy.limits().maxPerProvider()).isEqualTo(1);
                    assertThat(policy.projectWeights()).containsEntry("project-heavy", 2);
                    assertThat(policy.commandDeadlineMillis()).isEqualTo(120_000L);
                    assertThat(ReflectionTestUtils.getField(dispatcher, "schedulingPolicy"))
                            .isEqualTo(policy);

                    FairRequirementDeliveryClaimPlanner planner = new FairRequirementDeliveryClaimPlanner();
                    long now = 10_000L;
                    RequirementStageCommand freshP0 = providerDockerCommand(
                            "fresh-p0", "project-heavy", "provider-heavy", "P0", now);
                    RequirementStageCommand agedP2 = providerDockerCommand(
                            "aged-p2", "project-light", "provider-light", "P2", now - 4_000L);

                    assertThat(planner.planStageCommands(
                            List.of(freshP0, agedP2), List.of(), policy.limits(), now, policy.projectWeights()))
                            .extracting(RequirementStageCommand::commandId)
                            .containsExactly("aged-p2");

                    RequirementStageCommand heavyLoad = RequirementStageCommand.pending(
                            "heavy-load", "task-heavy-load", 1L, 1L,
                            "REQUIREMENT_DELIVERY", "HOST", 0, 3, now + 60_000L,
                            ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                            "project-heavy", "host-control-plane", "P1", now - 100L
                    ).claimed("worker-heavy", now + 60_000L, now);
                    RequirementStageCommand lightLoad = RequirementStageCommand.pending(
                            "light-load", "task-light-load", 1L, 1L,
                            "REQUIREMENT_DELIVERY", "HOST", 0, 3, now + 60_000L,
                            ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                            "project-light", "host-control-plane", "P1", now - 100L
                    ).claimed("worker-light", now + 60_000L, now);

                    assertThat(planner.planStageCommands(
                            List.of(freshP0, agedP2),
                            List.of(heavyLoad, lightLoad),
                            policy.limits(),
                            now,
                            policy.projectWeights()
                    )).extracting(RequirementStageCommand::commandId).containsExactly("fresh-p0");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(RequirementDeliveryDispatchService.class)
    static class WiringConfiguration {

        @Bean
        RequirementDeliveryEngine requirementDeliveryEngine() {
            return mock(RequirementDeliveryEngine.class);
        }

        @Bean
        DeepRetrievalOrchestrator deepRetrievalOrchestrator() {
            return mock(DeepRetrievalOrchestrator.class);
        }

        @Bean(name = RdBotThreadPoolConfiguration.REQUIREMENT_DELIVERY_EXECUTOR_BEAN)
        AsyncTaskExecutor requirementDeliveryExecutor() {
            return new TaskExecutorAdapter(new SyncTaskExecutor());
        }

        @Bean
        RequirementDeliveryJobStore requirementDeliveryJobStore() {
            return new InMemoryRequirementDeliveryJobStore();
        }

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return SnowflakeIdGenerator.defaultGenerator();
        }

        @Bean
        TaskRetryCheckpointStore taskRetryCheckpointStore() {
            return new InMemoryTaskRetryCheckpointStore();
        }

        @Bean
        RagStreamTaskRegistry ragStreamTaskRegistry() {
            return new RagStreamTaskRegistry(
                    new InMemoryRdTaskStore(),
                    new InMemoryRdTaskStatusEventStore(),
                    SnowflakeIdGenerator.defaultGenerator()
            );
        }

        @Bean(name = "taskScheduler")
        TaskScheduler taskScheduler() {
            return mock(TaskScheduler.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryStageStoreConfiguration {

        @Bean
        RequirementStageCommandStore requirementStageCommandStore() {
            return new InMemoryRequirementStageCommandStore();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RequirementDeliverySchedulingProperties.class)
    static class SchedulingPropertiesConfiguration {
    }

    private static RequirementStageCommand providerDockerCommand(
            String commandId,
            String projectId,
            String providerId,
            String priority,
            long createdAtEpochMillis
    ) {
        return RequirementStageCommand.pending(
                commandId,
                "task-" + commandId,
                1L,
                1L,
                "CODING_AGENT",
                "ROLE_EXECUTION:CODING_AGENT",
                0,
                3,
                createdAtEpochMillis + 60_000L,
                ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                projectId,
                providerId,
                priority,
                createdAtEpochMillis
        );
    }
}
