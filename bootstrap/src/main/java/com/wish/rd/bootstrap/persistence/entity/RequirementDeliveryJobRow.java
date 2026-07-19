package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("rd_requirement_delivery_jobs")
public class RequirementDeliveryJobRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String status;
    public Integer attemptNo;
    public Integer maxAttempts;
    public String leaseOwner;
    public OffsetDateTime leaseUntil;
    public String errorMessage;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
