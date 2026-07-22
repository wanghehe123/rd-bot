package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;
import com.wish.rd.engine.requirement.RuleBasedRequirementPolicyGate;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentRequirementDeliveryRealSmokePayloadTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldUseConcreteRepositoryDeliveryRequirementInsteadOfMetaOrchestrationRequirement() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(requirementBody());
        String serialized = body.toPrettyString();

        assertFalse(serialized.contains("创建一条多 Agent 需求交付任务"));
        assertTrue(body.path("title").asText().contains("production smoke marker"));
        assertTrue(serialized.contains("docs/rd-bot-production-smoke.md"));
        assertTrue(serialized.contains("multi-agent-production-smoke"));
        assertTrue(serialized.contains("test -s docs/rd-bot-production-smoke.md"));
        assertTrue(serialized.contains("grep -q \\\"multi-agent-production-smoke\\\" docs/rd-bot-production-smoke.md"));
    }

    @Test
    void shouldUseConcreteFollowUpMarkerRequirementForExperienceReuseSmoke() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(followUpRequirementBody());
        String serialized = body.toPrettyString();

        assertTrue(body.path("title").asText().contains("follow-up marker"));
        assertTrue(serialized.contains("docs/rd-bot-production-smoke-follow-up.md"));
        assertTrue(serialized.contains("multi-agent-production-smoke-follow-up"));
        assertTrue(serialized.contains("test -s docs/rd-bot-production-smoke-follow-up.md"));
        assertTrue(serialized.contains(
                "grep -q \\\"multi-agent-production-smoke-follow-up\\\" docs/rd-bot-production-smoke-follow-up.md"
        ));
    }

    @Test
    void shouldStayBelowRuleBasedPolicyGateRiskForAutomaticSmokeExecution() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(requirementBody());
        RequirementPolicyDecision decision = new RuleBasedRequirementPolicyGate().decide(
                taskFrom(body),
                contextFrom(body),
                null,
                materialsFrom(body)
        );

        assertTrue(decision.allowed(), "smoke payload must be accepted by policy gate: " + decision);
    }

    private static String requirementBody() throws Exception {
        Method method = MultiAgentRequirementDeliveryRealSmokeTest.class.getDeclaredMethod(
                "requirementBody",
                MultiAgentProductionAcceptanceProfile.class
        );
        method.setAccessible(true);
        return (String) method.invoke(null, validProfile());
    }

    private static String followUpRequirementBody() throws Exception {
        Method method = MultiAgentRequirementDeliveryRealSmokeTest.class.getDeclaredMethod(
                "followUpRequirementBody",
                MultiAgentProductionAcceptanceProfile.class
        );
        method.setAccessible(true);
        return (String) method.invoke(null, validProfile());
    }

    private static MultiAgentProductionAcceptanceProfile validProfile() {
        return MultiAgentProductionAcceptanceProfile.from(
                Map.ofEntries(
                        entry("rd.multi-agent.smoke.production-evidence", "true"),
                        entry("rd.multi-agent.smoke.rd-bot-version", "0.1.0-smoke"),
                        entry("rd.multi-agent.smoke.environment-id", "local-machine"),
                        entry("rd.multi-agent.smoke.executed-by", "payload-test"),
                        entry("rd.multi-agent.smoke.base-url", "http://127.0.0.1:18080"),
                        entry("rd.multi-agent.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/ragent"),
                        entry("rd.multi-agent.smoke.postgres-user", "postgres"),
                        entry("rd.multi-agent.smoke.postgres-password", "postgres"),
                        entry("rd.multi-agent.smoke.repository-url",
                                "https://github.com/example-owner/example-repo.git"),
                        entry("rd.multi-agent.smoke.repo-owner", "example-owner"),
                        entry("rd.multi-agent.smoke.repo-name", "example-repo"),
                        entry("rd.multi-agent.smoke.expected-provider-count", "2"),
                        entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,MINIMAX_API_KEY"),
                        entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                        entry("rd.multi-agent.smoke.github-auth-mode", "GH_CLI_LOCAL_SMOKE"),
                        entry("rd.multi-agent.smoke.secret-scan-needles", "payload-canary")
                ),
                Map.of(
                        "LONGCAT_API_KEY", "longcat-secret",
                        "MINIMAX_API_KEY", "minimax-secret"
                )
        );
    }

    private static RdRequirementTask taskFrom(JsonNode body) throws Exception {
        return new RdRequirementTask(
                "payload-test-task",
                RdRequirementTask.TASK_TYPE,
                "ADMIN",
                "",
                "",
                body.path("priority").asText(),
                RdTaskStatus.CREATED,
                body.path("title").asText(),
                body.path("repositoryUrl").asText(),
                body.path("repoOwner").asText(),
                body.path("repoName").asText(),
                body.path("baseBranch").asText(),
                "",
                body.path("expectedResult").asText(),
                OBJECT_MAPPER.writeValueAsString(body.path("acceptanceCriteria")),
                "",
                "{}",
                "",
                "",
                1L,
                1L,
                false
        );
    }

    private static RequirementContextPackage contextFrom(JsonNode body) {
        return new RequirementContextPackage(
                "payload-test-task",
                body.path("expectedResult").asText(),
                textArray(body.path("acceptanceCriteria")),
                List.of(),
                List.of(),
                List.of("payload-test-material"),
                "payload-test-trace"
        );
    }

    private static List<TaskMaterial> materialsFrom(JsonNode body) {
        List<TaskMaterial> materials = new ArrayList<>();
        int index = 0;
        for (JsonNode material : body.path("materials")) {
            materials.add(new TaskMaterial(
                    "payload-test-material-" + index++,
                    "payload-test-task",
                    materialType(material.path("materialType").asText("REQUIREMENT_DOC")),
                    TaskMaterialSourceType.valueOf(material.path("sourceType").asText("MANUAL_TEXT")),
                    material.path("title").asText(),
                    "",
                    material.path("mimeType").asText(),
                    "",
                    material.path("content").asText(),
                    "",
                    "",
                    "",
                    "{}",
                    1L,
                    1L
            ));
        }
        return materials;
    }

    private static TaskMaterialType materialType(String value) {
        return switch (value == null ? "" : value) {
            case "TECHNICAL_DESIGN" -> TaskMaterialType.DESIGN_DOC;
            case "TEST_LOG" -> TaskMaterialType.ACCEPTANCE_CRITERIA;
            case "REFERENCE_DOC" -> TaskMaterialType.REFERENCE_DOC;
            default -> TaskMaterialType.REQUIREMENT_DOC;
        };
    }

    private static List<String> textArray(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : arrayNode) {
            values.add(value.asText());
        }
        return values;
    }
}
