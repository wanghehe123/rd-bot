package com.wish.rd.bootstrap.provider;

import com.wish.rd.exec.repair.health.ModelHealthStateStore;
import com.wish.rd.exec.repair.health.impl.InMemoryModelHealthStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ModelHealthStoreConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelHealthStoreConfiguration.class);

    @Test
    void redisModeFailsStartupWithoutSharedRedisClient() {
        contextRunner
                .withPropertyValues("rd.executor.docker.circuit-breaker.state-store=redis")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("distributedLockRedissonClient"));
    }

    @Test
    void memoryModeRegistersOnlyExplicitInProcessStateStore() {
        contextRunner
                .withPropertyValues("rd.executor.docker.circuit-breaker.state-store=memory")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelHealthStateStore.class);
                    assertThat(context.getBean(ModelHealthStateStore.class))
                            .isInstanceOf(InMemoryModelHealthStateStore.class);
                });
    }
}
