package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_vectors")
public class KnowledgeVectorRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String content;
    public String metadataJson;
    public String embedding;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableField(exist = false)
    public Double score;
}
