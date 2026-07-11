package com.wish.rd.rag.project.alert.model;

/**
 * 项目告警接收目标。
 *
 * @param type  飞书接收 ID 类型
 * @param value 飞书接收 ID
 */
public record RdAlertRecipient(RdAlertRecipientType type, String value) {

    public RdAlertRecipient {
        type = type == null ? RdAlertRecipientType.CHAT_ID : type;
        value = value == null ? "" : value.strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException("recipient value must not be blank");
        }
    }

    /**
     * 返回接收目标稳定键。
     *
     * @return 类型和值组成的稳定键
     */
    public String key() {
        return type.name() + ":" + value;
    }
}
