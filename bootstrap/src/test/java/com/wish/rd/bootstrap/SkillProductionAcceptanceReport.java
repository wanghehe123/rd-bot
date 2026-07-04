package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Markdown evidence for the property-gated Skill policy production smoke.
 */
final class SkillProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final List<AcceptancePoint> ACCEPTANCE_POINTS = List.of(
            new AcceptancePoint(1, "需求任务可进入多 Agent 工作流"),
            new AcceptancePoint(2, "角色上下文包真实落库且内容不同"),
            new AcceptancePoint(3, "需求评审 Agent 能阻断不可交付需求"),
            new AcceptancePoint(4, "方案 Agent 产出可执行开发方案"),
            new AcceptancePoint(5, "多 provider 降级重试真实生效"),
            new AcceptancePoint(6, "编码 Agent 在 Docker 中真实改代码并运行测试"),
            new AcceptancePoint(7, "QA Agent 逐条验收并阻断失败交付"),
            new AcceptancePoint(8, "交付复核通过后才提交为已交付"),
            new AcceptancePoint(9, "状态机可恢复且不会重复派发"),
            new AcceptancePoint(10, "错误通知真实送达 Feishu"),
            new AcceptancePoint(11, "Skill 安装和使用受策略控制"),
            new AcceptancePoint(12, "经验自动沉淀且可被后续 RAG 检索"),
            new AcceptancePoint(13, "密钥和敏感信息不进入产物"),
            new AcceptancePoint(14, "指标和审计可观测"),
            new AcceptancePoint(15, "生产真实测试结论要求")
    );

    private final Path reportRoot;
    private final Clock clock;

    SkillProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static SkillProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.skill.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new SkillProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 Skill 策略 smoke 参数不完整，本次没有执行真实 Skill 安装目录写入。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 Skill 策略前置条件"));
        return write(markdown);
    }

    Path writePassed(SkillSmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_SKILL_POLICY_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writeJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(SkillSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_SKILL_POLICY_SMOKE", evidence.secretNeedles());
        markdown.append("\n## 失败信息\n\n");
        markdown.append("- errorType：").append(failure == null ? "" : failure.getClass().getSimpleName()).append('\n');
        markdown.append("- message：").append(redact(
                failure == null ? "" : failure.getMessage(),
                evidence.secretNeedles()
        )).append('\n');
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, failedMatrix());
        return write(markdown);
    }

    private StringBuilder header(String conclusion, List<String> secretNeedles) {
        return new StringBuilder()
                .append("# RD-Bot Skill 安装生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 Skill 策略和真实文件系统安装 smoke 覆盖的证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=SkillPolicyRealSmokeTest \\
                  -Drd.integration.skill-policy.enabled=true \\
                  -Drd.skill.smoke.production-evidence=true \\
                  -Drd.skill.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.skill.smoke.environment-id=<production-environment-id> \\
                  -Drd.skill.smoke.executed-by=<operator> \\
                  -Drd.skill.smoke.skill-id=<skill-id> \\
                  -Drd.skill.smoke.skill-version=<skill-version> \\
                  -Drd.skill.smoke.skill-source-uri=file:///opt/rd-bot/skills-src/<skill-id> \\
                  -Drd.skill.smoke.skill-checksum=sha256:<checksum> \\
                  -Drd.skill.smoke.install-root=/opt/rd-bot/skills-installed \\
                  -Drd.skill.smoke.allowed-role=<allowed-role> \\
                  -Drd.skill.smoke.rejected-role=<rejected-role> \\
                  -Drd.skill.smoke.high-risk-role=<high-risk-role> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, SkillSmokeEvidence evidence) {
        markdown.append("\n## Skill 策略 Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- stageRunId：").append(oneLine(evidence.stageRunId())).append('\n');
        markdown.append("- skillId：").append(oneLine(evidence.skillId())).append('\n');
        markdown.append("- skillVersion：").append(oneLine(evidence.skillVersion())).append('\n');
        markdown.append("- allowedRole：").append(oneLine(evidence.allowedRole())).append('\n');
        markdown.append("- rejectedRole：").append(oneLine(evidence.rejectedRole())).append('\n');
        markdown.append("- highRiskRole：").append(oneLine(evidence.highRiskRole())).append('\n');
        markdown.append("- installPath：").append(oneLine(evidence.installPath())).append('\n');
        markdown.append("- sourceChecksum：").append(oneLine(evidence.sourceChecksum())).append('\n');
        markdown.append("- installed：").append(evidence.installed()).append('\n');
        markdown.append("- unauthorizedRejected：").append(evidence.unauthorizedRejected()).append('\n');
        markdown.append("- highRiskWaitingApproval：").append(evidence.highRiskWaitingApproval()).append('\n');
        markdown.append("- metadataValidated：").append(evidence.metadataValidated()).append('\n');
        markdown.append("- installerCallCount：").append(evidence.installerCallCount()).append('\n');
    }

    private void requirePassedEvidence(SkillSmokeEvidence evidence) {
        requireEvidenceText(evidence.taskId(), "taskId");
        requireEvidenceText(evidence.stageRunId(), "stageRunId");
        requireEvidenceText(evidence.skillId(), "skillId");
        requireEvidenceText(evidence.skillVersion(), "skillVersion");
        requireEvidenceText(evidence.allowedRole(), "allowedRole");
        requireEvidenceText(evidence.rejectedRole(), "rejectedRole");
        requireEvidenceText(evidence.highRiskRole(), "highRiskRole");
        if (!roleBoundariesAreDistinct(evidence)) {
            throw new IllegalArgumentException("role boundaries must be distinct for PASSED report");
        }
        requireEvidenceText(evidence.installPath(), "installPath");
        if (!installPathMatchesSkillIdentity(evidence.installPath(), evidence.skillId(), evidence.skillVersion())) {
            throw new IllegalArgumentException(
                    "installPath must be absolute and end with skillId/skillVersion for PASSED report"
            );
        }
        requireEvidenceText(evidence.sourceChecksum(), "sourceChecksum");
        if (!sourceChecksumIsFullSha256(evidence.sourceChecksum())) {
            throw new IllegalArgumentException("sourceChecksum must be sha256 plus 64 hex chars for PASSED report");
        }
        if (!evidence.installed()) {
            throw new IllegalArgumentException("installed must be true for PASSED report");
        }
        if (!evidence.unauthorizedRejected()) {
            throw new IllegalArgumentException("unauthorizedRejected must be true for PASSED report");
        }
        if (!evidence.highRiskWaitingApproval()) {
            throw new IllegalArgumentException("highRiskWaitingApproval must be true for PASSED report");
        }
        if (!evidence.metadataValidated()) {
            throw new IllegalArgumentException("metadataValidated must be true for PASSED report");
        }
        if (evidence.installerCallCount() != 1) {
            throw new IllegalArgumentException("installerCallCount must be 1 for PASSED report");
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(SkillSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("Skill 策略 smoke 未覆盖该验收点，需要生产专项演练。");
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", skillId=" + oneLine(evidence.skillId())
                + ", installPath=" + oneLine(evidence.installPath());
        statuses.put(11, new MatrixStatus(
                "PASSED",
                evidenceRef + "；真实文件系统安装已校验 checksum，未授权角色被拒绝，高风险 Skill 进入等待审批且未触发安装。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "Skill 策略 smoke 只能作为 #11 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("Skill 策略 smoke 未覆盖该验收点，需要生产专项演练。");
        statuses.put(11, new MatrixStatus(
                "FAILED",
                "Skill 策略 smoke 失败，真实安装、角色拒绝、高风险审批或元数据校验没有全部通过。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "Skill 策略 smoke 失败，#11 未通过；#15 不能标记为通过。"
        ));
        return statuses;
    }

    private void appendMatrix(StringBuilder markdown, Map<Integer, MatrixStatus> statuses) {
        markdown.append("| # | 验收点 | 结论 | 证据/缺口 |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            MatrixStatus status = statuses.get(point.number());
            markdown.append("| ").append(point.number())
                    .append(" | ").append(point.title())
                    .append(" | ").append(status.status())
                    .append(" | ").append(oneLine(status.evidence()))
                    .append(" |\n");
        }
    }

    private Path write(StringBuilder markdown) throws IOException {
        Files.createDirectories(reportRoot);
        Path reportPath = reportRoot.resolve("skill-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(Path markdownReportPath, SkillSmokeEvidence evidence) throws IOException {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("skillPolicyEvidenceValidated", true);
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("skillPolicyTaskId", evidence.taskId());
        json.put("taskId", evidence.taskId());
        json.put("stageRunId", evidence.stageRunId());
        json.put("skillId", evidence.skillId());
        json.put("skillVersion", evidence.skillVersion());
        json.put("allowedRole", evidence.allowedRole());
        json.put("rejectedRole", evidence.rejectedRole());
        json.put("highRiskRole", evidence.highRiskRole());
        json.put("installPath", evidence.installPath());
        json.put("sourceChecksum", evidence.sourceChecksum());
        json.put("installed", evidence.installed());
        json.put("unauthorizedRejected", evidence.unauthorizedRejected());
        json.put("highRiskWaitingApproval", evidence.highRiskWaitingApproval());
        json.put("metadataValidated", evidence.metadataValidated());
        json.put("installerCallCount", evidence.installerCallCount());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString();
        return markdownReportPath.resolveSibling(fileName.replace(".md", ".json"));
    }

    private static void requireEvidenceText(String value, String fieldName) {
        if (oneLine(value).isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank for PASSED report");
        }
    }

    private static boolean sourceChecksumIsFullSha256(String sourceChecksum) {
        return oneLine(sourceChecksum).matches("sha256:[0-9a-fA-F]{64}");
    }

    private static boolean roleBoundariesAreDistinct(SkillSmokeEvidence evidence) {
        String allowedRole = oneLine(evidence.allowedRole());
        String rejectedRole = oneLine(evidence.rejectedRole());
        String highRiskRole = oneLine(evidence.highRiskRole());
        return !allowedRole.equals(rejectedRole)
                && !allowedRole.equals(highRiskRole)
                && !rejectedRole.equals(highRiskRole);
    }

    private static boolean installPathMatchesSkillIdentity(String installPath, String skillId, String skillVersion) {
        String safeInstallPath = oneLine(installPath);
        String safeSkillId = oneLine(skillId);
        String safeSkillVersion = oneLine(skillVersion);
        if (safeInstallPath.isBlank() || safeSkillId.isBlank() || safeSkillVersion.isBlank()) {
            return false;
        }
        try {
            Path normalizedInstallPath = Path.of(safeInstallPath).normalize();
            return normalizedInstallPath.isAbsolute()
                    && normalizedInstallPath.endsWith(Path.of(safeSkillId, safeSkillVersion));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static String redact(String value, List<String> secretNeedles) {
        String redacted = oneLine(value);
        if (secretNeedles == null) {
            return redacted;
        }
        for (String needle : secretNeedles) {
            String normalized = oneLine(needle);
            if (!normalized.isBlank()) {
                redacted = redacted.replace(normalized, "[REDACTED]");
            }
        }
        return redacted;
    }

    record SkillSmokeEvidence(
            String taskId,
            String stageRunId,
            String skillId,
            String skillVersion,
            String allowedRole,
            String rejectedRole,
            String highRiskRole,
            String installPath,
            String sourceChecksum,
            boolean installed,
            boolean unauthorizedRejected,
            boolean highRiskWaitingApproval,
            boolean metadataValidated,
            int installerCallCount,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<String> secretNeedles
    ) {
        SkillSmokeEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            skillId = oneLine(skillId);
            skillVersion = oneLine(skillVersion);
            allowedRole = oneLine(allowedRole);
            rejectedRole = oneLine(rejectedRole);
            highRiskRole = oneLine(highRiskRole);
            installPath = oneLine(installPath);
            sourceChecksum = oneLine(sourceChecksum);
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            installerCallCount = Math.max(installerCallCount, 0);
            secretNeedles = secretNeedles == null ? List.of() : List.copyOf(secretNeedles);
        }
    }

    private record AcceptancePoint(int number, String title) {
    }

    private record MatrixStatus(String status, String evidence) {
    }
}
