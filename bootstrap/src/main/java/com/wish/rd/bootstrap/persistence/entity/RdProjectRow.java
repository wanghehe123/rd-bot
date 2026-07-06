package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * RD 项目持久化行。
 *
 * <p>对应 {@code rd_projects}，由 PostgreSQL 项目管理适配器读写。
 */
@TableName("rd_projects")
public class RdProjectRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String projectKey;
    public String name;
    public String description;
    public String repositoryUrl;
    public String repoOwner;
    public String repoName;
    public String defaultBranch;
    public Boolean enabled;
    public Boolean deleted;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
