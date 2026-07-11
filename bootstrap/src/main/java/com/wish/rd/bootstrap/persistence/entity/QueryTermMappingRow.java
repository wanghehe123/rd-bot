package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** PostgreSQL row for {@code rd_query_term_mappings}. */
public class QueryTermMappingRow {
    public Long id;
    public Long projectId;
    public String scope;
    public String sourceTerm;
    public String targetTerm;
    public Integer priority;
    public Boolean enabled;
    public String remark;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
