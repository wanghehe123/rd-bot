package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** PostgreSQL row for an extension set member. */
@TableName("rd_agent_extension_set_members")
public class AgentExtensionSetMemberRow {
    public String extensionSetId;
    public Long setVersion;
    public String extensionId;
    public String extensionVersion;
    public Boolean enabled;
    public String configHash;
}
