package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryProjectionMode;
import com.wish.rd.rag.project.memory.ProjectMemoryProjectionPort;

/**
 * Default projection port. Always {@link ProjectMemoryProjectionMode#OFF} so an unconfigured
 * deployment never writes project memory to OpenViking.
 */
public final class DisabledProjectMemoryProjectionPort implements ProjectMemoryProjectionPort {

    @Override
    public ProjectMemoryProjectionMode mode() {
        return ProjectMemoryProjectionMode.OFF;
    }
}
