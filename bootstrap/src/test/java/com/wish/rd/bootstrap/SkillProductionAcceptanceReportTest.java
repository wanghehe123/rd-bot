package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillProductionAcceptanceReportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path reportRoot;

    @Test
    void shouldWriteSkippedReportWithMissingSkillSmokeRequirements() throws Exception {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());

        Path reportPath = report.writeSkipped(List.of(
                "rd.skill.smoke.skill-source-uri",
                "rd.skill.smoke.install-root"
        ));

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("# RD-Bot Skill 安装生产验收报告"));
        assertTrue(markdown.contains("结论：SKIPPED"));
        assertTrue(markdown.contains("rd.skill.smoke.skill-source-uri"));
        assertTrue(markdown.contains("rd.skill.smoke.install-root"));
        assertTrue(markdown.contains("-Drd.skill.smoke.allowed-role=<allowed-role>"));
        assertTrue(markdown.contains("-Drd.skill.smoke.rejected-role=<rejected-role>"));
        assertTrue(markdown.contains("-Drd.skill.smoke.high-risk-role=<high-risk-role>"));
        assertTrue(markdown.contains("SkillPolicyRealSmokeTest"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
    }

    @Test
    void shouldWritePassedReportForRealSkillPolicySmoke() throws Exception {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence =
                new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                        "task-skill-smoke",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1,
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of("secret-value")
                );

        Path reportPath = report.writePassed(evidence);
        Path jsonPath = reportPath.resolveSibling(reportPath.getFileName().toString().replace(".md", ".json"));

        String markdown = Files.readString(reportPath);
        JsonNode json = OBJECT_MAPPER.readTree(jsonPath.toFile());
        assertTrue(markdown.contains("结论：PASSED_SKILL_POLICY_SMOKE"));
        assertTrue(markdown.contains("taskId：task-skill-smoke"));
        assertTrue(markdown.contains("stageRunId：stage-skill-smoke"));
        assertTrue(markdown.contains("skillId：qa-real-runner"));
        assertTrue(markdown.contains("skillVersion：v1"));
        assertTrue(markdown.contains("allowedRole：QA_AGENT"));
        assertTrue(markdown.contains("rejectedRole：REQUIREMENT_REVIEWER"));
        assertTrue(markdown.contains("highRiskRole：CODING_AGENT"));
        assertTrue(markdown.contains("installed：true"));
        assertTrue(markdown.contains("unauthorizedRejected：true"));
        assertTrue(markdown.contains("highRiskWaitingApproval：true"));
        assertTrue(markdown.contains("metadataValidated：true"));
        assertTrue(markdown.contains("installerCallCount：1"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | PASSED | taskId=task-skill-smoke"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | NOT_RUN |"));
        assertTrue(markdown.contains("Skill 策略 smoke 只能作为 #11 专项证据"));
        assertFalse(markdown.contains("secret-value"));
        assertTrue(Files.isRegularFile(jsonPath));
        assertTrue(json.path("skillPolicyEvidenceValidated").asBoolean(false));
        assertTrue(json.path("installed").asBoolean(false));
        assertTrue(json.path("unauthorizedRejected").asBoolean(false));
        assertTrue(json.path("highRiskWaitingApproval").asBoolean(false));
        assertTrue(json.path("metadataValidated").asBoolean(false));
        assertTrue(json.path("installerCallCount").asInt(0) == 1);
        assertTrue(json.path("rdBotVersion").asText("").equals("0.1.0-smoke"));
        assertTrue(json.path("environmentId").asText("").equals("prod-equivalent-a"));
        assertTrue(json.path("executedBy").asText("").equals("qa-runner"));
        assertTrue(json.path("skillPolicyTaskId").asText("").equals("task-skill-smoke"));
    }

    @Test
    void shouldWriteFailedReportWithoutFailingUncoveredAcceptancePoints() throws Exception {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence =
                new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                        "task-skill-smoke",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:abc123",
                        false,
                        false,
                        false,
                        false,
                        0,
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of("secret-value")
                );

        Path reportPath = report.writeFailed(evidence, new AssertionError("leaked secret-value"));

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("结论：FAILED_SKILL_POLICY_SMOKE"));
        assertTrue(markdown.contains("[REDACTED]"));
        assertTrue(markdown.contains("| 1 | 需求任务可进入多 Agent 工作流 | NOT_RUN |"));
        assertTrue(markdown.contains("| 10 | 错误通知真实送达 Feishu | NOT_RUN |"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | FAILED |"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | FAILED |"));
        assertFalse(markdown.contains("secret-value"));
    }

    @Test
    void shouldRejectPassedReportWithoutRoleBoundaries() {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence =
                new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                        "task-skill-smoke",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:abc123",
                        true,
                        true,
                        true,
                        true,
                        1,
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of()
                );

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("allowedRole"));
    }

    @Test
    void shouldRejectPassedReportWhenRoleBoundariesAreNotDistinct() {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence =
                new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                        "task-skill-smoke",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "QA_AGENT",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1,
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of()
                );

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("role boundaries"));
    }

    @Test
    void shouldRejectPassedReportWhenSourceChecksumIsNotFullSha256() {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence =
                new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                        "task-skill-smoke",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:abc123",
                        true,
                        true,
                        true,
                        true,
                        1,
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of()
                );

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("sourceChecksum"));
    }

    @Test
    void shouldRejectPassedReportWhenInstallPathDoesNotMatchSkillIdentity() {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence =
                new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                        "task-skill-smoke",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/other-skill/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1,
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of()
                );

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("installPath"));
    }

    @Test
    void shouldRejectPassedReportWhenInstallPathIsRelative() {
        SkillProductionAcceptanceReport report = new SkillProductionAcceptanceReport(reportRoot, fixedClock());
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence =
                new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                        "task-skill-smoke",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1,
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        List.of()
                );

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassed(evidence)
        );

        assertTrue(failure.getMessage().contains("installPath"));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-01T08:00:00Z"), ZoneOffset.UTC);
    }
}
