package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;

class InMemoryAuditedTaskStateStoreTest extends AuditedTaskStateStoreContractTest {
    @Override
    protected AuditedTaskStateStore newStore() {
        return new InMemoryAuditedTaskStateStore();
    }
}
