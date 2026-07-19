package com.wish.rd.bootstrap.persistence.entity;

/** Aggregate projection returned by the paged PostgreSQL evaluation history query. */
public class EvaluationRunOverviewRow {
    public Long total;
    public Long active;
    public Long gatePassed;
    public Long incomplete;
    public Long failed;
}
