package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.skill.impl.LocalFileSystemSkillInstaller;
import com.wish.rd.skill.model.AgentSkillDescriptor;
import com.wish.rd.skill.impl.RoleAllowlistSkillPolicyGate;
import com.wish.rd.skill.model.SkillInstallCommand;
import com.wish.rd.skill.model.SkillInstallResult;
import com.wish.rd.skill.SkillInstallationEngine;
import com.wish.rd.skill.SkillInstallerPort;
import com.wish.rd.skill.SkillRegistryPort;
import com.wish.rd.skill.model.SkillRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill policy production smoke. Disabled by default and writes to a real configured install root when enabled.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=SkillPolicyRealSmokeTest \
 *   -Drd.integration.skill-policy.enabled=true \
 *   -Drd.skill.smoke.production-evidence=true \
 *   -Drd.skill.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.skill.smoke.environment-id=prod-equivalent-a \
 *   -Drd.skill.smoke.executed-by=qa-runner \
 *   -Drd.skill.smoke.skill-id=qa-real-runner \
 *   -Drd.skill.smoke.skill-version=v1 \
 *   -Drd.skill.smoke.skill-source-uri=file:///opt/rd-bot/skills-src/qa-real-runner \
 *   -Drd.skill.smoke.skill-checksum=sha256:<checksum> \
 *   -Drd.skill.smoke.install-root=/opt/rd-bot/skills-installed \
 *   -Drd.skill.smoke.allowed-role=QA_AGENT \
 *   -Drd.skill.smoke.rejected-role=REQUIREMENT_REVIEWER \
 *   -Drd.skill.smoke.high-risk-role=CODING_AGENT \
 *   -Drd.skill.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.skill-policy.enabled", matches = "true")
class SkillPolicyRealSmokeTest {

