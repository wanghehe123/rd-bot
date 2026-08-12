package com.wish.rd.exec.repair.oracle;

import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;

/**
 * Creates a fresh Host-controlled context for one frozen assertion scope.
 * The implementation must never derive a workspace or base URL from agent result JSON.
 */
@FunctionalInterface
public interface HostVerifierWorkspaceFactory {

    /**
     * Replays the reviewed candidate patch in a clean verification workspace.
     *
     * @param command Host-created QA command containing the verified candidate patch attachment
     * @param request neutral Host scope identity; it intentionally carries no executable assertion specification
     * @return Host-owned workspace descriptor
     * @throws Exception when a clean verification context cannot be created
     */
    HostVerifierWorkspace create(RepairJobCommand command, HostVerifierWorkspaceRequest request) throws Exception;

    /** Returns a fail-closed factory for installations without a Host verification workspace. */
    static HostVerifierWorkspaceFactory unavailable() {
        return (command, bundle) -> {
            throw new IllegalStateException("Host verifier workspace factory is unavailable");
        };
    }
}
