package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.engine.project.memory.ProjectMemoryReconciliationScanner;
import com.wish.rd.engine.project.memory.ProjectMemoryReconciliationSourcePort;
import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Default-off scanner that backfills missing deterministic memory operations. */
@Configuration(proxyBeanMethods = false)
public class ProjectMemoryReconciliationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rd.project-memory.reconcile", name = "enabled", havingValue = "true")
    ProjectMemoryReconciliationScanner projectMemoryReconciliationScanner(
            ProjectMemoryReconciliationSourcePort source,
            ProjectMemoryOperationStore operationStore
    ) {
        return new ProjectMemoryReconciliationScanner(source, operationStore);
    }

    @Component
    @ConditionalOnBean(ProjectMemoryReconciliationScanner.class)
    static class ProjectMemoryReconciliationScheduler {
        private final ProjectMemoryReconciliationScanner scanner;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final int batchSize;

        ProjectMemoryReconciliationScheduler(
                ProjectMemoryReconciliationScanner scanner,
                @Value("${rd.project-memory.reconcile.batch-size:20}") int batchSize
        ) {
            this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
            this.batchSize = Math.max(1, batchSize);
        }

        @Scheduled(fixedDelayString = "${rd.project-memory.reconcile.poll-interval-ms:30000}")
        void reconcileMissingOperations() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            try {
                scanner.reconcileOnce(batchSize);
            } finally {
                running.set(false);
            }
        }
    }
}
