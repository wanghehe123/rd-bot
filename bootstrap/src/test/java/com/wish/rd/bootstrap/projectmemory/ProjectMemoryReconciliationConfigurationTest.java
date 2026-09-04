package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.engine.project.memory.ProjectMemoryReconciliationScanner;
import com.wish.rd.engine.project.memory.ProjectMemoryReconciliationSourcePort;
import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryOperationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectMemoryReconciliationConfigurationTest {

    @Test
    void shouldOnlyRegisterReconciliationScannerWhenExplicitlyEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(ProjectMemoryReconciliationScanner.class).length));
        contextRunner
                .withPropertyValues("rd.project-memory.reconcile.enabled=true")
                .run(context -> assertEquals(1, context.getBeanNamesForType(ProjectMemoryReconciliationScanner.class).length));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ProjectMemoryReconciliationConfiguration.class)
    static class TestContext {
        @Bean
        ProjectMemoryOperationStore projectMemoryOperationStore() {
            return new InMemoryProjectMemoryOperationStore();
        }

        @Bean
        ProjectMemoryReconciliationSourcePort projectMemoryReconciliationSourcePort() {
            return limit -> List.of();
        }
    }
}
