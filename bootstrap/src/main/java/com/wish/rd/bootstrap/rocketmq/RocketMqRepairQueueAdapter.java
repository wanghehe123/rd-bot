package com.wish.rd.bootstrap.rocketmq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RocketMQ 修复队列适配器：实现 {@link RepairQueuePublisher}，并注册消费者回调到 {@link RepairQueueConsumer}。
 *
 * <p>由配置 {@code rd.repair.queue.mode=rocketmq} 装配；默认走
 * {@link InMemoryRepairQueueAdapter}。
 *
 * <p>消息约定（对齐 AGENTS.md）：
 * <ul>
 *   <li>topic：{@link RocketMqRepairQueueProperties#getTopic()}（默认 {@code RD_BOT_REPAIR_TICKET}）。</li>
 *   <li>tag：优先级（{@code P0}/{@code P1}/{@code P2}）。</li>
 *   <li>key：{@code ticketId}。</li>
 *   <li>property：{@code traceId}/{@code attempt}/{@code source}。</li>
 *   <li>body：JSON 形式的 {@link RepairTicketMessage}（只含路由元数据）。</li>
 * </ul>
 *
 * <p>消费失败时保留 traceId 并自增 attempt 后重投（由 RocketMQ 重试机制兜底）。
 */
@Component
@ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "rocketmq")
public class RocketMqRepairQueueAdapter implements RepairQueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqRepairQueueAdapter.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RocketMqRepairQueueProperties properties;
    private final ObjectMapper objectMapper;
    private final DefaultMQProducer producer;

    public RocketMqRepairQueueAdapter(
            RocketMqRepairQueueProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<RepairQueueConsumer> consumerProvider
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.producer = createProducer(properties);
        try {
            producer.start();
            log.info("rocketmq producer started, group={}, nameserver={}",
                    producer.getProducerGroup(), producer.getNamesrvAddr());
        } catch (MQClientException exception) {
            throw new IllegalStateException("failed to start rocketmq producer", exception);
        }
        registerConsumer(consumerProvider.getIfAvailable());
    }

    /**
     * 创建 RocketMQ Producer；子类/测试可覆盖以注入桩。
     *
     * @param properties 队列配置
     * @return Producer
     */
    protected DefaultMQProducer createProducer(RocketMqRepairQueueProperties properties) {
        DefaultMQProducer mqProducer = new DefaultMQProducer(
                properties.getProducerGroup().isBlank()
                        ? properties.getConsumerGroup() + "_PRODUCER"
                        : properties.getProducerGroup()
        );
        mqProducer.setNamesrvAddr(properties.getNameServer());
        mqProducer.setSendMsgTimeout(properties.getSendMessageTimeoutMillis());
        return mqProducer;
    }

    /**
     * 创建 RocketMQ Push 消费者；子类/测试可覆盖。
     *
     * @param properties 队列配置
     * @return Consumer
     */
    protected DefaultMQPushConsumer createConsumer(RocketMqRepairQueueProperties properties) {
        DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        mqConsumer.setNamesrvAddr(properties.getNameServer());
        try {
            // 订阅三个优先级 tag
            mqConsumer.subscribe(properties.getTopic(), "P0 || P1 || P2");
        } catch (Exception exception) {
            throw new IllegalStateException("failed to subscribe rocketmq topic", exception);
        }
        return mqConsumer;
    }

    private void registerConsumer(RepairQueueConsumer consumer) {
        if (consumer == null) {
            log.warn("no RepairQueueConsumer bean; rocketmq consumer will not be registered");
            return;
        }
        DefaultMQPushConsumer mqConsumer = createConsumer(properties);
        mqConsumer.registerMessageListener(new RepairMessageListener(consumer, objectMapper));
        try {
            mqConsumer.start();
            log.info("rocketmq consumer started, group={}, topic={}",
                    properties.getConsumerGroup(), properties.getTopic());
        } catch (MQClientException exception) {
            throw new IllegalStateException("failed to start rocketmq consumer", exception);
        }
    }

    @Override
    public RepairQueuePublishResult publish(RepairTicketMessage message) {
        try {
            Message mq = toMqMessage(message);
            SendResult result = producer.send(mq);
            if (result.getSendStatus() == SendStatus.SEND_OK) {
                log.info(
                        "rocketmq published, ticketId={}, tag={}, msgId={}",
                        message.ticketId(), message.tag(), result.getMsgId()
                );
                return RepairQueuePublishResult.success(
                        result.getMsgId(), properties.getTopic(), message.tag()
                );
            }
            return RepairQueuePublishResult.failure(
                    properties.getTopic(), message.tag(), "send status: " + result.getSendStatus()
            );
        } catch (Exception exception) {
            log.error("rocketmq publish failed, ticketId={}", message.ticketId(), exception);
            return RepairQueuePublishResult.failure(
                    properties.getTopic(), message.tag(), exception.getClass().getSimpleName()
            );
        }
    }

    private Message toMqMessage(RepairTicketMessage message) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "ticketId", message.ticketId(),
                "priority", message.priority(),
                "traceId", message.traceId(),
                "attempt", message.attempt(),
                "source", message.source(),
                "eventId", message.eventId(),
                "eventType", message.eventType(),
                "createdAt", message.createdAt().toString()
        ));
        Message mq = new Message(
                properties.getTopic(),
                message.tag(),
                message.ticketId(),
                body.getBytes(StandardCharsets.UTF_8)
        );
        mq.putUserProperty("traceId", message.traceId());
        mq.putUserProperty("attempt", Integer.toString(message.attempt()));
        mq.putUserProperty("source", message.source());
        return mq;
    }

    /** RocketMQ 消息监听器：把 MQ 消息还原成 {@link RepairTicketMessage} 后回调消费端口。 */
    private static final class RepairMessageListener implements MessageListenerConcurrently {

        private final RepairQueueConsumer consumer;
        private final ObjectMapper objectMapper;

        RepairMessageListener(RepairQueueConsumer consumer, ObjectMapper objectMapper) {
            this.consumer = consumer;
            this.objectMapper = objectMapper;
        }

        @Override
        public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
            for (MessageExt msg : msgs) {
                try {
                    RepairTicketMessage message = fromMqMessage(msg);
                    boolean success = consumer.handle(message);
                    if (!success) {
                        // 消费失败：让 RocketMQ 稍后重试（RECONSUME_LATER）
                        log.warn("rocketmq consume failed, ticketId={}, will retry", message.ticketId());
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                } catch (Exception exception) {
                    log.error("rocketmq consume exception, msgId={}", msg.getMsgId(), exception);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        @SuppressWarnings("unchecked")
        private RepairTicketMessage fromMqMessage(MessageExt msg) throws Exception {
            String json = new String(msg.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> map = objectMapper.readValue(json, MAP_TYPE);
            int attempt = 1;
            Object attemptValue = map.get("attempt");
            if (attemptValue instanceof Number number) {
                attempt = number.intValue();
            }
            return new RepairTicketMessage(
                    (String) map.getOrDefault("ticketId", ""),
                    (String) map.getOrDefault("priority", "P2"),
                    (String) map.getOrDefault("traceId", ""),
                    attempt,
                    (String) map.getOrDefault("source", ""),
                    (String) map.getOrDefault("eventId", ""),
                    (String) map.getOrDefault("eventType", ""),
                    parseInstant((String) map.get("createdAt"))
            );
        }

        private Instant parseInstant(String value) {
            if (value == null || value.isBlank()) {
                return Instant.EPOCH;
            }
            try {
                return Instant.parse(value);
            } catch (Exception ignored) {
                return Instant.EPOCH;
            }
        }
    }
}
