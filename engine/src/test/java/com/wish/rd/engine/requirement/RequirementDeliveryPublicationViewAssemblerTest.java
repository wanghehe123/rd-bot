package com.wish.rd.engine.requirement;

import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryPublicationViewAssemblerTest {

    private final RequirementDeliveryPublicationViewAssembler assembler =
            new RequirementDeliveryPublicationViewAssembler();

    @Test
    void shouldAssembleProductionArrayFieldsFromPr28Shape() throws IOException {
        RequirementDeliveryPublicationView view = assembler.assemble(fixture());

        assertEquals(1, view.schemaVersion());
        assertEquals("完成外卖接单页的订单筛选与状态展示", view.summary());
        assertEquals(2, view.coding().changedFiles().size());
        assertEquals("npm run build", view.coding().testCommands().get(1));
        assertEquals("PASSED", view.coding().testStatus());
        assertEquals(2, view.qa().acceptanceResults().size());
        assertTrue(view.evidenceReferences().contains("qa-evidence/manifest.json"));
    }

    @Test
    void shouldSupportAllDocumentedCompatibilityLocations() {
        for (String result : new String[]{stageResultsJson(), roleResultsJson(), rootFallbackJson()}) {
            RequirementDeliveryPublicationView view = assembler.assemble(result);
            assertEquals(java.util.List.of("src/App.java"), view.coding().changedFiles());
            assertEquals(java.util.List.of("./mvnw test"), view.coding().testCommands());
        }
    }

    @Test
    void shouldAcceptOneLegacyChangedFileScalarButNotLegacyTestSummary() {
        RequirementDeliveryPublicationView view = assembler.assemble(baseJson(
                "\"changedFiles\":\"src/App.java\",\"testCommands\":[\"./mvnw test\"],\"testStatus\":\"PASSED\""));

        assertEquals(java.util.List.of("src/App.java"), view.coding().changedFiles());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(baseJson(
                        "\"changedFiles\":[\"src/App.java\"],\"testSummary\":\"tests passed\",\"testStatus\":\"PASSED\"")));
        assertTrue(error.getMessage().contains("testCommands"));
    }

    @Test
    void shouldRejectDuplicateSuccessfulRoleInSameContainer() {
        String json = baseJson("\"changedFiles\":[\"src/App.java\"],\"testCommands\":[\"./mvnw test\"],\"testStatus\":\"PASSED\"")
                .replace("{\"role\":\"CODING_AGENT\"", "{\"role\":\"CODING_AGENT\"")
                .replace("]}", ",{\"role\":\"CODING_AGENT\",\"success\":true,\"resultJson\":{\"changedFiles\":[\"src/Other.java\"],\"testCommands\":[\"./mvnw test\"],\"testStatus\":\"PASSED\"}}]}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(json));
        assertTrue(error.getMessage().contains("duplicate") && error.getMessage().contains("CODING_AGENT"));
    }

    @Test
    void shouldRejectMalformedRoleResultAndConflictingCriticalFacts() {
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble("{\"multiAgentStages\":[{\"role\":\"CODING_AGENT\",\"success\":true,\"resultJson\":\"{bad\"}]}"));
        assertTrue(malformed.getMessage().contains("multiAgentStages.CODING_AGENT.resultJson"));

        String conflict = """
                {"changedFiles":["src/Root.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED",
                 "multiAgentStages":[{"role":"CODING_AGENT","success":true,"resultJson":{
                   "changedFiles":["src/Stage.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED"}}]}
                """;
        IllegalArgumentException conflictError = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(conflict));
        assertTrue(conflictError.getMessage().contains("conflict")
                && conflictError.getMessage().contains("changedFiles"));
    }

    @Test
    void shouldRejectExplicitFailedAggregateEvenWhenAllStagesSucceeded() throws IOException {
        String failedAggregate = fixture().replace(
                "\"multiAgentStatus\": \"SUCCESS\"",
                "\"multiAgentStatus\": \"FAILED\"");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(failedAggregate));

        assertTrue(error.getMessage().contains("multiAgentStatus"));
        assertTrue(error.getMessage().contains("SUCCESS"));
    }

    @Test
    void shouldNotFallBackToStaleQaWhenCurrentQaStageFailed() {
        String json = """
                {"changedFiles":["src/App.java"],"testCommands":["./mvnw test"],
                 "testStatus":"PASSED","riskLevel":"LOW",
                 "multiAgentStages":[{"role":"QA_AGENT","success":false,"status":"FAILED_RETRYABLE"}],
                 "roleResults":{"QA_AGENT":{"status":"PASSED","summary":"stale","failureCategory":"NONE",
                   "retryRecommendation":"NONE","browserValidation":{"required":false,"performed":false,
                   "decisionSource":"NOT_APPLICABLE","browser":"chromium","viewports":[]},
                   "acceptanceResults":[],"evidenceManifestArtifactId":"qa/stale.json"}}}
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(json));

        assertTrue(error.getMessage().contains("multiAgentStages.QA_AGENT"));
        assertTrue(error.getMessage().contains("not successful"));
    }

    @Test
    void shouldNotRehabilitateFailedCurrentRequiredRoleFromLegacyStages() {
        String json = """
                {"changedFiles":["src/App.java"],"testCommands":["./mvnw test"],
                 "testStatus":"PASSED","riskLevel":"LOW",
                 "multiAgentStages":[{"role":"REQUIREMENT_REVIEWER","success":false,"status":"FAILED"}],
                 "stageResults":[{"role":"REQUIREMENT_REVIEWER","success":true,"result":{}}]}
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(json));

        assertTrue(error.getMessage().contains("multiAgentStages.REQUIREMENT_REVIEWER"));
        assertTrue(error.getMessage().contains("not successful"));
    }

    @Test
    void shouldRejectConflictingBrowserValidationAcrossSources() {
        String json = """
                {"changedFiles":["src/App.java"],"testCommands":["./mvnw test"],
                 "testStatus":"PASSED","riskLevel":"LOW",
                 "multiAgentStages":[{"role":"QA_AGENT","success":true,"resultJson":{
                   "status":"PASSED","failureCategory":"NONE","retryRecommendation":"NONE",
                   "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE",
                   "browser":"chromium","viewports":[]}}}],
                 "roleResults":{"QA_AGENT":{"status":"PASSED","failureCategory":"NONE",
                   "retryRecommendation":"NONE","browserValidation":{"required":true,"performed":true,
                   "decisionSource":"PROFILE","baseUrl":"https://example.test","browser":"chromium",
                   "viewports":["desktop"]}}}}
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(json));

        assertTrue(error.getMessage().contains("QA_AGENT.browserValidation"));
        assertTrue(error.getMessage().contains("conflict"));
    }

    @Test
    void shouldTreatReorderedEquivalentFactsAsEqual() {
        String json = """
                {"changedFiles":["src/B.java","src/A.java"],
                 "testCommands":["./mvnw verify","./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW",
                 "multiAgentStages":[{"role":"CODING_AGENT","success":true,"resultJson":{
                   "changedFiles":["src/A.java","src/B.java"],
                   "testCommands":["./mvnw test","./mvnw verify"],"testStatus":"PASSED","riskLevel":"LOW"}},
                  {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED",
                   "failureCategory":"NONE","retryRecommendation":"NONE",
                   "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE",
                   "browser":"chromium","viewports":["mobile","desktop"]}}}],
                 "roleResults":{"QA_AGENT":{"retryRecommendation":"NONE","failureCategory":"NONE",
                   "status":"PASSED","browserValidation":{"viewports":["desktop","mobile"],
                   "browser":"chromium","decisionSource":"NOT_APPLICABLE","performed":false,"required":false}}}}
                """;

        RequirementDeliveryPublicationView view = assembler.assemble(json);

        assertEquals(java.util.List.of("src/A.java", "src/B.java"),
                view.coding().changedFiles().stream().sorted().toList());
    }

    private static String baseJson(String codingFields) {
        return "{\"multiAgentStages\":[{\"role\":\"CODING_AGENT\",\"success\":true,\"resultJson\":{" + codingFields + "}}]}";
    }

    private static String stageResultsJson() {
        return "{\"stageResults\":[{\"role\":\"CODING_AGENT\",\"success\":true,\"result\":{\"changedFiles\":[\"src/App.java\"],\"testCommands\":[\"./mvnw test\"],\"testStatus\":\"PASSED\"}}]}";
    }

    private static String roleResultsJson() {
        return "{\"roleResults\":{\"CODING_AGENT\":{\"changedFiles\":[\"src/App.java\"],\"testCommands\":[\"./mvnw test\"],\"testStatus\":\"PASSED\"}}}";
    }

    private static String rootFallbackJson() {
        return "{\"changedFiles\":[\"src/App.java\"],\"testCommands\":[\"./mvnw test\"],\"testStatus\":\"PASSED\"}";
    }

    private static String fixture() throws IOException {
        try (InputStream input = RequirementDeliveryPublicationViewAssemblerTest.class
                .getResourceAsStream("/requirement-publication/pr-28-production-result.json")) {
            if (input == null) {
                throw new IOException("fixture missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
