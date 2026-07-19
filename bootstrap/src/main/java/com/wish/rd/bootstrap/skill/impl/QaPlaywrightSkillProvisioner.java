package com.wish.rd.bootstrap.skill.impl;

import com.wish.rd.skill.SkillInstallationEngine;
import com.wish.rd.skill.SkillRegistryPort;
import com.wish.rd.skill.impl.RoleAllowlistSkillPolicyGate;
import com.wish.rd.skill.model.AgentSkillDescriptor;
import com.wish.rd.skill.model.SkillInstallCommand;
import com.wish.rd.skill.model.SkillInstallResult;
import com.wish.rd.skill.model.SkillRiskLevel;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the bundled QA skill, runs it through the skill registry and policy gate, and installs a verified snapshot.
 */
public final class QaPlaywrightSkillProvisioner {

    public static final String SKILL_ID = "qa-playwright-cli";
    public static final String VERSION = "1.0.2";
    private static final String RESOURCE = "skills/qa-playwright-cli/SKILL.md";

    private final Path root;

    public QaPlaywrightSkillProvisioner(Path root) {
        this.root = (root == null ? Path.of("/tmp/rd-bot/qa-skills") : root)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Provisions the immutable skill snapshot used by QA containers.
     */
    public synchronized Provision provision() {
        try {
            Path source = extractSource();
            String checksum = LocalFileSystemSkillInstaller.sha256(source);
            AgentSkillDescriptor descriptor = new AgentSkillDescriptor(
                    SKILL_ID,
                    VERSION,
                    source.toUri().toString(),
                    checksum,
                    List.of("QA_AGENT"),
                    SkillRiskLevel.LOW,
                    "read-only Docker bind mount",
                    "Real command, HTTP, Playwright CLI and regression QA evidence workflow"
            );
            SkillRegistryPort registry = registry(descriptor);
            SkillInstallationEngine engine = new SkillInstallationEngine(
                    registry,
                    new RoleAllowlistSkillPolicyGate(true),
                    new LocalFileSystemSkillInstaller(root.resolve("installed"))
            );
            SkillInstallResult result = engine.install(new SkillInstallCommand(
                    "bootstrap",
                    "qa-skill-provision",
                    "QA_AGENT",
                    SKILL_ID,
                    VERSION
            ));
            return new Provision(
                    result.installed(),
                    result.skillId(),
                    result.version(),
                    checksum,
                    result.installPath(),
                    result.policyJson(),
                    result.message()
            );
        } catch (IOException | IllegalArgumentException exception) {
            return new Provision(false, SKILL_ID, VERSION, "", "", "{}", exception.getMessage());
        }
    }

    private Path extractSource() throws IOException {
        Path source = root.resolve("sources").resolve(SKILL_ID).resolve(VERSION).normalize();
        if (!source.startsWith(root)) {
            throw new IllegalArgumentException("QA skill source escapes provision root");
        }
        Files.createDirectories(source);
        Path skillFile = source.resolve("SKILL.md");
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (var input = resource.getInputStream()) {
            Files.copy(input, skillFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return source;
    }

    private static SkillRegistryPort registry(AgentSkillDescriptor descriptor) {
        return new SkillRegistryPort() {
            @Override
            public List<AgentSkillDescriptor> listAvailable() {
                return List.of(descriptor);
            }

            @Override
            public Optional<AgentSkillDescriptor> findById(String skillId) {
                return SKILL_ID.equals(skillId) ? Optional.of(descriptor) : Optional.empty();
            }
        };
    }

    /**
     * Auditable result used to configure the read-only Docker skill mount.
     */
    public record Provision(
            boolean installed,
            String skillId,
            String version,
            String checksum,
            String installPath,
            String policyJson,
            String message
    ) {
    }
}
