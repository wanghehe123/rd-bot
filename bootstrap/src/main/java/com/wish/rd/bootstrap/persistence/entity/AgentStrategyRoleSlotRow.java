package com.wish.rd.bootstrap.persistence.entity;

/** Row for one delivery-role slot of a project agent strategy. */
public class AgentStrategyRoleSlotRow {
    public Long projectId;
    public String strategyId;
    public String role;
    public String runtimeType;
    public String providerProfileId;
    public String modelOverride;
    public String extensionSetId;
    public Long extensionSetVersion;
    public String toolPolicyId;
    public Long toolPolicyVersion;
    public String imageMode;
    public String image;
    public String dockerfileName;
    public String dockerfileSha256;
    public String dockerfileArtifactUri;
    public String dockerfileText;
}
