package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_bases")
public class KnowledgeBaseRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String name;
    public String description;
    public Boolean enabled;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
