package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("rd_rag_retrieval_events")
public class RdRagRetrievalEventRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long runId;
    public String fromStatus;
    public String toStatus;
    public String trigger;
    public String message;
    public String errorCategory;
    public OffsetDateTime occurredAt;
}
