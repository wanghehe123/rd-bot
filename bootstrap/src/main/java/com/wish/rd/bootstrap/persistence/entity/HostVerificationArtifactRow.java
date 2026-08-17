package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one host-verification evidence object. */
@TableName("rd_host_verification_artifacts")
public class HostVerificationArtifactRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public Long runId;
    public String artifactType;
    public String relativePath;
    public String objectUri;
    public String contentType;
    public Long sizeBytes;
    public String sha256;
    public OffsetDateTime createdAt;
}
