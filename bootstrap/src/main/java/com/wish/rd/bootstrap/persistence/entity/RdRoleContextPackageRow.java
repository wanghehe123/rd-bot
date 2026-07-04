package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 角色上下文包持久化行。
 *
 * <p>对应 {@code rd_role_context_packages}，由 PostgreSQL 角色上下文 store 读写。
 */
@TableName("rd_role_context_packages")
public class RdRoleContextPackageRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String role;
    public Integer packageVersion;
    public String evidenceJson;
    public String acceptanceJson;
    public String riskHintsJson;
    public String contextBudgetJson;
    public String omittedEvidenceJson;
    public String contentHash;
    public OffsetDateTime createdAt;
}
