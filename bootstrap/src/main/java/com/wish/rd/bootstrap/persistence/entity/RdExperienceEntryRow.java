package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 工作流经验条目持久化行。
 */
@TableName("rd_experience_entries")
public class RdExperienceEntryRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public Long stageRunId;
    public Long sourceArtifactId;
    public String role;
    public String experienceType;
    public String title;
    public String summary;
    public String contentJson;
    public String contentHash;
    public Boolean reusable;
    public Boolean failure;
    public Boolean redacted;
    public Long ingestionTaskId;
    public OffsetDateTime createdAt;
}
