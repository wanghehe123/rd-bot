package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** MyBatis row for immutable opt-in iterative retrieval round audits. */
@TableName("rd_retrieval_round_audits")
public class RetrievalRoundAuditRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public Long runId;
    public Integer roundNo;
    public String queryPreview;
    public String candidateEvidenceIdsJson;
    public String selectedEvidenceIdsJson;
    public String missingEvidenceTypesJson;
    public Integer informationGain;
    public Long cumulativeTokens;
    public Long elapsedMs;
    public String stopReason;
    public String scopeFingerprint;
    public OffsetDateTime recordedAt;
}
