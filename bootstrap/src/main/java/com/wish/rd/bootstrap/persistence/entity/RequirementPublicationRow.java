package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one durable requirement publication intent. */
@TableName("rd_requirement_publications")
public class RequirementPublicationRow {
    @TableId(type = IdType.INPUT)
    public String id;
    public String operationId;
    public Long taskId;
    public String stageRunId;
    public String status;
    public String baseBranch;
    public String workBranch;
    public String candidatePatchSha256;
    public String remoteHeadSha;
    public String pullRequestUrl;
    public Integer pullRequestNumber;
    public Integer version;
    public String lastError;
    public OffsetDateTime nextReconcileAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableField(exist = false)
    public Integer expectedVersion;
}
