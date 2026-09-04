package com.wish.rd.engine.requirement;

import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.EvidenceRef;
import com.wish.rd.engine.requirement.audit.EvidenceSourceKind;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.DeliveryReview;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementPullRequestBodyRendererTest {

    private final RequirementDeliveryPublicationViewAssembler assembler =
            new RequirementDeliveryPublicationViewAssembler();
    private final RequirementPullRequestBodyRenderer renderer = new RequirementPullRequestBodyRenderer();

    @Test
    void shouldRenderPr28FactsInFixedReadableSections() throws IOException {
        RequirementDeliveryPublicationView view = approved(assembler.assemble(fixture()), "task-28");

        String body = renderer.render("task-28", "op-28", "外卖接单页", view);

        assertEquals(expectedFixture(), body);
        assertEquals("Summary,Changes,Verification,Acceptance,Delivery Review,Evidence,RD-Bot Provenance",
                headings(body));
        assertTrue(body.contains("`src/pages/OrderAcceptPage.tsx`"));
        assertTrue(body.contains("`npm run test -- OrderAcceptPage`"));
        assertTrue(body.contains("| 1 | CURRENT | 订单筛选可用 | PASSED |"));
        assertTrue(body.contains("- Approved: **yes**"));
        assertTrue(body.contains("- Reason: deterministic delivery review passed"));
        assertTrue(body.contains("- taskId: `task-28`"));
        assertTrue(body.contains("- operationId: `op-28`"));
        assertFalse(body.contains("not reported"));
        assertFalse(body.contains("{}"));
    }

    @Test
    void shouldPrefixAuditedAcceptanceChecklistAndLabelAgentNarrativeUnverified() throws IOException {
        RequirementDeliveryPublicationView view = approved(assembler.assemble(fixture()), "task-28");
        EvidenceRef evidence = new EvidenceRef(
                "audit-9",
                EvidenceSourceKind.QA_EVIDENCE,
                "qa-evidence://artifacts/current.log",
                "sha256:" + "e".repeat(64));
        AuditedTaskState head = new AuditedTaskState(
                "task-28",
                2L,
                "sha256:" + "d".repeat(64),
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 9L),
                List.of(
                        new AuditedRecord(
                                "AC-001",
                                AuditedRecordKind.REQUIREMENT,
                                true,
                                "订单筛选可用",
                                AuditedRecordStatus.COMPLETED,
                                List.of(evidence),
                                "",
                                ""),
                        new AuditedRecord(
                                "AC-002",
                                AuditedRecordKind.REQUIREMENT,
                                true,
                                "既有订单列表回归",
                                AuditedRecordStatus.PENDING,
                                List.of(),
                                "",
                                ""),
                        new AuditedRecord(
                                "GATE-BUILD",
                                AuditedRecordKind.GATE,
                                true,
                                "build",
                                AuditedRecordStatus.PENDING,
                                List.of(),
                                "",
                                "")),
                "audit-9");

        String body = renderer.render("task-28", "op-28", "外卖接单页", view, head);

        assertEquals(
                "已审计验收清单,未核验叙述,Summary,Changes,Verification,Acceptance,Delivery Review,Evidence,RD-Bot Provenance",
                headings(body));
        int checklist = body.indexOf("## 已审计验收清单");
        int unverified = body.indexOf("## 未核验叙述");
        int summary = body.indexOf("## Summary");
        int agentBody = body.indexOf("实现了外卖接单页，并补充验证。");
        assertTrue(checklist >= 0 && checklist < unverified && unverified < summary);
        assertTrue(unverified < agentBody);
        assertTrue(body.contains("`AC-001`"));
        assertTrue(body.contains("COMPLETED"));
        assertTrue(body.contains("`audit-9`"));
        assertTrue(body.contains("`qa-evidence://artifacts/current.log`"));
        assertTrue(body.contains("`AC-002`"));
        assertTrue(body.contains("PENDING"));
        assertTrue(body.contains("未核验叙述"));
        assertFalse(body.contains("- Agent narrative:"));
    }

    @Test
    void shouldEscapeMarkdownAndUseStableDeduplicatedOrdering() {
        RequirementDeliveryPublicationView raw = assembler.assemble("""
                {"summary":"Summary | line","changedFiles":["z.md","a|b.md","z.md"],
                 "testCommands":["z `test`","a | test","z `test`"],"testStatus":"PASSED","riskLevel":"LOW"}
                """);
        RequirementDeliveryPublicationView view = approved(raw, "task`1");

        String body = renderer.render("task`1", "op|1", "Title | test", view);

        assertTrue(body.indexOf("`a|b.md`") < body.indexOf("`z.md`"));
        assertEquals(1, occurrences(body, "`z.md`"));
        assertTrue(body.contains("Summary \\| line"));
        assertTrue(body.contains("Title \\| test"));
        assertTrue(body.contains("z &#96;test&#96;"));
    }

    @Test
    void shouldKeepExplicitArtifactIdsAndFilterAllLoopbackLinks() {
        RequirementDeliveryPublicationView raw = assembler.assemble("""
                {"changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW",
                 "roleResults":{"QA_AGENT":{"status":"PASSED","summary":"ok","failureCategory":"NONE","retryRecommendation":"NONE",
                   "acceptanceResults":[{"criteria":"c","scope":"CURRENT","command":"cmd","status":"PASSED","exitCode":0,"durationMillis":1,
                     "logArtifactId":"qa-evidence/current.log","evidenceArtifactIds":["http://localhost/a","http://127.0.0.1/b","http://127.1/short","http://127.0.1/shorter","http://0177.0.0.1/octal","http://0x7f000001/hex","http://0x7f.0.0.1/hex-parts","http://0.0.0.0/unspecified","http://[::]/unspecified-v6","http://[::1]/c","http://[::ffff:127.0.0.1]/mapped","http://[0:0:0:0:0:ffff:7f00:1]/expanded","http://2130706433/decimal","https://evidence.example/d"]}],
                   "evidenceManifestArtifactId":"qa-evidence/manifest.json"}}}
                """);

        String body = renderer.render("task-1", "op-1", "title", approved(raw, "task-1"));

        assertTrue(body.contains("`qa-evidence/current.log`"));
        assertTrue(body.contains("`qa-evidence/manifest.json`"));
        assertTrue(body.contains("[https://evidence.example/d](https://evidence.example/d)"));
        assertFalse(body.contains("localhost"));
        assertFalse(body.contains("127.0.0.1"));
        assertFalse(body.contains("127.1"));
        assertFalse(body.contains("127.0.1"));
        assertFalse(body.contains("::1"));
        assertFalse(body.contains("::ffff"));
        assertFalse(body.contains("7f00:1"));
        assertFalse(body.contains("2130706433"));
        assertFalse(body.contains("0177.0.0.1"));
        assertFalse(body.contains("0x7f"));
        assertFalse(body.contains("0.0.0.0"));
        assertFalse(body.contains("unspecified-v6"));
    }

    @Test
    void shouldPreventNarrativeMarkdownAndLoopbackInjection() {
        RequirementDeliveryPublicationView raw = assembler.assemble("""
                {"summary":"Summary\\n## Injected HTTP://localhost/upper http://127.1/short http://0x7f000001/hex http://0.0.0.0/runtime","prBody":"[local](http://localhost/a)\\n## Evil",
                 "changedFiles":["src/App.java"],"testCommands":["./mvnw test"],
                 "testStatus":"PASSED","riskLevel":"LOW"}
                """);

        String body = renderer.render(
                "task-1", "op-1", "Title\\n## TitleInjected", approved(raw, "task-1"));

        assertEquals("Summary,Changes,Verification,Acceptance,Delivery Review,Evidence,RD-Bot Provenance",
                headings(body));
        assertFalse(body.contains("localhost"));
        assertFalse(body.contains("HTTP://"));
        assertFalse(body.contains("127.1"));
        assertFalse(body.contains("0.0.0.0"));
        assertFalse(body.contains("0x7f000001"));
        assertFalse(body.contains("## Injected"));
        assertFalse(body.contains("## Evil"));
        assertFalse(body.contains("## TitleInjected"));
        assertTrue(body.contains("loopback URL omitted"));
    }

    @Test
    void shouldRejectUnapprovedView() {
        RequirementDeliveryPublicationView view = assembler.assemble(rootCodingJson());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> renderer.render("task-1", "op-1", "title", view));
        assertTrue(error.getMessage().contains("approved"));
    }

    @Test
    void shouldRejectApprovalBoundToDifferentTaskOrFacts() {
        RequirementDeliveryPublicationView raw = assembler.assemble(rootCodingJson());
        RequirementDeliveryPublicationView wrongTask = raw.withDeliveryReview(new DeliveryReview(
                "task-other", "DELIVERY_REVIEWER", true, "", raw.publicationFactsHash()));
        RequirementDeliveryPublicationView wrongFacts = raw.withDeliveryReview(new DeliveryReview(
                "task-1", "DELIVERY_REVIEWER", true, "", "sha256:stale"));

        IllegalArgumentException taskError = assertThrows(IllegalArgumentException.class,
                () -> renderer.render("task-1", "op-1", "title", wrongTask));
        IllegalArgumentException factsError = assertThrows(IllegalArgumentException.class,
                () -> renderer.render("task-1", "op-1", "title", wrongFacts));

        assertTrue(taskError.getMessage().contains("taskId"));
        assertTrue(factsError.getMessage().contains("factsHash"));
    }

    private static RequirementDeliveryPublicationView approved(
            RequirementDeliveryPublicationView view,
            String taskId
    ) {
        return view.withDeliveryReview(new DeliveryReview(
                taskId, "DELIVERY_REVIEWER", true, "", view.publicationFactsHash()));
    }

    private static String headings(String body) {
        return body.lines().filter(line -> line.startsWith("## "))
                .map(line -> line.substring(3)).reduce((left, right) -> left + "," + right).orElse("");
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    private static String rootCodingJson() {
        return "{\"changedFiles\":[\"src/App.java\"],\"testCommands\":[\"./mvnw test\"],\"testStatus\":\"PASSED\",\"riskLevel\":\"LOW\"}";
    }

    private static String fixture() throws IOException {
        return resource("/requirement-publication/pr-28-production-result.json");
    }

    private static String expectedFixture() throws IOException {
        return resource("/requirement-publication/pr-28-expected.md").strip();
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = RequirementPullRequestBodyRendererTest.class
                .getResourceAsStream(path)) {
            if (input == null) throw new IOException("fixture missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
