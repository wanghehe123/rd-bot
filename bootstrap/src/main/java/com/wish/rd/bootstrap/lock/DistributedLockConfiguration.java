package com.wish.rd.bootstrap.lock;

import com.wish.rd.bootstrap.lock.impl.RedissonDistributedLockExecutor;

import com.wish.rd.rag.lock.DistributedLockExecutor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RD-Bot 分布式锁装配。
 */
@Configuration
public class DistributedLockConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "rd.distributed-lock.mode", havingValue = "redisson", matchIfMissing = true)
    public RedissonClient distributedLockRedissonClient(
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword
    ) {
        Config config = new Config();
        String actualHost = redisHost == null || redisHost.isBlank() ? "localhost" : redisHost;
        config.useSingleServer()
                .setAddress("redis://" + actualHost + ":" + redisPort)
                .setPassword(redisPassword == null || redisPassword.isBlank() ? null : redisPassword);
        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnProperty(name = "rd.distributed-lock.mode", havingValue = "redisson", matchIfMissing = true)
    public DistributedLockExecutor redissonDistributedLockExecutor(RedissonClient distributedLockRedissonClient) {
        return new RedissonDistributedLockExecutor(distributedLockRedissonClient);
    }

    @Bean
    @ConditionalOnProperty(name = "rd.distributed-lock.mode", havingValue = "local")
    public DistributedLockExecutor localDistributedLockExecutor() {
        return DistributedLockExecutor.local();
    }
}
