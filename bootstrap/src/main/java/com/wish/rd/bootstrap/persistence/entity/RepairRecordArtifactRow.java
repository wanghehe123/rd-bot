package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("repair_record_artifacts")
public class RepairRecordArtifactRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long repairRecordId;
    public String artifactType;
    public String artifactUri;
    public String summary;
    public String contentHash;
    public String extensionJson;
    public OffsetDateTime createdAt;
}
