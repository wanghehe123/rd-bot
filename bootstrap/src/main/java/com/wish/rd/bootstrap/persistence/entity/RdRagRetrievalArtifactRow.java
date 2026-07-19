package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("rd_rag_retrieval_artifacts")
public class RdRagRetrievalArtifactRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long runId;
    public Long stepId;
    public String artifactType;
    public String artifactUri;
    public String contentPreview;
    public String contentHash;
    public String metadataJson;
    public Boolean redacted;
    public OffsetDateTime createdAt;
}
