package com.wish.rd.bootstrap.persistence.entity;

/** Row for the registered agent execution profile and its bindings. */
public class AgentExecutionProfileRow {
    public String profileId;
    public Long projectId;
    public String role;
    public String name;
    public String runtimeType;
    public String providerProfileId;
    public String modelOverride;
    public String extensionSetId;
    public Long extensionSetVersion;
    public String toolPolicyId;
    public Long toolPolicyVersion;
    public Boolean enabled;
    public Long version;
    public String capabilitiesJson;
}
