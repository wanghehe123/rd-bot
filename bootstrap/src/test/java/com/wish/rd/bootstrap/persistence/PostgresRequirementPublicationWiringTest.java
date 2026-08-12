package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.executor.impl.RequirementPublicationReconcileScheduler;
import com.wish.rd.bootstrap.threading.RdBotThreadPoolConfiguration;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationCommitPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationContinuationPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test
    void postgresModeFailsClosedWhenAtomicCommitPortIsMissing() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(
                        PostgresRequirementPublicationConfiguration.class,
                        LedgerAndReconcileOnlyConfiguration.class
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("atomic publication commit port");
                });
    }

    @Test
    void postgresModeFailsClosedWhenPublicationContinuationPortIsMissing() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(
                        PostgresRequirementPublicationConfiguration.class,
                        LedgerReconcileAndCommitOnlyConfiguration.class
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("publication continuation port");
                });
    }

    @Test
    void reconcileSchedulerFailsStartupWhenContinuationPortIsMissing() {
        RequirementPublicationStore store = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(store);
        AtomicLong ids = new AtomicLong(1L);
        new ApplicationContextRunner()
                .withPropertyValues("rd.requirement-publication.reconcile.enabled=true")
                .withBean(RequirementPublicationStore.class, () -> store)
                .withBean(RequirementPublicationReconciliationService.class,
                        () -> new RequirementPublicationReconciliationService(
                                ledger, query -> Optional.empty()))
                .withBean(RagStreamTaskRegistry.class, () -> new RagStreamTaskRegistry(
                        new InMemoryRdTaskStore(),
                        new InMemoryRdTaskStatusEventStore(),
                        new SnowflakeIdGenerator(1, 1, ids::getAndIncrement)))
                .withBean(
                        RdBotThreadPoolConfiguration.MAINTENANCE_EXECUTOR_BEAN,
                        AsyncTaskExecutor.class,
                        () -> new SimpleAsyncTaskExecutor("publication-reconcile-test-"))
                .withUserConfiguration(RequirementPublicationReconcileScheduler.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("RequirementPublicationContinuationPort");
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

        @Bean
        RequirementPublicationCommitPort requirementPublicationCommitPort() {
            return command -> {
                throw new AssertionError("wiring-only commit port must not execute");
            };
        }

        @Bean
        RequirementPublicationContinuationPort requirementPublicationContinuationPort() {
            return continuation -> {
                throw new AssertionError("wiring-only continuation port must not execute");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LedgerReconcileAndCommitOnlyConfiguration {

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

        @Bean
        RequirementPublicationCommitPort requirementPublicationCommitPort() {
            return command -> {
                throw new AssertionError("wiring-only commit port must not execute");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LedgerAndReconcileOnlyConfiguration {

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
