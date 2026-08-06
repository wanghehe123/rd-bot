package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks memory-mode wiring for the publication ledger so Engine setter injection
 * receives a bean without requiring Postgres DDL.
 */
class InMemoryRequirementPublicationLedgerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("rd.knowledge.store=memory")
            .withUserConfiguration(InMemoryAgentObservabilityConfiguration.class);

    @Test
    void shouldExposeInMemoryPublicationLedgerWhenKnowledgeStoreIsMemory() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RequirementPublicationStore.class);
            assertThat(context).hasSingleBean(RequirementPublicationLedger.class);
            assertThat(context.getBean(RequirementPublicationStore.class))
                    .isInstanceOf(InMemoryRequirementPublicationStore.class);

            RequirementPublicationLedger ledger = context.getBean(RequirementPublicationLedger.class);
            RequirementPublicationStore store = context.getBean(RequirementPublicationStore.class);
            ledger.prepare(new RequirementPublicationPrepareCommand(
                    "op-wiring-1",
                    "task-wiring-1",
                    "stage-1",
                    "main",
                    "requirement/task-wiring-1",
                    "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
            ));
            assertThat(store.findByOperationId("op-wiring-1"))
                    .isPresent()
                    .get()
                    .extracting(publication -> publication.status())
                    .isEqualTo(RequirementPublicationStatus.PREPARED);
        });
    }

    @Test
    void shouldNotRegisterMemoryPublicationBeansWhenKnowledgeStoreIsPostgres() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(InMemoryAgentObservabilityConfiguration.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RequirementPublicationLedger.class));
    }
}
