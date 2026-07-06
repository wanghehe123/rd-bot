package com.wish.rd.adapter.model;

/**
 * 工单更新/回复结果。
 *
 * @param success           是否成功
 * @param providerMessageId 提供方返回的消息/操作 ID，失败时为 {@code ""}
 * @param providerCode      提供方返回的业务码，{@code 0}/{@code ""} 视为成功
 * @param message           可读结果或错误摘要
 */
public record TicketUpdateResult(
        boolean success,
        String providerMessageId,
        String providerCode,
        String message
) {

    public TicketUpdateResult {
        providerMessageId = providerMessageId == null ? "" : providerMessageId;
        providerCode = providerCode == null ? "" : providerCode;
        message = message == null ? "" : message;
    }

    /**
     * 构造成功结果。
     *
     * @param providerMessageId 提供方消息 ID
     * @param message           结果摘要
     * @return 成功结果
     */
    public static TicketUpdateResult success(String providerMessageId, String message) {
        return new TicketUpdateResult(true, providerMessageId, "0", message);
    }

    /**
     * 构造失败结果。
     *
     * @param providerCode 提供方业务码
     * @param message      错误摘要
     * @return 失败结果
     */
    public static TicketUpdateResult failure(String providerCode, String message) {
        return new TicketUpdateResult(false, "", providerCode, message);
    }
}
