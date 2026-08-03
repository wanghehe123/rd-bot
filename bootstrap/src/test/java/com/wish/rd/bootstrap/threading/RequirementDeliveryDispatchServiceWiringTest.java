package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RequirementDeliveryDispatchServiceWiringTest {

    @Test
    void shouldUseTheGeneralSchedulerWhenRedisStreamAddsAnotherTaskScheduler() {
        new ApplicationContextRunner()
                .withUserConfiguration(WiringConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(RequirementDeliveryDispatchService.class));
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

        @Bean(name = "redisStreamRepairQueueLeaseTaskScheduler")
        TaskScheduler redisStreamRepairLeaseScheduler() {
            return mock(TaskScheduler.class);
        }
    }
}
