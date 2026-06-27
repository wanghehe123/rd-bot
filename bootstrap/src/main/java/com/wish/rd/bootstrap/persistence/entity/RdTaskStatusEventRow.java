package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * RD 任务状态事件持久化行。
 *
 * <p>对应 {@code rd_task_status_events}，由 {@code PostgresRdTaskStatusEventStore} 读写。
 */
@TableName("rd_task_status_events")
public class RdTaskStatusEventRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String status;
    public String title;
    public String message;
    public OffsetDateTime enteredAt;
    public Long durationMs;
    public String trigger;
}
