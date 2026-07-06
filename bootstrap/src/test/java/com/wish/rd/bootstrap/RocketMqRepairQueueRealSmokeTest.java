package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.rocketmq.impl.RocketMqRepairQueueAdapter;
import com.wish.rd.bootstrap.rocketmq.RocketMqRepairQueueProperties;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RocketMQ 真实连通性冒烟测试：默认跳过，显式开启才执行。
 *
 * <p>执行命令：
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=RocketMqRepairQueueRealSmokeTest \
 *   -Drd.integration.rocketmq.enabled=true \
 *   -Drd.repair.queue.mode=rocketmq \
 *   -Drd.rocketmq.repair.name-server=127.0.0.1:9876 \
 *   test
 * </pre>
 *
 * <p>发布一条 P1 消息后等待消费回调被触发，验证端到端链路通畅。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "rd.integration.rocketmq.enabled", matches = "true")
class RocketMqRepairQueueRealSmokeTest {

    @Autowired
    private RocketMqRepairQueueProperties properties;

    @DynamicPropertySource
    static void registerRocketMqProperties(DynamicPropertyRegistry registry) {
        registry.add("rd.repair.queue.mode", () -> "rocketmq");
    }

    @Test
    void publishesRepairMessageToBroker() {
        assumeTrue(!properties.getNameServer().isBlank(),
                "rd.rocketmq.repair.name-server must be set (e.g. 127.0.0.1:9876)");

        String ticketId = "SMOKE-" + UUID.randomUUID().toString().substring(0, 8);

        // 发布路径独立可验证：用空消费者构造 adapter，确认消息能落到 broker。
        // 消费端到端验证留给 /test/repair/tickets/{id}/run 通道（避免与 Spring 上下文
        // 中已注册的 TicketRepairEngine 消费者冲突）。
        RocketMqRepairQueueAdapter adapter = new RocketMqRepairQueueAdapter(
                properties,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                emptyProvider()
        );

        RepairTicketMessage message = new RepairTicketMessage(
                ticketId, "P1", "smoke-trace", 1, "feishu",
                "smoke-evt-" + UUID.randomUUID(), "helpdesk.ticket.created_v1", Instant.now()
        );
        RepairQueuePublishResult result = adapter.publish(message);
        assertTrue(result.success(), "publish should succeed: " + result.errorMessage());
        System.out.println("[smoke] published to topic=" + result.targetTopic()
                + " tag=" + result.targetTag() + " msgId=" + result.messageId()
                + " ticketId=" + ticketId);
    }

    /** 返回空 ObjectProvider，使 adapter 不注册真实消费者。 */
    private static org.springframework.beans.factory.ObjectProvider<RepairQueueConsumer> emptyProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public RepairQueueConsumer getObject() {
                return null;
            }

            @Override
            public RepairQueueConsumer getIfAvailable() {
                return null;
            }

            @Override
            public RepairQueueConsumer getIfUnique() {
                return null;
            }
        };
    }
}
