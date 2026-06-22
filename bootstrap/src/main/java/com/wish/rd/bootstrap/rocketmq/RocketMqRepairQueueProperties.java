package com.wish.rd.bootstrap.rocketmq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 修复队列配置。
 *
 * <p>键前缀 {@code rd.rocketmq.repair.*}，对齐 AGENTS.md P1 约定：
 * topic {@code RD_BOT_REPAIR_TICKET}、consumer group {@code GID_RD_BOT_REPAIR_WORKER}、
 * tags {@code P0}/{@code P1}/{@code P2}。
 */
@ConfigurationProperties(prefix = "rd.rocketmq.repair")
public class RocketMqRepairQueueProperties {

    /** 默认 topic，对齐 AGENTS.md。 */
    public static final String DEFAULT_TOPIC = "RD_BOT_REPAIR_TICKET";
    /** 默认 consumer group，对齐 AGENTS.md。 */
    public static final String DEFAULT_CONSUMER_GROUP = "GID_RD_BOT_REPAIR_WORKER";

    private String topic = DEFAULT_TOPIC;
    private String consumerGroup = DEFAULT_CONSUMER_GROUP;
    private String nameServer = "";
    private String producerGroup = "";
    private int sendMessageTimeoutMillis = 2000;
    private int maxRetryAttempts = 3;

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public int getSendMessageTimeoutMillis() {
        return sendMessageTimeoutMillis;
    }

    public void setSendMessageTimeoutMillis(int sendMessageTimeoutMillis) {
        this.sendMessageTimeoutMillis = sendMessageTimeoutMillis;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }
}
