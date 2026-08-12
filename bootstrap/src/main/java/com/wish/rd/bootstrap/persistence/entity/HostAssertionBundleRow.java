package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for the immutable {@code rd_host_assertion_bundles} Host QA contract table. */
public class HostAssertionBundleRow {
    public Long taskId;
    public Long stageRunId;
    public String scope;
    public String canonicalSpecsJson;
    public String contentHash;
    public Long version;
    public OffsetDateTime createdAt;
}
