package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_refresh_metrics")
public class KnowledgeRefreshMetricRow {

    @TableId(type = IdType.AUTO)
    public Long id;
    public String documentId;
    public String knowledgeBaseId;
    public String sourceType;
    public String sourceName;
    public Boolean success;
    public Integer oldChunkCount;
    public Integer newChunkCount;
    public Long durationMs;
    public String errorMessage;
    public String metadataJson;
    public OffsetDateTime createdAt;
}
