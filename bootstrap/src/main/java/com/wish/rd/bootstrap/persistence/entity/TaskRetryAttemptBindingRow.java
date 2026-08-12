package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one immutable, checkpoint-scoped retry attempt binding. */
@TableName("rd_task_retry_attempt_bindings")
public class TaskRetryAttemptBindingRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long checkpointId;
    public String bindingKind;
    public String role;
    public Long stageRunId;
    public Long retrievalRunId;
    public Long aiReviewRunId;
    public Long parentBindingId;
    public Integer attemptNo;
    public Integer ordinal;
    public OffsetDateTime createdAt;
}
