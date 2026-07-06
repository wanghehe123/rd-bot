package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.rocketmq.impl.RocketMqRepairQueueAdapter;
import com.wish.rd.bootstrap.rocketmq.RocketMqRepairQueueProperties;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link RocketMqRepairQueueAdapter} 的消息组装与发布结果，不连接真实 broker：
 * 重写 createProducer/createConsumer 注入桩，避免网络调用。
 */
class RocketMqRepairQueueAdapterTest {

    @Test
    void publishesRoutingMessageWithTicketIdKeyAndPriorityTag() throws Exception {
        RocketMqRepairQueueProperties properties = newProperties();
        AtomicReference<Message> captured = new AtomicReference<>();
        ObjectMapper objectMapper = new ObjectMapper();

        RocketMqRepairQueueAdapter adapter = new RocketMqRepairQueueAdapter(
                properties, objectMapper, emptyConsumerProvider()
        ) {
            @Override
            protected DefaultMQProducer createProducer(RocketMqRepairQueueProperties props) {
                DefaultMQProducer producer = new DefaultMQProducer("test-group") {
                    @Override
                    public SendResult send(Message msg) {
                        captured.set(msg);
                        SendResult result = new SendResult();
                        result.setSendStatus(org.apache.rocketmq.client.producer.SendStatus.SEND_OK);
                        result.setMsgId("mq-stub-id");
                        result.setOffsetMsgId("offset-1");
                        MessageQueue queue = new MessageQueue();
                        queue.setTopic(props.getTopic());
                        queue.setQueueId(0);
                        result.setMessageQueue(queue);
                        return result;
                    }

                    @Override
                    public void start() {
                        // no-op：不连 broker
                    }

                    @Override
                    public void shutdown() {
                        // no-op
                    }
                };
                producer.setNamesrvAddr(props.getNameServer());
                return producer;
            }
        };

        RepairTicketMessage message = new RepairTicketMessage(
                "FS-7001", "P1", "trace-7", 1, "feishu",
                "evt-7", "helpdesk.ticket.created_v1", Instant.parse("2026-06-21T00:00:00Z")
        );
        RepairQueuePublishResult result = adapter.publish(message);

        assertTrue(result.success());
        assertEquals("mq-stub-id", result.messageId());
        assertEquals("RD_BOT_REPAIR_TICKET", result.targetTopic());
        assertEquals("P1", result.targetTag());

        Message capturedMessage = captured.get();
        assertAll(
                () -> assertEquals("FS-7001", capturedMessage.getKeys()),
                () -> assertEquals("P1", capturedMessage.getTags()),
                () -> assertEquals("RD_BOT_REPAIR_TICKET", capturedMessage.getTopic()),
                () -> assertEquals("trace-7", capturedMessage.getUserProperty("traceId")),
                () -> assertEquals("1", capturedMessage.getUserProperty("attempt")),
                () -> assertEquals("feishu", capturedMessage.getUserProperty("source"))
        );
        // body 不得包含工单内容/secret
        String body = new String(capturedMessage.getBody());
        assertFalse(body.contains("title"));
        assertFalse(body.contains("accessToken"));
        assertFalse(body.contains("helpdeskToken"));
    }

    @Test
    void publishFailureReturnsFailureResult() {
        RocketMqRepairQueueProperties properties = newProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        RocketMqRepairQueueAdapter adapter = new RocketMqRepairQueueAdapter(
                properties, objectMapper, emptyConsumerProvider()
        ) {
            @Override
            protected DefaultMQProducer createProducer(RocketMqRepairQueueProperties props) {
                DefaultMQProducer producer = new DefaultMQProducer("test-group-fail") {
                    @Override
                    public SendResult send(Message msg) {
                        throw new RuntimeException("broker unavailable");
                    }

                    @Override
                    public void start() {
                        // no-op
                    }

                    @Override
                    public void shutdown() {
                        // no-op
                    }
                };
                producer.setNamesrvAddr(props.getNameServer());
                return producer;
            }
        };

        RepairQueuePublishResult result = adapter.publish(new RepairTicketMessage(
                "FS-ERR", "P0", "trace-e", 1, "feishu", "evt-e", "type", Instant.EPOCH
        ));
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("RuntimeException"));
    }

    @Test
    void consumerFailurePreservesTraceIdAndIncrementsAttempt() {
        // 验证 RepairTicketMessage.nextAttempt 语义（RocketMQ 重投时消费端用）
        RepairTicketMessage first = new RepairTicketMessage(
                "FS-R", "P2", "trace-r", 1, "feishu", "evt-r", "type", Instant.EPOCH
        );
        RepairTicketMessage retry = first.nextAttempt();
        assertEquals("trace-r", retry.traceId());
        assertEquals(2, retry.attempt());
        assertEquals("FS-R", retry.ticketId());

        // 记录消费回调返回 false 时不会丢失信息
        RepairQueueConsumer failingConsumer = msg -> false;
        assertFalse(failingConsumer.handle(first));
    }

    private RocketMqRepairQueueProperties newProperties() {
        RocketMqRepairQueueProperties properties = new RocketMqRepairQueueProperties();
        properties.setTopic(RocketMqRepairQueueProperties.DEFAULT_TOPIC);
        properties.setConsumerGroup(RocketMqRepairQueueProperties.DEFAULT_CONSUMER_GROUP);
        properties.setNameServer("127.0.0.1:9876");
        return properties;
    }

    /** 返回空的 ObjectProvider，使适配器构造时不注册真实消费者。 */
    private ObjectProvider<RepairQueueConsumer> emptyConsumerProvider() {
        return new ObjectProvider<>() {
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
