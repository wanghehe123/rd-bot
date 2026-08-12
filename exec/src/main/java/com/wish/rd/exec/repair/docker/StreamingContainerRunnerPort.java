package com.wish.rd.exec.repair.docker;

import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;

import java.io.IOException;

/** Optional L2 container port: stream stdout/stderr while retaining the sync result contract. */
public interface StreamingContainerRunnerPort extends ContainerRunnerPort {

    /**
     * Whether this runner executes the task-local network/relay plan carried by a
     * {@link ContainerRunRequest}. Relay-backed Pi execution must fail closed when
     * the runner cannot honor this boundary.
     */
    default boolean supportsNetworkPlans() {
        return false;
    }

    ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException;

    @Override
    default ContainerRunResult run(ContainerRunRequest request) throws IOException {
        return run(request, ContainerOutputListener.noop());
    }
}
