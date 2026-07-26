package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** PostgreSQL row for a publisher-verified Pi extension bundle. */
@TableName("rd_agent_extension_versions")
public class AgentExtensionVersionRow {
    public String extensionId;
    public String version;
    public String artifactUri;
    public String artifactSha256;
    public String signature;
    public String manifestJson;
    public String checksumJson;
    public String sbomJson;
    public String piVersionRange;
    public String bridgeProtocolRange;
    public String targetOs;
    public String targetArch;
    public String nodeAbi;
    public String status;
}
