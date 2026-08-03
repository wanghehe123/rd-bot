package com.wish.rd.engine.ticket.model;

/**
 * 队列发布结果。
 *
 * @param success      是否成功入队
 * @param messageId    队列提供方返回的记录 ID，失败时为 {@code ""}
 * @param targetTopic  兼容字段：目标队列地址或 Stream key
 * @param targetTag    兼容字段：目标优先级标签
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
     * @param messageId   队列记录 ID
     * @param targetTopic 目标队列地址或 Stream key
     * @param targetTag   目标优先级标签
     * @return 成功结果
     */
    public static RepairQueuePublishResult success(String messageId, String targetTopic, String targetTag) {
        return new RepairQueuePublishResult(true, messageId, targetTopic, targetTag, "");
    }

    /**
     * 构造失败发布结果。
     *
     * @param targetTopic 目标队列地址或 Stream key
     * @param targetTag   目标优先级标签
     * @param errorMessage 失败原因
     * @return 失败结果
     */
    public static RepairQueuePublishResult failure(String targetTopic, String targetTag, String errorMessage) {
        return new RepairQueuePublishResult(false, "", targetTopic, targetTag, errorMessage);
    }
}
