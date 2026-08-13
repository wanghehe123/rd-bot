package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_external_index_bindings")
public class KnowledgeExternalIndexBindingRow {

    public String provider;
    public Long documentId;
    public Long knowledgeBaseId;
    public String remoteUri;
    public String ownershipMarker;
    public String desiredState;
    public Long desiredVersion;
    public String desiredChecksum;
    public String observedState;
    public Long observedVersion;
    public String observedChecksum;
    public String projectionStatus;
    public Long activeOperationId;
    public String remoteTaskId;
    public String semanticConfigFingerprint;
    public OffsetDateTime lastSubmittedAt;
    public OffsetDateTime lastVerifiedAt;
    public String lastErrorCode;
    public String lastErrorMessage;
    public Long rowVersion;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
