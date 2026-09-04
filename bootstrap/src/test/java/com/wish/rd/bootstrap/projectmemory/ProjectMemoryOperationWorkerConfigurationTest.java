package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.engine.project.memory.ProjectMemoryOperationWorker;
import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryOperationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectMemoryOperationWorkerConfigurationTest {

    @Test
    void shouldOnlyRegisterProjectMemoryWorkerWhenExplicitlyEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(ProjectMemoryOperationWorker.class).length));
        contextRunner
                .withPropertyValues("rd.project-memory.worker.enabled=true")
                .run(context -> assertEquals(1, context.getBeanNamesForType(ProjectMemoryOperationWorker.class).length));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ProjectMemoryOperationWorkerConfiguration.class)
    static class TestContext {
        @Bean
        ProjectMemoryOperationStore projectMemoryOperationStore() {
            return new InMemoryProjectMemoryOperationStore();
        }
    }
}
