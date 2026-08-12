package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.requirement.publication.RequirementPublicationCommitPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRequirementPublicationCommitAdapterTest {

    @Test
    void publicationCommitRejectsZeroExpectedFence() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementPublicationCommitPort.RequirementPublicationCommitCommand(
                "1784200000001", "sha256:publication-operation", "https://github.com/acme/waimai/pull/42",
                "{}", 9L, RdTaskStatus.PR_CREATING, 0L));
    }

    @Test
    void transactionalCommitAdapterStartsWithTheProductionClassProxyMode() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ClassProxyTransactionConfiguration.class
        )) {
            RequirementPublicationCommitPort port = context.getBean(RequirementPublicationCommitPort.class);

            assertTrue(AopUtils.isCglibProxy(port));
        }
    }

    @Test
    void commitsTaskSnapshotTimelineAndPublicationFromOneTransactionalPort() throws Exception {
        RagStreamTaskRegistry taskRegistry = mock(RagStreamTaskRegistry.class);
        RequirementPublicationLedger ledger = mock(RequirementPublicationLedger.class);
        RdRequirementTask committed = committedTask();
        RequirementPublicationCommitPort.RequirementPublicationCommitCommand command = command();
        when(taskRegistry.markRequirementCommittedFenced(
                command.taskId(),
                command.expectedVersion(),
                command.expectedStatus(),
                command.expectedFencingToken(),
                command.pullRequestUrl(),
                command.executionResultJson()
        )).thenReturn(committed);
        PostgresRequirementPublicationCommitAdapter adapter =
                new PostgresRequirementPublicationCommitAdapter(taskRegistry, ledger);

        RdRequirementTask result = adapter.commit(command);

        assertEquals(committed, result);
        var order = inOrder(taskRegistry, ledger);
        order.verify(taskRegistry).markRequirementCommittedFenced(
                command.taskId(),
                command.expectedVersion(),
                command.expectedStatus(),
                command.expectedFencingToken(),
                command.pullRequestUrl(),
                command.executionResultJson());
        order.verify(ledger).markCommitted(command.operationId());
        Method method = PostgresRequirementPublicationCommitAdapter.class.getMethod(
                "commit", RequirementPublicationCommitPort.RequirementPublicationCommitCommand.class);
        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    @Test
    void staleFinalizeDoesNotAdvancePublicationLedger() {
        RagStreamTaskRegistry taskRegistry = mock(RagStreamTaskRegistry.class);
        RequirementPublicationLedger ledger = mock(RequirementPublicationLedger.class);
        RequirementPublicationCommitPort.RequirementPublicationCommitCommand command = command();
        when(taskRegistry.markRequirementCommittedFenced(
                command.taskId(),
                command.expectedVersion(),
                command.expectedStatus(),
                command.expectedFencingToken(),
                command.pullRequestUrl(),
                command.executionResultJson()
        )).thenThrow(new IllegalStateException("task CAS failed"));
        PostgresRequirementPublicationCommitAdapter adapter =
                new PostgresRequirementPublicationCommitAdapter(taskRegistry, ledger);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> adapter.commit(command));

        assertEquals("task CAS failed", failure.getMessage());
        verify(ledger, never()).markCommitted(command.operationId());
    }

    private static RequirementPublicationCommitPort.RequirementPublicationCommitCommand command() {
        return new RequirementPublicationCommitPort.RequirementPublicationCommitCommand(
                "1784200000001",
                "sha256:publication-operation",
                "https://github.com/acme/waimai/pull/42",
                "{\"status\":\"SUCCESS\"}",
                9L,
                RdTaskStatus.PR_CREATING,
                17L
        );
    }

    private static RdRequirementTask committedTask() {
        return RdRequirementTask.created(
                "1784200000001",
                new CreateRequirementTaskCommand(
                        "Atomic publication", "P1", "manual", "REQ-ATOMIC", "",
                        "https://github.com/acme/waimai.git", "main", List.of("PR committed"), false
                ),
                1_784_200_000_000L
        ).withState(
                RdTaskStatus.COMMITTED,
                "",
                "{\"status\":\"SUCCESS\"}",
                "https://github.com/acme/waimai/pull/42",
                "",
                1_784_200_000_001L
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ClassProxyTransactionConfiguration {

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        RequirementPublicationCommitPort requirementPublicationCommitPort() {
            return new PostgresRequirementPublicationCommitAdapter(
                    mock(RagStreamTaskRegistry.class),
                    mock(RequirementPublicationLedger.class)
            );
        }
    }
}
