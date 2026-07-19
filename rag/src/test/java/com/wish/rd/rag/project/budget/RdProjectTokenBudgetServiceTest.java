package com.wish.rd.rag.project.budget;

import com.wish.rd.rag.project.budget.model.RdProjectTokenBudget;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RdProjectTokenBudgetServiceTest {

    @Test
    void shouldReturnUnlimitedDefaultAndPersistNonNegativeBudget() {
        InMemoryStore store = new InMemoryStore();
        RdProjectTokenBudgetService service = new RdProjectTokenBudgetService(store);

        assertEquals(0L, service.get("7482000000000000001").defaultTokenBudget());
        RdProjectTokenBudget saved = service.update("7482000000000000001", 120_000L);

        assertEquals(120_000L, saved.defaultTokenBudget());
        assertEquals(saved, service.get(saved.projectId()));
    }

    @Test
    void shouldRejectNegativeBudget() {
        RdProjectTokenBudgetService service = new RdProjectTokenBudgetService(new InMemoryStore());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.update("7482000000000000001", -1L)
        );

        assertEquals("defaultTokenBudget must not be negative", exception.getMessage());
    }

    private static final class InMemoryStore implements RdProjectTokenBudgetStore {
        private RdProjectTokenBudget value;

        @Override
        public RdProjectTokenBudget save(RdProjectTokenBudget budget) {
            value = budget;
            return budget;
        }

        @Override
        public Optional<RdProjectTokenBudget> findByProjectId(String projectId) {
            return value == null || !value.projectId().equals(projectId) ? Optional.empty() : Optional.of(value);
        }
    }
}
