package com.wish.rd.bootstrap.persistence.entity;

/** Row for one immutable version of an agent tool policy. */
public class AgentToolPolicyRow {
    public String policyId;
    public Long version;
    public String policyJson;
    public String policyHash;
    public Boolean enabled;
}
