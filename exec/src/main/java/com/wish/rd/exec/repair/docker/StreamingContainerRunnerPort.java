package com.wish.rd.exec.repair.docker;

import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;

import java.io.IOException;

/** Optional L2 container port: stream stdout/stderr while retaining the sync result contract. */
public interface StreamingContainerRunnerPort extends ContainerRunnerPort {

    ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException;

    @Override
    default ContainerRunResult run(ContainerRunRequest request) throws IOException {
        return run(request, ContainerOutputListener.noop());
    }
}
