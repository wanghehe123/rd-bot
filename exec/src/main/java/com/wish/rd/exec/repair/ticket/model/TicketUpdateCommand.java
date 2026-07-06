package com.wish.rd.exec.repair.ticket.model;

import java.util.Map;

/**
 * 修复执行后的通用工单回写命令。
 *
 * @param ticketId        外部工单 ID
 * @param taskId          RD 任务 ID
 * @param repairRecordId  修复记录 ID
 * @param statusSummary   回写摘要
 * @param pullRequestUrl  关联 PR 地址
 * @param failureReason   失败原因
 * @param needHumanAction 是否需要人工介入
 * @param metadata        扩展元数据
 */
public record TicketUpdateCommand(
        String ticketId,
        String taskId,
        String repairRecordId,
        String statusSummary,
        String pullRequestUrl,
        String failureReason,
        boolean needHumanAction,
        Map<String, String> metadata
) {

    public TicketUpdateCommand {
        ticketId = ticketId == null ? "" : ticketId.strip();
        taskId = taskId == null ? "" : taskId.strip();
        repairRecordId = repairRecordId == null ? "" : repairRecordId.strip();
        statusSummary = statusSummary == null ? "" : statusSummary.strip();
        pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl.strip();
        failureReason = failureReason == null ? "" : failureReason.strip();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 创建成功回写命令。
     *
     * @param ticketId       外部工单 ID
     * @param taskId         RD 任务 ID
     * @param repairRecordId 修复记录 ID
     * @param statusSummary  回写摘要
     * @param pullRequestUrl 关联 PR 地址
     * @param metadata       扩展元数据
     * @return 成功回写命令
     */
    public static TicketUpdateCommand success(
            String ticketId,
            String taskId,
            String repairRecordId,
            String statusSummary,
            String pullRequestUrl,
            Map<String, String> metadata
    ) {
        return new TicketUpdateCommand(
                ticketId,
                taskId,
                repairRecordId,
                statusSummary,
                pullRequestUrl,
                "",
                false,
                metadata
        );
    }

    /**
     * 创建失败回写命令。
     *
     * @param ticketId       外部工单 ID
     * @param taskId         RD 任务 ID
     * @param repairRecordId 修复记录 ID
     * @param statusSummary  回写摘要
     * @param failureReason  失败原因
     * @param metadata       扩展元数据
     * @return 失败回写命令
     */
    public static TicketUpdateCommand failure(
            String ticketId,
            String taskId,
            String repairRecordId,
            String statusSummary,
            String failureReason,
            Map<String, String> metadata
    ) {
        return new TicketUpdateCommand(
                ticketId,
                taskId,
                repairRecordId,
                statusSummary,
                "",
                failureReason,
                true,
                metadata
        );
    }
}
