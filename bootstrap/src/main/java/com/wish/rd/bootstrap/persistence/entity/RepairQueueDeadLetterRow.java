package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("repair_queue_dead_letters")
public class RepairQueueDeadLetterRow {

    @TableId(type = IdType.INPUT)
    public String id;
    public String ticketId;
    public String traceId;
    public String source;
    public String eventId;
    public String eventType;
    public Integer originalAttempt;
    public String reason;
    public String messageJson;
    public Boolean replayed;
    public OffsetDateTime createdAt;
    public OffsetDateTime replayedAt;
}
