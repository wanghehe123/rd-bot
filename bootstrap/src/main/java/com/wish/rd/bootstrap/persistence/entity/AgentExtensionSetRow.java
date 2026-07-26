package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for an immutable Pi extension set version. */
@TableName("rd_agent_extension_sets")
public class AgentExtensionSetRow {
    public String extensionSetId;
    public Long version;
    public String setHash;
    public Boolean enabled;
    public OffsetDateTime createdAt;
}
