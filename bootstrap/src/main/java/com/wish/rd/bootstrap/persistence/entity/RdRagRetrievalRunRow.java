package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("rd_rag_retrieval_runs")
public class RdRagRetrievalRunRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String consumerType;
    public String role;
    public Long stageRunId;
    public Integer attemptNo;
    public Long parentRunId;
    public String idempotencyKey;
    public String status;
    public String knowledgeBaseIdsJson;
    public String queryHash;
    public String queryPreview;
    public Integer currentIteration;
    public Integer maxIterations;
    public Integer contextBudgetChars;
    public Integer candidateCount;
    public Integer selectedEvidenceCount;
    public String qualityDecision;
    public String stopReason;
    public String errorCategory;
    public String errorMessage;
    public String leaseOwner;
    public OffsetDateTime leaseUntil;
    public Long version;
    @TableField(exist = false)
    public Long expectedVersion;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
