package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.engine.project.memory.ProjectMemoryOperationHandler;
import com.wish.rd.engine.project.memory.ProjectMemoryOperationWorker;
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
import java.util.function.LongSupplier;

/** Default-off scheduler for bounded project-memory consolidation workers. */
@Configuration(proxyBeanMethods = false)
public class ProjectMemoryOperationWorkerConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rd.project-memory.worker", name = "enabled", havingValue = "true")
    ProjectMemoryOperationWorker projectMemoryOperationWorker(
            ProjectMemoryOperationStore store,
            ProjectMemoryOperationHandler handler,
            @Value("${rd.project-memory.worker.lease-duration-ms:30000}") long leaseDurationMs,
            @Value("${rd.project-memory.worker.base-backoff-ms:1000}") long baseBackoffMs,
            LongSupplier projectMemoryWorkerClock
    ) {
        return new ProjectMemoryOperationWorker(
                store,
                handler,
                leaseDurationMs,
                baseBackoffMs,
                projectMemoryWorkerClock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rd.project-memory.worker", name = "enabled", havingValue = "true")
    ProjectMemoryOperationHandler projectMemoryOperationHandler() {
        return claim -> claim.checkpointJson();
    }

    @Bean
    LongSupplier projectMemoryWorkerClock() {
        return System::currentTimeMillis;
    }

    @Component
    @ConditionalOnBean(ProjectMemoryOperationWorker.class)
    static class ProjectMemoryOperationWorkerScheduler {
        private final ProjectMemoryOperationWorker worker;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final String owner;

        ProjectMemoryOperationWorkerScheduler(
                ProjectMemoryOperationWorker worker,
                @Value("${rd.project-memory.worker.owner-id:bootstrap-project-memory-worker}") String owner
        ) {
            this.worker = Objects.requireNonNull(worker, "worker must not be null");
            this.owner = owner == null || owner.isBlank() ? "bootstrap-project-memory-worker" : owner.strip();
        }

        @Scheduled(fixedDelayString = "${rd.project-memory.worker.poll-interval-ms:5000}")
        void pollOneOperation() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            try {
                worker.runOnce(owner);
            } finally {
                running.set(false);
            }
        }
    }
}
