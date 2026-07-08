package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 意图节点持久化行。
 *
 * <p>对应 {@code t_intent_node}，由 PostgreSQL 意图树适配器读写。
 */
@TableName("t_intent_node")
public class IntentNodeRow {

    @TableId(type = IdType.INPUT)
    public String id;
    public String kbId;
    public String intentCode;
    public String name;
    public Integer level;
    public String parentCode;
    public String description;
    public String examples;
    public String collectionName;
    public Integer topK;
    public String mcpToolId;
    public Integer kind;
    public String promptSnippet;
    public String promptTemplate;
    public String paramPromptTemplate;
    public Integer sortOrder;
    public Integer enabled;
    public String createBy;
    public String updateBy;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
