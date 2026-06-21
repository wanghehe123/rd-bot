package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_documents")
public class KnowledgeDocumentRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long knowledgeBaseId;
    public String sourceName;
    public String knowledgeType;
    public String mimeType;
    public String status;
    public Boolean enabled;
    public Integer chunkCount;
    public String nodeLogsJson;
    public String sourceType;
    public String sourceToken;
    public String sourceUrl;
    public String revisionId;
    public String checksum;
    public String rawPreview;
    public String rawContent;
    public OffsetDateTime lastSyncedAt;
    public OffsetDateTime nextRefreshAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
