package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for durable QA evidence object metadata. */
@TableName("rd_qa_evidence_objects")
public class RdQaEvidenceObjectRow {

    @TableId(type = IdType.AUTO)
    public Long id;
    public Long taskId;
    public Long stageRunId;
    public Long artifactId;
    public String artifactType;
    public String artifactName;
    public String objectUri;
    public String contentType;
    public Long sizeBytes;
    public String sha256;
    public OffsetDateTime createdAt;
    public OffsetDateTime expiresAt;
}
