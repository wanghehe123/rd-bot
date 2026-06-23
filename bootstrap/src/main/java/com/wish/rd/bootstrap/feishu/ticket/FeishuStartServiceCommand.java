package com.wish.rd.bootstrap.feishu.ticket;

import java.util.List;

/**
 * 创建服务台对话命令，对应飞书 {@code POST /open-apis/helpdesk/v1/start_service}。
 *
 * @param openId          发起服务台对话的用户 open_id，通常来自 IM 消息事件 sender
 * @param humanService    是否直接进入人工；需要返回真实 ticketId 时应设为 true
 * @param appointedAgents 指定客服 open_id 列表，非空时 humanService 必须为 true
 * @param customizedInfo  工单来源自定义信息
 */
public record FeishuStartServiceCommand(
        String openId,
        boolean humanService,
        List<String> appointedAgents,
        String customizedInfo
) {

    public FeishuStartServiceCommand {
        openId = requireText(openId, "openId");
        appointedAgents = appointedAgents == null ? List.of() : List.copyOf(appointedAgents);
        customizedInfo = customizedInfo == null ? "" : customizedInfo.strip();
        if (!appointedAgents.isEmpty() && !humanService) {
            throw new IllegalArgumentException("humanService must be true when appointedAgents is not empty");
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
