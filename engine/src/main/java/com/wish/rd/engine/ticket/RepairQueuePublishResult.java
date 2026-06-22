package com.wish.rd.engine.ticket;

/**
 * 队列发布结果。
 *
 * @param success      是否成功入队
 * @param messageId    MQ 提供方返回的消息 ID，失败时为 {@code ""}
 * @param targetTopic  目标 topic
 * @param targetTag    目标 tag
 * @param errorMessage 失败原因摘要，成功时为 {@code ""}
 */
public record RepairQueuePublishResult(
        boolean success,
        String messageId,
        String targetTopic,
        String targetTag,
        String errorMessage
) {

    public RepairQueuePublishResult {
        messageId = messageId == null ? "" : messageId;
        targetTopic = targetTopic == null ? "" : targetTopic;
        targetTag = targetTag == null ? "" : targetTag;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 构造成功发布结果。
     *
     * @param messageId   MQ 消息 ID
     * @param targetTopic 目标 topic
     * @param targetTag   目标 tag
     * @return 成功结果
     */
    public static RepairQueuePublishResult success(String messageId, String targetTopic, String targetTag) {
        return new RepairQueuePublishResult(true, messageId, targetTopic, targetTag, "");
    }

    /**
     * 构造失败发布结果。
     *
     * @param targetTopic 目标 topic
     * @param targetTag   目标 tag
     * @param errorMessage 失败原因
     * @return 失败结果
     */
    public static RepairQueuePublishResult failure(String targetTopic, String targetTag, String errorMessage) {
        return new RepairQueuePublishResult(false, "", targetTopic, targetTag, errorMessage);
    }
}
