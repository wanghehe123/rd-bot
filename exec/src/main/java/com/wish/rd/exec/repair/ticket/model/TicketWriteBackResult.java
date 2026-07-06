package com.wish.rd.exec.repair.ticket.model;

import java.util.Map;

/**
 * 外部工单回写结果。
 *
 * @param success      是否回写成功
 * @param providerCode 外部系统返回码或本地适配器代码
 * @param message      结果说明
 * @param metadata     扩展元数据
 */
public record TicketWriteBackResult(
        boolean success,
        String providerCode,
        String message,
        Map<String, String> metadata
) {

    public TicketWriteBackResult {
        providerCode = providerCode == null ? "" : providerCode.strip();
        message = message == null ? "" : message.strip();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 创建成功结果。
     *
     * @param providerCode 外部系统返回码或本地适配器代码
     * @param message      结果说明
     * @param metadata     扩展元数据
     * @return 成功回写结果
     */
    public static TicketWriteBackResult success(String providerCode, String message, Map<String, String> metadata) {
        return new TicketWriteBackResult(true, providerCode, message, metadata);
    }

    /**
     * 创建失败结果。
     *
     * @param providerCode 外部系统返回码或本地适配器代码
     * @param message      结果说明
     * @param metadata     扩展元数据
     * @return 失败回写结果
     */
    public static TicketWriteBackResult failure(String providerCode, String message, Map<String, String> metadata) {
        return new TicketWriteBackResult(false, providerCode, message, metadata);
    }
}
