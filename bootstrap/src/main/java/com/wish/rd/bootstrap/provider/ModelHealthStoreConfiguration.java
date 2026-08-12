package com.wish.rd.bootstrap.provider;

import com.wish.rd.bootstrap.provider.impl.RedisModelHealthStateStore;
import com.wish.rd.exec.repair.health.ModelHealthStateStore;
import com.wish.rd.exec.repair.health.impl.InMemoryModelHealthStateStore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects the shared Provider circuit state backend for Docker execution. */
@Configuration(proxyBeanMethods = false)
public class ModelHealthStoreConfiguration {

    /**
     * Creates the production Redis state adapter.
     *
     * @param redissonClient shared Redisson client used by distributed correctness controls
     * @return Redis-backed health state
     */
    @Bean
    @ConditionalOnMissingBean(ModelHealthStateStore.class)
    @ConditionalOnProperty(
            prefix = "rd.executor.docker.circuit-breaker",
            name = "state-store",
            havingValue = "redis",
            matchIfMissing = true
    )
    public ModelHealthStateStore redisModelHealthStateStore(
            @Qualifier("distributedLockRedissonClient") RedissonClient redissonClient
    ) {
        return new RedisModelHealthStateStore(redissonClient);
    }

    /**
     * Creates local circuit state only when an operator explicitly selects memory mode.
     *
     * @return in-process health state
     */
    @Bean
    @ConditionalOnMissingBean(ModelHealthStateStore.class)
    @ConditionalOnProperty(
            prefix = "rd.executor.docker.circuit-breaker",
            name = "state-store",
            havingValue = "memory"
    )
    public ModelHealthStateStore inMemoryModelHealthStateStore() {
        return new InMemoryModelHealthStateStore();
    }
}
