package com.wish.rd.bootstrap.provider;

import com.wish.rd.bootstrap.provider.impl.RedisModelHealthStateStore;
import com.wish.rd.exec.repair.health.ModelHealthStore;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Redis proof that circuit and HALF_OPEN ownership are shared across executor instances. */
@EnabledIfSystemProperty(named = "rd.bot.redis.test", matches = "true")
class RedisModelHealthStateStoreIntegrationTest {

    @Test
    void shouldAllowOnlyOneHalfOpenProbeAcrossInstances() throws InterruptedException {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient client = Redisson.create(config);
        String keyPrefix = "rd-bot:test:model-health:" + UUID.randomUUID();
        ModelCircuitBreakerPolicy policy = new ModelCircuitBreakerPolicy(true, 2, 50L);
        ModelHealthStore first = new ModelHealthStore(
                policy, new RedisModelHealthStateStore(client, keyPrefix));
        ModelHealthStore second = new ModelHealthStore(
                policy, new RedisModelHealthStateStore(client, keyPrefix));
        try {
            first.markFailure("deepseek");
            second.markFailure("deepseek");
            assertFalse(first.allowCall("deepseek"));

            Thread.sleep(75L);

            assertTrue(first.allowCall("deepseek"));
            assertFalse(second.allowCall("deepseek"));
        } finally {
            client.getKeys().deleteByPattern(keyPrefix + "*");
            client.shutdown();
        }
    }
}
