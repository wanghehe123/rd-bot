package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.RoleAwareRepairExecutor;

import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleAwareRepairExecutorTest {

    @Test
    void shouldExecuteReviewerAndArchitectOnTheAgentContainerNotModelOnlyHttp() {
        RecordingRepairExecutor docker = new RecordingRepairExecutor("docker");
        RecordingRepairExecutor openAi = new RecordingRepairExecutor("openai-chat-completions");
        RoleAwareRepairExecutor executor = new RoleAwareRepairExecutor(docker, openAi);

        RepairExecutionResult review = executor.execute(command("REQUIREMENT_REVIEWER"));
        RepairExecutionResult architect = executor.execute(command("SOLUTION_ARCHITECT"));
        RepairExecutionResult coding = executor.execute(command("CODING_AGENT"));
        RepairExecutionResult qa = executor.execute(command("QA_AGENT"));

        assertEquals("docker", review.summary());
        assertEquals("docker", architect.summary());
        assertEquals("docker", coding.summary());
        assertEquals("docker", qa.summary());
        assertEquals(
                List.of("REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"),
                docker.roles()
        );
        assertEquals(List.of(), openAi.roles());
    }

    private RepairJobCommand command(String role) {
        return new RepairJobCommand(
                "repair-" + role,
                "task-1001",
                "",
                "需求",
                "prompt",
                "https://github.com/acme/order.git",
                "acme",
                "order",
                "main",
                "requirement/task-1001",
                Map.of("agentRole", role),
                Map.of()
        );
    }

    private static final class RecordingRepairExecutor implements RepairExecutorPort {

        private final String name;
        private final List<String> roles = new ArrayList<>();

        private RecordingRepairExecutor(String name) {
            this.name = name;
        }

        @Override
        public RepairExecutionResult execute(RepairJobCommand command) {
            roles.add(command.contextJson().getOrDefault("agentRole", ""));
            return new RepairExecutionResult(
                    RepairExecutionStatus.SUCCESS,
                    name,
                    "",
                    List.of(),
                    Map.of("status", "SUCCESS"),
                    Map.of("provider", name),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    ""
            );
        }

        private List<String> roles() {
            return List.copyOf(roles);
        }
    }
}
