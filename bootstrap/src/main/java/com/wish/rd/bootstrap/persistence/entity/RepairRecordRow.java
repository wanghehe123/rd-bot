package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("repair_records")
public class RepairRecordRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String ticketId;
    public String ticketUrl;
    public String title;
    public String status;
    public String ragSummary;
    public String executorJson;
    public String dockerJson;
    public String githubJson;
    public String testJson;
    public String riskJson;
    public String errorMessage;
    public String extensionJson;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
