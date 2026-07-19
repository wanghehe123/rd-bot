package com.wish.rd.rag.project.budget;

import com.wish.rd.rag.project.budget.model.RdProjectTokenBudget;

import java.util.Optional;

/** Persistent project default token-budget port. */
public interface RdProjectTokenBudgetStore {

    RdProjectTokenBudget save(RdProjectTokenBudget budget);

    Optional<RdProjectTokenBudget> findByProjectId(String projectId);
}
