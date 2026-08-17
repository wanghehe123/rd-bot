package com.wish.rd.bootstrap.persistence.entity;

/** PostgreSQL row for one BUILD or STATIC step of a host verification run. */
public class HostVerificationStepRow {
    public Long runId;
    public String step;
    public String status;
    public String commandsJson;
    public Integer exitCode;
    public Long durationMillis;
    public Long logArtifactId;
    public String errorMessage;
}
