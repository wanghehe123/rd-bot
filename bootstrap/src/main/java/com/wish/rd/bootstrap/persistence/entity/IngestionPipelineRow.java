package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 摄取管道持久化行。
 *
 * <p>对应 {@code t_ingestion_pipeline}，由 PostgreSQL 摄取管道适配器读写。
 */
@TableName("t_ingestion_pipeline")
public class IngestionPipelineRow {

    @TableId(type = IdType.INPUT)
    public String id;
    public String name;
    public String description;
    public String createdBy;
    public String updatedBy;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
