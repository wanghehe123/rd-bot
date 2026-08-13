package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_reconcile_findings")
public class KnowledgeReconcileFindingRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String provider;
    public Long knowledgeBaseId;
    public String findingType;
    public String remoteUri;
    public Long documentId;
    public String detail;
    public String status;
    public OffsetDateTime firstSeenAt;
    public OffsetDateTime lastSeenAt;
}
