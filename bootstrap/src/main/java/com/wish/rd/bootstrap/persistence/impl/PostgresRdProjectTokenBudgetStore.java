package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RdProjectTokenBudgetRow;
import com.wish.rd.bootstrap.persistence.mapper.RdProjectTokenBudgetMapper;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetStore;
import com.wish.rd.rag.project.budget.model.RdProjectTokenBudget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** PostgreSQL project token-budget store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRdProjectTokenBudgetStore implements RdProjectTokenBudgetStore {

    private final RdProjectTokenBudgetMapper mapper;

    public PostgresRdProjectTokenBudgetStore(RdProjectTokenBudgetMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RdProjectTokenBudget save(RdProjectTokenBudget budget) {
        RdProjectTokenBudgetRow row = new RdProjectTokenBudgetRow();
        row.projectId = PostgresPersistenceSupport.parseId(budget.projectId());
        row.defaultTokenBudget = budget.defaultTokenBudget();
        row.createdAt = PostgresPersistenceSupport.toDateTime(budget.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(budget.updateTimeEpochMillis());
        mapper.upsert(row);
        return budget;
    }

    @Override
    public Optional<RdProjectTokenBudget> findByProjectId(String projectId) {
        return Optional.ofNullable(mapper.findByProjectId(PostgresPersistenceSupport.parseId(projectId)))
                .map(row -> new RdProjectTokenBudget(
                        PostgresPersistenceSupport.idString(row.projectId),
                        row.defaultTokenBudget == null ? 0L : row.defaultTokenBudget,
                        PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                        PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
                ));
    }
}
