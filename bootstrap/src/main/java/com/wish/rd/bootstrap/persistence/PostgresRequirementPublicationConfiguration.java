package com.wish.rd.bootstrap.persistence;

import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the mandatory publication ledger and reconciliation path for PostgreSQL mode.
 * Memory mode already registers both store and ledger in
 * {@code InMemoryAgentObservabilityConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementPublicationConfiguration {

    @Bean
    @ConditionalOnMissingBean(RequirementPublicationLedger.class)
    RequirementPublicationLedger requirementPublicationLedger(
            ObjectProvider<RequirementPublicationStore> storeProvider
    ) {
        RequirementPublicationStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException(
                    "PostgreSQL requirement publication requires a publication ledger");
        }
        return new RequirementPublicationLedger(store);
    }

    @Bean
    @ConditionalOnMissingBean(RequirementPublicationReconciliationService.class)
    RequirementPublicationReconciliationService requirementPublicationReconciliationService(
            ObjectProvider<RequirementPublicationLedger> ledgerProvider,
            ObjectProvider<RequirementPublicationReconcilePort> reconcilerProvider
    ) {
        RequirementPublicationLedger ledger = ledgerProvider.getIfAvailable();
        if (ledger == null) {
            throw new IllegalStateException(
                    "PostgreSQL requirement publication requires a publication ledger");
        }
        RequirementPublicationReconcilePort reconciler = reconcilerProvider.getIfAvailable();
        if (reconciler == null) {
            throw new IllegalStateException(
                    "PostgreSQL requirement publication requires a publication reconciliation service");
        }
        return new RequirementPublicationReconciliationService(ledger, reconciler);
    }
}
