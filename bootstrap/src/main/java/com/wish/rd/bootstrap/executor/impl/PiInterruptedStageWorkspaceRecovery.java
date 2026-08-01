package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.recovery.InterruptedStageWorkspaceRecoveryPort;
import com.wish.rd.engine.agent.recovery.model.RecoveredWorkspaceExecution;
import com.wish.rd.exec.repair.pi.PiSettledWorkspaceRecovery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Bootstrap adapter that inspects persisted Pi workspace output for interrupted stage recovery.
 */
@Component
@ConditionalOnProperty(prefix = "rd.executor.docker", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class PiInterruptedStageWorkspaceRecovery implements InterruptedStageWorkspaceRecoveryPort {

    private final PiSettledWorkspaceRecovery recovery;

    @Autowired
    public PiInterruptedStageWorkspaceRecovery(DockerExecutorProperties properties) {
        Path workspaceRoot = properties == null
                ? DockerExecutorProperties.DEFAULT_WORKSPACE_ROOT
                : properties.getWorkspaceRoot();
        this.recovery = new PiSettledWorkspaceRecovery(workspaceRoot);
    }

    /** Package-visible for focused unit tests. */
    PiInterruptedStageWorkspaceRecovery(Path workspaceRoot) {
        this.recovery = new PiSettledWorkspaceRecovery(workspaceRoot);
    }

    @Override
    public Optional<RecoveredWorkspaceExecution> tryRecover(AgentStageRun stage) {
        if (stage == null) {
            return Optional.empty();
        }
        try {
            return recovery.tryRecover(stage.taskId(), stage.stageRunId(), stage.role().name())
                    .map(snapshot -> new RecoveredWorkspaceExecution(
                            snapshot.success(),
                            snapshot.resultJson(),
                            snapshot.errorCategory(),
                            snapshot.errorMessage(),
                            snapshot.providerName(),
                            snapshot.providerAttemptsJson()
                    ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
