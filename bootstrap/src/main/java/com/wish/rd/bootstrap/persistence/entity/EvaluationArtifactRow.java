package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for redacted local evaluation artifact metadata. */
@TableName("rd_evaluation_artifacts")
public class EvaluationArtifactRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long runId;
    public String artifactType;
    public String artifactUri;
    public String contentPreview;
    public String contentHash;
    public Long sizeBytes;
    public OffsetDateTime createdAt;
}