    @Test
    void installsAllowedSkillAndRejectsUnauthorizedOrHighRiskSkill() throws Exception {
        SkillProductionAcceptanceReport report = SkillProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = SkillProductionAcceptanceProfile.systemProperties();
        List<String> missing = SkillProductionAcceptanceProfile.missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("skill-policy", missing, skippedReport);
        }
        SkillProductionAcceptanceProfile profile = SkillProductionAcceptanceProfile.from(properties);
        String taskId = profile.taskId().isBlank()
                ? "skill-policy-smoke-" + Instant.now().toEpochMilli()
                : profile.taskId();
        String stageRunId = taskId + "-stage";
        SkillProductionAcceptanceReport.SkillSmokeEvidence evidence = evidence(
                profile,
                taskId,
                stageRunId,
                "",
                false,
                false,
                false,
                false,
                0
        );
        try {
            Path source = Path.of(URI.create(profile.skillSourceUri())).toAbsolutePath().normalize();
            assertTrue(Files.exists(source), "real skill source must exist: " + source);
            assertEquals(profile.skillChecksum(), LocalFileSystemSkillInstaller.sha256(source),
                    "real skill source checksum must match the provided production property");

            AtomicInteger installerCallCount = new AtomicInteger();
            LocalFileSystemSkillInstaller realInstaller =
                    new LocalFileSystemSkillInstaller(Path.of(profile.installRoot()));
            SkillInstallerPort countingInstaller = (command, descriptor) -> {
                installerCallCount.incrementAndGet();
                return realInstaller.install(command, descriptor);
            };
            SkillInstallationEngine engine = new SkillInstallationEngine(
                    registry(profile),
                    new RoleAllowlistSkillPolicyGate(true),
                    countingInstaller
            );

            SkillInstallResult installed = engine.install(new SkillInstallCommand(
                    taskId,
                    stageRunId,
                    profile.allowedRole(),
                    profile.skillId(),
                    profile.skillVersion()
            ));
            assertTrue(installed.installed(), "allowed low-risk skill must be installed: " + installed.message());
            assertTrue(Files.exists(Path.of(installed.installPath())),
                    "installed skill path must exist: " + installed.installPath());

            SkillInstallResult unauthorized = engine.install(new SkillInstallCommand(
                    taskId,
                    stageRunId,
                    profile.rejectedRole(),
                    profile.skillId(),
                    profile.skillVersion()
            ));
            assertFalse(unauthorized.installed(), "unauthorized role must not install the skill");
            assertTrue(unauthorized.message().contains("skill is not allowed for role"));

            SkillInstallResult highRisk = engine.install(new SkillInstallCommand(
                    taskId,
                    stageRunId,
                    profile.highRiskRole(),
                    profile.highRiskSkillId(),
                    profile.skillVersion()
            ));
            assertFalse(highRisk.installed(), "high-risk skill must wait for approval before installation");
            assertTrue(highRisk.message().contains("high risk skill requires manual approval"));
            assertFalse(Files.exists(Path.of(profile.installRoot())
                            .toAbsolutePath()
                            .normalize()
                            .resolve(profile.highRiskSkillId())
                            .resolve(profile.skillVersion())),
                    "high-risk skill must not be copied before manual approval");
            assertEquals(1, installerCallCount.get(),
                    "only the allowed low-risk install should reach the real installer");

            evidence = evidence(
                    profile,
                    taskId,
                    stageRunId,
                    installed.installPath(),
                    installed.installed(),
                    !unauthorized.installed(),
                    !highRisk.installed(),
                    installed.policyJson().contains("\"action\":\"ALLOWED\""),
                    installerCallCount.get()
            );
            assertNoSecretNeedles(profile, List.of(
                    installed.message(),
                    installed.installPath(),
                    installed.policyJson(),
                    unauthorized.message(),
                    unauthorized.policyJson(),
                    highRisk.message(),
                    highRisk.policyJson()
            ));
            Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] skill-policy taskId=" + taskId
                    + " installPath=" + installed.installPath()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] skill-policy failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    private static SkillRegistryPort registry(SkillProductionAcceptanceProfile profile) {
        AgentSkillDescriptor lowRisk = new AgentSkillDescriptor(
                profile.skillId(),
                profile.skillVersion(),
                profile.skillSourceUri(),
                profile.skillChecksum(),
                List.of(profile.allowedRole()),
                SkillRiskLevel.LOW,
                "local-copy",
                "生产 smoke 低风险 Skill"
        );
        AgentSkillDescriptor highRisk = new AgentSkillDescriptor(
                profile.highRiskSkillId(),
                profile.skillVersion(),
                profile.skillSourceUri(),
                profile.skillChecksum(),
                List.of(profile.highRiskRole()),
                SkillRiskLevel.HIGH,
                "local-copy",
                "生产 smoke 高风险 Skill"
        );
        Map<String, AgentSkillDescriptor> skills = Map.of(
                lowRisk.skillId(), lowRisk,
                highRisk.skillId(), highRisk
        );
        return new SkillRegistryPort() {

            @Override
            public List<AgentSkillDescriptor> listAvailable() {
                return List.copyOf(skills.values());
            }

            @Override
            public Optional<AgentSkillDescriptor> findById(String skillId) {
                return Optional.ofNullable(skills.get(skillId));
            }
        };
    }

    private static SkillProductionAcceptanceReport.SkillSmokeEvidence evidence(
            SkillProductionAcceptanceProfile profile,
            String taskId,
            String stageRunId,
            String installPath,
            boolean installed,
            boolean unauthorizedRejected,
            boolean highRiskWaitingApproval,
            boolean metadataValidated,
            int installerCallCount
    ) {
        return new SkillProductionAcceptanceReport.SkillSmokeEvidence(
                taskId,
                stageRunId,
                profile.skillId(),
                profile.skillVersion(),
                profile.allowedRole(),
                profile.rejectedRole(),
                profile.highRiskRole(),
                installPath,
                profile.skillChecksum(),
                installed,
                unauthorizedRejected,
                highRiskWaitingApproval,
                metadataValidated,
                installerCallCount,
                profile.rdBotVersion(),
                profile.environmentId(),
                profile.executedBy(),
                profile.secretScanNeedles()
        );
    }

    private static void assertNoSecretNeedles(
            SkillProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            if (needle == null || needle.isBlank()) {
                continue;
            }
            for (String value : values) {
                assertFalse(value != null && value.contains(needle),
                        "skill smoke evidence must not contain configured secret needle");
            }
        }
    }
}
