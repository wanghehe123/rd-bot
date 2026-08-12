package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.requirement.publication.RequirementPublicationCommitPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * PostgreSQL transaction adapter that commits a requirement task and publication ledger together.
 *
 * <p>It delegates the task snapshot/event CAS to {@link RagStreamTaskRegistry}, then advances the
 * matching ledger operation. Both collaborators join this outer transaction in PostgreSQL mode.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementPublicationCommitAdapter implements RequirementPublicationCommitPort {

    private final RagStreamTaskRegistry taskRegistry;
    private final RequirementPublicationLedger publicationLedger;

    /**
     * Creates the atomic publication finalization adapter.
     *
     * @param taskRegistry requirement task state transition owner
     * @param publicationLedger durable publication ledger
     */
    public PostgresRequirementPublicationCommitAdapter(
            RagStreamTaskRegistry taskRegistry,
            RequirementPublicationLedger publicationLedger
    ) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry must not be null");
        this.publicationLedger = Objects.requireNonNull(publicationLedger, "publicationLedger must not be null");
    }

    /**
     * Advances the task snapshot/event and the PR-confirmed publication in one transaction.
     *
     * @param command immutable finalization input
     * @return committed task snapshot
     */
    @Override
    @Transactional
    public RdRequirementTask commit(RequirementPublicationCommitCommand command) {
        RequirementPublicationCommitCommand safeCommand = Objects.requireNonNull(command, "command must not be null");
        RdRequirementTask committed = taskRegistry.markRequirementCommittedFenced(
                safeCommand.taskId(),
                safeCommand.expectedVersion(),
                safeCommand.expectedStatus(),
                safeCommand.expectedFencingToken(),
                safeCommand.pullRequestUrl(),
                safeCommand.executionResultJson());
        publicationLedger.markCommitted(safeCommand.operationId());
        return committed;
    }
}
