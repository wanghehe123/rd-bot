package com.wish.rd.bootstrap.rocketmq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 修复队列 Bean 装配。
 *
 * <p>仅注册 {@link RocketMqRepairQueueProperties} 为 bean；适配器本身由组件扫描 + 条件装配进入 IOC。
 */
@Configuration
public class RocketMqRepairQueueConfiguration {

    /**
     * 注册队列配置属性 bean。
     *
     * @return 配置属性
     */
    @Bean
    @ConfigurationProperties(prefix = "rd.rocketmq.repair")
    public RocketMqRepairQueueProperties rocketMqRepairQueueProperties() {
        return new RocketMqRepairQueueProperties();
    }
}
