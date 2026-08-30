package com.wish.rd.bootstrap.persistence.entity;

import java.time.Instant;

public class ProjectMemoryOperationRow {
    public Long id;
    public String operationKey;
    public Long projectId;
    public String operationKind;
    public String sourceIdentity;
    public String sourceContentHash;
    public String extractorVersion;
    public String schemaVersion;
    public String status;
    public Integer attemptNo;
    public Integer maxAttempts;
    public String leaseOwner;
    public Instant leaseUntil;
    public Long fencingToken;
    public Long rowVersion;
    public Instant nextVisibleAt;
    public String checkpointJson;
    public String lastError;
}
