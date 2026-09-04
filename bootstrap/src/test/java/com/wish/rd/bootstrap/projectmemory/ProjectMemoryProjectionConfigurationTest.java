package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.rag.project.memory.ProjectMemoryProjectionMode;
import com.wish.rd.rag.project.memory.ProjectMemoryProjectionPort;
import com.wish.rd.rag.project.memory.impl.DisabledProjectMemoryProjectionPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProjectMemoryProjectionConfigurationTest {

    @Test
    void shouldRegisterDisabledProjectionPortByDefaultAndNeverEnableOpenVikingMemory() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class);

        contextRunner.run(context -> {
            assertEquals(1, context.getBeanNamesForType(ProjectMemoryProjectionPort.class).length);
            ProjectMemoryProjectionPort port = context.getBean(ProjectMemoryProjectionPort.class);
            assertInstanceOf(DisabledProjectMemoryProjectionPort.class, port);
            assertEquals(ProjectMemoryProjectionMode.OFF, port.mode());
            assertFalse(port.enabled());
            assertFalse(hasOpenVikingMemoryBean(context.getBeanDefinitionNames()));
        });

        contextRunner
                .withPropertyValues("rd.project-memory.projection.mode=SHADOW")
                .run(context -> {
                    assertEquals(0, context.getBeanNamesForType(ProjectMemoryProjectionPort.class).length);
                    assertFalse(hasOpenVikingMemoryBean(context.getBeanDefinitionNames()));
                });

        contextRunner
                .withPropertyValues("rd.project-memory.projection.enabled=true")
                .run(context -> {
                    ProjectMemoryProjectionPort port = context.getBean(ProjectMemoryProjectionPort.class);
                    assertFalse(port.enabled(), "this change must not enable OpenViking memory projection");
                    assertInstanceOf(DisabledProjectMemoryProjectionPort.class, port);
                    assertFalse(hasOpenVikingMemoryBean(context.getBeanDefinitionNames()));
                });
    }

    private static boolean hasOpenVikingMemoryBean(String[] beanNames) {
        return Arrays.stream(beanNames).anyMatch(name -> {
            String lowered = name.toLowerCase(Locale.ROOT);
            return lowered.contains("openviking") && lowered.contains("memory");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ProjectMemoryProjectionConfiguration.class)
    static class TestContext {
    }
}
