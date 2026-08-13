package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_external_index_outbox")
public class KnowledgeExternalIndexOutboxRow {

    @TableId(type = IdType.INPUT)
    public Long eventId;
    public String idempotencyKey;
    public String provider;
    public String operationType;
    public Long knowledgeBaseId;
    public Long documentId;
    public Long syncVersion;
    public String checksum;
    public String remoteUri;
    public Long revisionId;
    public String payloadRef;
    public String status;
    public String remoteTaskId;
    public String remoteOperationId;
    public String leaseOwner;
    public OffsetDateTime leaseUntil;
    public Integer attemptCount;
    public Integer maxAttempts;
    public OffsetDateTime nextVisibleAt;
    public OffsetDateTime publishedAt;
    public String lastErrorCode;
    public String lastErrorMessage;
    public Long rowVersion;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
