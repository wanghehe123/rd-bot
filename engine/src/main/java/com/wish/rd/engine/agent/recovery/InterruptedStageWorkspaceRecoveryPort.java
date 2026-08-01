package com.wish.rd.engine.agent.recovery;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.recovery.model.RecoveredWorkspaceExecution;

import java.util.Optional;

/**
 * Reads a persisted task workspace and returns a settled execution snapshot when the
 * Bridge lifecycle and result contract are complete enough to reconcile.
 */
@FunctionalInterface
public interface InterruptedStageWorkspaceRecoveryPort {

    /**
     * Attempts to recover a settled workspace output for the given stage attempt.
     *
     * @param stage interrupted stage attempt
     * @return recovered execution when lifecycle and result are complete; otherwise empty
     */
    Optional<RecoveredWorkspaceExecution> tryRecover(AgentStageRun stage);

    /**
     * No-op recovery port used when workspace inspection is unavailable.
     */
    static InterruptedStageWorkspaceRecoveryPort unavailable() {
        return stage -> Optional.empty();
    }
}
