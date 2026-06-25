package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("repair_assets")
public class RepairAssetRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long repairRecordId;
    public String assetType;
    public String title;
    public String summary;
    public String contentJson;
    public Long sourceArtifactId;
    public Boolean reusable;
    public OffsetDateTime createdAt;
}
