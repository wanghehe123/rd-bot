package com.wish.rd.bootstrap.persistence;

import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresRequirementPublicationWiringTest {

    @Test
    void postgresModeFailsClosedWhenPublicationLedgerIsMissing() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(PostgresRequirementPublicationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("publication ledger");
                });
    }

    @Test
    void postgresModeAcceptsCompleteLedgerAndReconcilePath() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(
                        PostgresRequirementPublicationConfiguration.class,
                        CompletePublicationPathConfiguration.class
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RequirementPublicationLedger.class);
                    assertThat(context).hasSingleBean(RequirementPublicationReconciliationService.class);
                });
    }

    @Test
    void postgresModeFailsClosedWhenReconcilePathIsMissing() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(
                        PostgresRequirementPublicationConfiguration.class,
                        LedgerOnlyPublicationPathConfiguration.class
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("publication reconciliation service");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CompletePublicationPathConfiguration {

        @Bean
        RequirementPublicationStore requirementPublicationStore() {
            return new InMemoryRequirementPublicationStore();
        }

        @Bean
        RequirementPublicationReconcilePort requirementPublicationReconcilePort() {
            return new RequirementPublicationReconcilePort() {
                @Override
                public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(
                        ReconcileQuery query
                ) {
                    return Optional.empty();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LedgerOnlyPublicationPathConfiguration {

        @Bean
        RequirementPublicationStore requirementPublicationStore() {
            return new InMemoryRequirementPublicationStore();
        }
    }
}
