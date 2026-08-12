package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.retry.RequirementRetryDispatchTransactionPort;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresRequirementRetryDispatchTransactionAdapterTest {

    @Test
    void exposesTheRetryInitializationPortAsATransactionalPostgresAdapter() {
        assertTrue(RequirementRetryDispatchTransactionPort.class
                .isAssignableFrom(PostgresRequirementRetryDispatchTransactionAdapter.class));
        assertNotNull(PostgresRequirementRetryDispatchTransactionAdapter.class.getAnnotation(Transactional.class));
    }
}
