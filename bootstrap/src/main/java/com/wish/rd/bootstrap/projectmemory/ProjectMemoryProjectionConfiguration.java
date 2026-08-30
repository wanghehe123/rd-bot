package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.rag.project.memory.ProjectMemoryProjectionPort;
import com.wish.rd.rag.project.memory.impl.DisabledProjectMemoryProjectionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the future project-memory projection SPI in its default-off state.
 *
 * <p>This change does not register an OpenViking memory outbox, binding, adapter, or worker.
 * A later OpenSpec change may add those behind an explicit enable switch.
 */
@Configuration(proxyBeanMethods = false)
public class ProjectMemoryProjectionConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "rd.project-memory.projection.mode",
            havingValue = "OFF",
            matchIfMissing = true)
    ProjectMemoryProjectionPort projectMemoryProjectionPort() {
        return new DisabledProjectMemoryProjectionPort();
    }
}
