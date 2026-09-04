package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemorySecurityAlert;

/** Publishes high-priority project memory security alerts. */
@FunctionalInterface
public interface ProjectMemorySecurityAlertSink {
    void publish(ProjectMemorySecurityAlert alert);

    static ProjectMemorySecurityAlertSink noop() {
        return alert -> { };
    }
}
