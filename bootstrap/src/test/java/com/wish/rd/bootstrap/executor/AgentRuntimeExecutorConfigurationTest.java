package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.runtime.AgentRuntimeRouter;
import com.wish.rd.exec.repair.runtime.UnsupportedAgentRuntimeException;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wiring contract of the runtime router, which is only reachable when
 * {@code rd.executor.agent-runtime.enabled=true}.
 */
class AgentRuntimeExecutorConfigurationTest {

    @Test
    void shouldWireRouterWhenNoExecutorIsAvailable() {
        AgentRuntimeExecutorConfiguration configuration = new AgentRuntimeExecutorConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();

        AgentRuntimeRouter router = configuration.agentRuntimeRouter(
                beans.getBeanProvider(RepairExecutorPort.class),
                beans.getBeanProvider(DockerPiAgentExecutor.class)
        );

        assertNotNull(router, "an empty router must still be created so the context can start");
    }

    @Test
    void shouldReportTheRequestedRuntimeWhenItHasNoExecutor() {
        AgentRuntimeExecutorConfiguration configuration = new AgentRuntimeExecutorConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        AgentRuntimeRouter router = configuration.agentRuntimeRouter(
                beans.getBeanProvider(RepairExecutorPort.class),
                beans.getBeanProvider(DockerPiAgentExecutor.class)
        );

        UnsupportedAgentRuntimeException failure = assertThrows(
                UnsupportedAgentRuntimeException.class,
                () -> router.execute(new AgentRuntimeExecutionRequest(snapshot(AgentRuntimeType.PI), command()))
        );

        assertTrue(failure.getMessage().contains("PI"), "unexpected message: " + failure.getMessage());
    }

    @Test
    void shouldRouteLegacyRuntimesToTheLegacyExecutorWhenPresent() {
        AgentRuntimeExecutorConfiguration configuration = new AgentRuntimeExecutorConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("repairExecutor", successfulRepairExecutor());

        AgentRuntimeRouter router = configuration.agentRuntimeRouter(
                beans.getBeanProvider(RepairExecutorPort.class),
                beans.getBeanProvider(DockerPiAgentExecutor.class)
        );
        RepairExecutionResult result = router.execute(
                new AgentRuntimeExecutionRequest(snapshot(AgentRuntimeType.CLAUDE_CODE), command())
        );

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
    }

    private AgentExecutionProfileSnapshot snapshot(AgentRuntimeType runtimeType) {
        String snapshotJson = "{\"runtimeType\":\"" + runtimeType.name() + "\"}";
        return new AgentExecutionProfileSnapshot(
                "agent-profile-stage-1",
                "stage-1",
                "task-1001",
                "CODING_AGENT",
                1,
                runtimeType,
                snapshotJson,
                AgentExecutionProfileSnapshot.sha256(snapshotJson),
                1000L
        );
    }

    private RepairJobCommand command() {
        return new RepairJobCommand(
                "task-1001",
                "task-1001",
                "",
                "需求交付",
                "implement requirement",
                "https://github.com/acme/order.git",
                "acme",
                "order",
                "main",
                "requirement/task-1001",
                Map.of("taskType", "REQUIREMENT"),
                Map.of("bridge", "engine-requirement-executor")
        );
    }

    private RepairExecutorPort successfulRepairExecutor() {
        return ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "done",
                "",
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
    }
}
