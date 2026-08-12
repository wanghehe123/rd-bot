package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** PostgreSQL row for the authoritative requirement-policy ledger. */
@TableName("rd_requirement_policy_runs")
public class RequirementPolicyRunRow {
    @TableId(type = IdType.INPUT) public Long id;
    public Long taskId; public Long sourceTaskVersion; public Long sourceFence;
    public String planJson; public String planDigest; public String policyJson; public String policyDigest;
    public String policyAction; public String state; public Long boundTaskVersion; public Long boundFence;
    public Long approvalExpectedTaskVersion; public Long approvalExpectedFence;
    public String approvalRequestId; public String approvedBy; public String note; public OffsetDateTime approvedAt;
    public Long approvalResumeCommandId;
    public Long consumedByCommandId; public OffsetDateTime consumedAt; public Long ledgerVersion;
    public OffsetDateTime createdAt; public OffsetDateTime updatedAt;
}
