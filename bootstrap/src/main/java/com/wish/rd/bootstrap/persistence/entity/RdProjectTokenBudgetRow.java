package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for {@code rd_project_token_budgets}. */
public class RdProjectTokenBudgetRow {
    public Long projectId;
    public Long defaultTokenBudget;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
