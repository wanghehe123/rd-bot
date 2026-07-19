package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** Redacted PostgreSQL artifact projection for AI review input and output. */
@TableName("rd_ai_review_artifacts")
public class AiReviewArtifactRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long runId;
    public String artifactType;
    public String artifactUri;
    public String contentPreview;
    public String contentHash;
    public String metadataJson;
    public Boolean redacted;
    public OffsetDateTime createdAt;
}
