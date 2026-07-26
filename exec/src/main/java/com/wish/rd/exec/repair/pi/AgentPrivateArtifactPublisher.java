package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.io.IOException;
import java.util.List;

/**
 * Publishes Pi artifacts that must not remain in the normal stage-artifact preview path.
 *
 * <p>The exec module only knows the boundary. Bootstrap supplies the RustFS/S3
 * implementation, keeping object-storage concerns out of the executor.</p>
 */
@FunctionalInterface
public interface AgentPrivateArtifactPublisher {

    List<RepairArtifact> publish(
            RepairJobCommand command,
            AgentExecutionProfileSnapshot snapshot,
            RepairWorkspace workspace
    ) throws IOException;

    static AgentPrivateArtifactPublisher noop() {
        return (command, snapshot, workspace) -> List.of();
    }
}
