package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("rd_project_memories")
public class ProjectMemoryRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long projectId;
    public String scopeRole;
    public String memoryType;
    public String logicalKey;
    public Long headRevisionId;
    public Long headVersion;
    public Long rowVersion;
    public String lifecycleStatus;
}
