package com.wish.rd.skill;

/**
 * Skill 安装编排服务。
 *
 * <p>供多 Agent 编排在进入执行阶段前准备角色 Skill。该服务只依赖注册表、
 * 策略门禁和安装端口，不直接执行 shell、下载外部资源或写入具体插件目录。
 */
public final class SkillInstallationEngine {

    private final SkillRegistryPort registry;
    private final SkillPolicyGate policyGate;
    private final SkillInstallerPort installer;

    /**
     * 创建 Skill 安装编排服务。
     *
     * @param registry   Skill 注册表端口
     * @param policyGate Skill 策略门禁
     * @param installer  Skill 安装端口
     */
    public SkillInstallationEngine(
            SkillRegistryPort registry,
            SkillPolicyGate policyGate,
            SkillInstallerPort installer
    ) {
        this.registry = registry;
        this.policyGate = policyGate;
        this.installer = installer;
    }

    /**
     * 按注册表和策略门禁准备 Skill。
     *
     * @param command 安装命令
     * @return 安装结果；未通过策略时不会调用安装端口
     */
    public SkillInstallResult install(SkillInstallCommand command) {
        SkillInstallCommand safeCommand = command == null
                ? new SkillInstallCommand("", "", "", "", "")
                : command;
        AgentSkillDescriptor descriptor = registry == null
                ? null
                : registry.findById(safeCommand.skillId()).orElse(null);
        if (descriptor == null) {
            SkillPolicyDecision decision = new SkillPolicyDecision(
                    false,
                    "REJECTED",
                    SkillRiskLevel.HIGH,
                    "skill descriptor missing: " + safeCommand.skillId()
            );
            return rejected(safeCommand, safeCommand.version(), decision);
        }
        SkillPolicyDecision metadataDecision = validateMetadata(descriptor);
        if (!metadataDecision.allowed()) {
            return rejected(safeCommand, descriptor.version(), metadataDecision);
        }
        if (!safeCommand.version().isBlank() && !safeCommand.version().equals(descriptor.version())) {
            SkillPolicyDecision decision = new SkillPolicyDecision(
                    false,
                    "REJECTED",
                    descriptor.riskLevel(),
                    "skill version mismatch: requested=" + safeCommand.version()
                            + ", available=" + descriptor.version()
            );
            return rejected(safeCommand, descriptor.version(), decision);
        }
        SkillPolicyDecision decision = policyGate == null
                ? new SkillPolicyDecision(false, "REJECTED", descriptor.riskLevel(), "skill policy gate missing")
                : policyGate.decide(descriptor, safeCommand.role());
        if (!decision.allowed()) {
            return rejected(safeCommand, descriptor.version(), decision);
        }
        if (installer == null) {
            SkillPolicyDecision rejected = new SkillPolicyDecision(
                    false,
                    "REJECTED",
                    descriptor.riskLevel(),
                    "skill installer missing"
            );
            return rejected(safeCommand, descriptor.version(), rejected);
        }
        SkillInstallResult result = installer.install(safeCommand, descriptor);
        if (result == null) {
            return new SkillInstallResult(
                    false,
                    descriptor.skillId(),
                    descriptor.version(),
                    "",
                    "skill installer returned empty result",
                    toJson(decision)
            );
        }
        return new SkillInstallResult(
                result.installed(),
                result.skillId().isBlank() ? descriptor.skillId() : result.skillId(),
                result.version().isBlank() ? descriptor.version() : result.version(),
                result.installPath(),
                result.message(),
                toJson(decision)
        );
    }

    private SkillInstallResult rejected(
            SkillInstallCommand command,
            String version,
            SkillPolicyDecision decision
    ) {
        return new SkillInstallResult(
                false,
                command.skillId(),
                version,
                "",
                decision.reason(),
                toJson(decision)
        );
    }

    private SkillPolicyDecision validateMetadata(AgentSkillDescriptor descriptor) {
        if (descriptor.version().isBlank()) {
            return metadataRejected(descriptor, "version");
        }
        if (descriptor.sourceUri().isBlank()) {
            return metadataRejected(descriptor, "sourceUri");
        }
        if (descriptor.checksum().isBlank()) {
            return metadataRejected(descriptor, "checksum");
        }
        if (descriptor.allowedRoles().isEmpty()) {
            return metadataRejected(descriptor, "allowedRoles");
        }
        if (descriptor.riskLevel() == SkillRiskLevel.UNKNOWN) {
            return metadataRejected(descriptor, "riskLevel");
        }
        return new SkillPolicyDecision(true, "ALLOWED", descriptor.riskLevel(), "skill metadata complete");
    }

    private SkillPolicyDecision metadataRejected(AgentSkillDescriptor descriptor, String fieldName) {
        return new SkillPolicyDecision(
                false,
                "REJECTED",
                descriptor.riskLevel(),
                "skill metadata incomplete: " + fieldName
        );
    }

    private String toJson(SkillPolicyDecision decision) {
        SkillPolicyDecision safeDecision = decision == null
                ? new SkillPolicyDecision(false, "REJECTED", SkillRiskLevel.HIGH, "missing decision")
                : decision;
        return """
                {"allowed":%s,"action":"%s","riskLevel":"%s","reason":"%s"}
                """.formatted(
                safeDecision.allowed(),
                json(safeDecision.action()),
                safeDecision.riskLevel().name(),
                json(safeDecision.reason())
        ).strip();
    }

    private String json(String value) {
        String safeValue = value == null ? "" : value;
        return safeValue
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
