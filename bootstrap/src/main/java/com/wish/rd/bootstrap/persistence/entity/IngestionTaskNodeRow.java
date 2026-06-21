package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("ingestion_task_nodes")
public class IngestionTaskNodeRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String pipelineId;
    public String nodeId;
    public String nodeType;
    public Integer nodeOrder;
    public String status;
    public Long durationMs;
    public String message;
    public String errorMessage;
    public String outputJson;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
