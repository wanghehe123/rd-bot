package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * Agent 阶段运行持久化行。
 *
 * <p>对应 {@code rd_agent_stage_runs}，由 PostgreSQL 阶段运行 store 读写。
 */
@TableName("rd_agent_stage_runs")
public class RdAgentStageRunRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String role;
    public String status;
    public Integer attemptNo;
    public String idempotencyKey;
    public String providerName;
    public String providerAttemptsJson;
    public Long contextPackageId;
    public Long promptArtifactId;
    public Long resultArtifactId;
    public String reviewResultJson;
    public String errorCategory;
    public String errorMessage;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
