package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * RD 任务材料持久化行。
 *
 * <p>对应 {@code rd_task_materials}，由 PostgreSQL 任务材料 store 读写。
 */
@TableName("rd_task_materials")
public class RdTaskMaterialRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String materialType;
    public String sourceType;
    public String title;
    public String sourceUri;
    public String mimeType;
    public String contentHash;
    public String contentPreview;
    public String artifactUri;
    public String knowledgeDocumentId;
    public String revisionId;
    public String metadataJson;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
