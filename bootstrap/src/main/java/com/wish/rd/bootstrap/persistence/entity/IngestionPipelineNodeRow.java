package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 摄取管道节点持久化行。
 *
 * <p>对应 {@code t_ingestion_pipeline_node}，保存管道节点及 next 指针。
 */
@TableName("t_ingestion_pipeline_node")
public class IngestionPipelineNodeRow {

    @TableId(type = IdType.INPUT)
    public String id;
    public String pipelineId;
    public String nodeId;
    public String nodeType;
    public String nextNodeId;
    public String settingsJson;
    public String conditionJson;
    public String createdBy;
    public String updatedBy;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
