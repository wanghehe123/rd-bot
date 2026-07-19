package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_map_nodes")
public class KnowledgeMapNodeRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long knowledgeBaseId;
    public Long parentId;
    public String nodeType;
    public String path;
    public String title;
    public String summary;
    public Long documentId;
    public Integer chunkStart;
    public Integer chunkEnd;
    public String revisionId;
    public String checksum;
    public Integer tokenEstimate;
    public String metadataJson;
    public Boolean enabled;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
