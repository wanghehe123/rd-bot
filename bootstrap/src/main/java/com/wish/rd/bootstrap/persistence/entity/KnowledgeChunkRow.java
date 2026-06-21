package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_chunks")
public class KnowledgeChunkRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long documentId;
    public Long knowledgeBaseId;
    public Integer chunkIndex;
    public String content;
    public String contentHash;
    public String knowledgeType;
    public String sourceName;
    public Boolean enabled;
    public String metadataJson;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
