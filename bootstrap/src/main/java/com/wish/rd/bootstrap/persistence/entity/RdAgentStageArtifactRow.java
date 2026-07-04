package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * Agent 阶段产物持久化行。
 *
 * <p>对应 {@code rd_agent_stage_artifacts}，由 PostgreSQL 阶段产物 store 读写。
 */
@TableName("rd_agent_stage_artifacts")
public class RdAgentStageArtifactRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long stageRunId;
    public Long taskId;
    public String role;
    public String artifactType;
    public String artifactUri;
    public String summary;
    public String contentPreview;
    public String contentHash;
    public String metadataJson;
    public OffsetDateTime createdAt;
}
