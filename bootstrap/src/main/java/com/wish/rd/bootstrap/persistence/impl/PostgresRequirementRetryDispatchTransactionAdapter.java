package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.TaskRetryTaskPort;
import com.wish.rd.engine.retry.impl.InMemoryRequirementRetryDispatchTransactionAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL transaction boundary for checkpoint creation, task recovery, and the first command. */
@Component
@Transactional
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementRetryDispatchTransactionAdapter
        extends InMemoryRequirementRetryDispatchTransactionAdapter {

    public PostgresRequirementRetryDispatchTransactionAdapter(
            TaskRetryTaskPort taskPort,
            TaskRetryCheckpointStore checkpointStore,
            RequirementStageCommandStore commandStore,
            TaskRetryAttemptBindingStore bindingStore
    ) {
        super(taskPort, checkpointStore, commandStore, bindingStore);
    }
}
