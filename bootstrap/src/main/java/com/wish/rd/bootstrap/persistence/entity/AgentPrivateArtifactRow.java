package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for restricted Pi object metadata. */
@TableName("rd_agent_private_artifacts")
public class AgentPrivateArtifactRow {

    @TableId(type = IdType.INPUT)
    public String artifactId;
    public Long taskId;
    public Long stageRunId;
    public String snapshotId;
    public String artifactType;
    public String artifactName;
    public String artifactUri;
    public Long bytes;
    public String sha256;
    public String contentType;
    public String retentionClass;
    public OffsetDateTime createdAt;
    public OffsetDateTime expiresAt;
}
