package com.wish.rd.bootstrap.lock;

import com.wish.rd.rag.lock.DistributedLockExecutor;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DistributedLockConfiguration} 装配测试。
 */
class DistributedLockConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DistributedLockConfiguration.class);

    @Test
    void shouldAllowLocalLockModeWithoutRedissonClient() {
        contextRunner
                .withPropertyValues("rd.distributed-lock.mode=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(DistributedLockExecutor.class);
                    assertThat(context).doesNotHaveBean(RedissonClient.class);
                });
    }
}
