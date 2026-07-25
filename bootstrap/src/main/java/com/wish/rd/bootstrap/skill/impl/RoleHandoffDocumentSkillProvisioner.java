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

/** Provisions the governed Markdown handoff skill used by the non-QA delivery roles. */
public final class RoleHandoffDocumentSkillProvisioner {

    public static final String SKILL_ID = "role-handoff-document";
    public static final String VERSION = "1.0.0";
    private static final String RESOURCE = "skills/role-handoff-document/SKILL.md";
    private static final List<String> ALLOWED_ROLES = List.of(
            "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT"
    );

    private final Path root;

    public RoleHandoffDocumentSkillProvisioner(Path root) {
        this.root = (root == null ? Path.of("/tmp/rd-bot/role-handoff-skills") : root)
                .toAbsolutePath()
                .normalize();
    }

    /** Extracts, policy-checks, and installs one immutable snapshot shared by the supported non-QA roles. */
    public synchronized Provision provision() {
        try {
            Path source = extractSource();
            String checksum = LocalFileSystemSkillInstaller.sha256(source);
            AgentSkillDescriptor descriptor = new AgentSkillDescriptor(
                    SKILL_ID,
                    VERSION,
                    source.toUri().toString(),
                    checksum,
                    ALLOWED_ROLES,
                    SkillRiskLevel.LOW,
                    "read-only Docker bind mount",
                    "Bounded private Markdown handoff for direct downstream delivery roles"
            );
            SkillInstallationEngine engine = new SkillInstallationEngine(
                    registry(descriptor),
                    new RoleAllowlistSkillPolicyGate(true),
                    new LocalFileSystemSkillInstaller(root.resolve("installed"))
            );
            // The descriptor is explicitly allowlisted for all three mounted roles. One installation is immutable
            // and shared; the executor separately limits the mount to those exact roles.
            SkillInstallResult result = engine.install(new SkillInstallCommand(
                    "bootstrap",
                    "role-handoff-skill-provision",
                    "SOLUTION_ARCHITECT",
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
            throw new IllegalArgumentException("role handoff skill source escapes provision root");
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

    /** Auditable provision result used to configure the read-only Docker bind mount. */
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
