package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** PostgreSQL row for the independently versioned state and injection live projection. */
public class AgentStageStateProjectionRow {
    public long stageRunId;
    public long taskId;
    public String role;
    public int attemptNo;
    public long stateSequence;
    public String stateHash;
    public String stateJson;
    public OffsetDateTime stateProjectedAt;
    public long injectionSequence;
    public long injectedStateSequence;
    public String injectedStateHash;
    public String promptHash;
    public String injectedBlockHash;
    public String injectedBlock;
    public String injectionIdempotencyKey;
    public OffsetDateTime injectedAt;
    public boolean finalized;
    public OffsetDateTime finalizedAt;
    public OffsetDateTime updatedAt;
}
