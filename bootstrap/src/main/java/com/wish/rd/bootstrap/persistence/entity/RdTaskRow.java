package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * RD 任务持久化行。
 *
 * <p>对应 {@code rd_tasks}，由 {@code PostgresRdTaskStore} 读写。
 */
@TableName("rd_tasks")
public class RdTaskRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String taskType;
    public String ticketId;
    public String ticketTitle;
    public String priority;
    public String status;
    public String messageId;
    public String title;
    public String promptSnapshot;
    public String executionResultJson;
    public String pullRequestUrl;
    public String errorMessage;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
