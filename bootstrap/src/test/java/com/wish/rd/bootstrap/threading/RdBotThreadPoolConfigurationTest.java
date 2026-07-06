package com.wish.rd.bootstrap.threading;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdBotThreadPoolConfigurationTest {

    @Test
    void shouldExposeIsolatedThreadPoolsWithConfigurableSizes() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(RdBotThreadPoolConfiguration.class)
                .withPropertyValues(
                        "rd.thread-pools.requirement-delivery.core-size=2",
                        "rd.thread-pools.requirement-delivery.max-size=3",
                        "rd.thread-pools.requirement-delivery.queue-capacity=11",
                        "rd.thread-pools.executor-io.core-size=4",
                        "rd.thread-pools.executor-io.max-size=6",
                        "rd.thread-pools.repair-queue.core-size=1",
                        "rd.thread-pools.ingestion.core-size=2",
                        "rd.thread-pools.maintenance.core-size=1"
                );

        contextRunner.run(context -> {
            Map<String, ThreadPoolTaskExecutor> executors = context.getBeansOfType(ThreadPoolTaskExecutor.class);

            assertTrue(executors.containsKey(RdBotThreadPoolConfiguration.REQUIREMENT_DELIVERY_EXECUTOR_BEAN));
            assertTrue(executors.containsKey(RdBotThreadPoolConfiguration.EXECUTOR_IO_EXECUTOR_BEAN));
            assertTrue(executors.containsKey(RdBotThreadPoolConfiguration.REPAIR_QUEUE_EXECUTOR_BEAN));
            assertTrue(executors.containsKey(RdBotThreadPoolConfiguration.INGESTION_EXECUTOR_BEAN));
            assertTrue(executors.containsKey(RdBotThreadPoolConfiguration.MAINTENANCE_EXECUTOR_BEAN));

            ThreadPoolTaskExecutor requirement = executors.get(
                    RdBotThreadPoolConfiguration.REQUIREMENT_DELIVERY_EXECUTOR_BEAN);
            assertEquals(2, requirement.getCorePoolSize());
            assertEquals(3, requirement.getMaxPoolSize());
            assertEquals(11, requirement.getQueueCapacity());
            assertTrue(requirement.getThreadNamePrefix().startsWith("rd-requirement-"));
        });
    }
}
