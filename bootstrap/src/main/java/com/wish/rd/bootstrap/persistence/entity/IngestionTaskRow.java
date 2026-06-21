package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("ingestion_tasks")
public class IngestionTaskRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String pipelineId;
    public Long knowledgeBaseId;
    public Long documentId;
    public String sourceType;
    public String sourceLocation;
    public String sourceFileName;
    public String status;
    public Integer chunkCount;
    public String errorMessage;
    public String metadataJson;
    public OffsetDateTime startedAt;
    public OffsetDateTime completedAt;
    public String createdBy;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
