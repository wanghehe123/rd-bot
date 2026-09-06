package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for one Host Manager decision. */
public class TaskManagerDecisionRow {
    public Long id;
    public Long taskId;
    public Integer roundNo;
    public Long sourceCommandId;
    public Long stateVersion;
    public String stateHash;
    public String route;
    public String targetRecordIdsJson;
    public String boundedContract;
    public String executorRoute;
    public String rationale;
    public String decisionHash;
    public OffsetDateTime createdAt;
}
