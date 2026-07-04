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
 * Writes Markdown and JSON evidence for the Docker coding production smoke.
 */
final class DockerCodingProductionAcceptanceReport {

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

    DockerCodingProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static DockerCodingProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.docker-coding.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new DockerCodingProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 Docker coding smoke 参数不完整，本次没有访问真实 RD-Bot HTTP 或 PostgreSQL。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 Docker coding 前置条件"));
        return write(markdown);
    }

    Path writePassed(DockerCodingSmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_DOCKER_CODING_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writeJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(DockerCodingSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_DOCKER_CODING_SMOKE", evidence.secretNeedles());
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
                .append("# RD-Bot Docker 编码生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 Docker coding smoke 覆盖的真实编码执行证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 PostgreSQL 密码或其他 secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=DockerCodingRealSmokeTest \\
                  -Drd.integration.docker-coding.enabled=true \\
                  -Drd.docker-coding.smoke.production-evidence=true \\
                  -Drd.docker-coding.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.docker-coding.smoke.environment-id=<production-environment-id> \\
                  -Drd.docker-coding.smoke.executed-by=<operator> \\
                  -Drd.docker-coding.smoke.base-url=<rd-bot-base-url> \\
                  -Drd.docker-coding.smoke.postgres-url=<postgres-jdbc-url> \\
                  -Drd.docker-coding.smoke.postgres-user=<postgres-user> \\
                  -Drd.docker-coding.smoke.postgres-password=<postgres-password> \\
                  -Drd.docker-coding.smoke.repository-url=<repository-url> \\
                  -Drd.docker-coding.smoke.task-id=<rd-bot-task-id> \\
                  -Drd.docker-coding.smoke.patch-artifact-uri=<patch-artifact-uri> \\
                  -Drd.docker-coding.smoke.result-artifact-uri=<result-artifact-uri> \\
                  -Drd.docker-coding.smoke.test-log-artifact-uri=<test-log-artifact-uri> \\
                  -Drd.docker-coding.smoke.docker-metadata-artifact-uri=<docker-metadata-artifact-uri> \\
                  -Drd.docker-coding.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, DockerCodingSmokeEvidence evidence) {
        markdown.append("\n## Docker Coding Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- stageRunId：").append(oneLine(evidence.stageRunId())).append('\n');
        markdown.append("- stageStatus：").append(oneLine(evidence.stageStatus())).append('\n');
        markdown.append("- repositoryUrl：").append(oneLine(evidence.repositoryUrl())).append('\n');
        markdown.append("- dockerImage：").append(oneLine(evidence.dockerImage())).append('\n');
        markdown.append("- containerId：").append(oneLine(evidence.containerId())).append('\n');
        markdown.append("- workspacePath：").append(oneLine(evidence.workspacePath())).append('\n');
        markdown.append("- commitHash：").append(oneLine(evidence.commitHash())).append('\n');
        markdown.append("- patchArtifactId：").append(oneLine(evidence.patchArtifactId())).append('\n');
        markdown.append("- resultArtifactId：").append(oneLine(evidence.resultArtifactId())).append('\n');
        markdown.append("- testLogArtifactId：").append(oneLine(evidence.testLogArtifactId())).append('\n');
        markdown.append("- dockerMetadataArtifactId：").append(oneLine(evidence.dockerMetadataArtifactId())).append('\n');
        markdown.append("- patchArtifactUri：").append(oneLine(evidence.patchArtifactUri())).append('\n');
        markdown.append("- resultArtifactUri：").append(oneLine(evidence.resultArtifactUri())).append('\n');
        markdown.append("- testLogArtifactUri：").append(oneLine(evidence.testLogArtifactUri())).append('\n');
        markdown.append("- dockerMetadataArtifactUri：").append(oneLine(evidence.dockerMetadataArtifactUri())).append('\n');
        markdown.append("- changedFileCount：").append(evidence.changedFileCount()).append('\n');
        markdown.append("- validationCommand：").append(oneLine(evidence.validationCommand())).append('\n');
        markdown.append("- validationExitCode：").append(evidence.validationExitCode()).append('\n');
        markdown.append("- testsRun：").append(evidence.testsRun()).append('\n');
        markdown.append("- testsFailed：").append(evidence.testsFailed()).append('\n');
        markdown.append("- patchNonEmpty：").append(evidence.patchNonEmpty()).append('\n');
        markdown.append("- resultJsonValidated：").append(evidence.resultJsonValidated()).append('\n');
        markdown.append("- realDockerRun：").append(evidence.realDockerRun()).append('\n');
    }

    private void requirePassedEvidence(DockerCodingSmokeEvidence evidence) {
        if (!dockerCodingEvidenceValidated(evidence)) {
            throw new IllegalArgumentException("Docker coding evidence must satisfy the production contract");
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(DockerCodingSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "Docker coding smoke 未覆盖该验收点，需要生产专项演练。"
        );
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", stageRunId=" + oneLine(evidence.stageRunId())
                + ", testsRun=" + evidence.testsRun();
        statuses.put(6, new MatrixStatus(
                "PASSED",
                evidenceRef + "；真实 Docker 编码阶段产出 patch/result/test log/metadata，并运行测试通过。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "Docker coding smoke 只能作为 #6 专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix(
                "Docker coding smoke 未覆盖该验收点，需要生产专项演练。"
        );
        statuses.put(6, new MatrixStatus(
                "FAILED",
                "Docker coding smoke 失败，无法证明编码 Agent 在 Docker 中真实改代码并运行测试。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "Docker coding smoke 失败，#6 未通过；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("docker-coding-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(Path markdownReportPath, DockerCodingSmokeEvidence evidence) throws IOException {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("dockerCodingEvidenceValidated", true);
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("repositoryUrl", evidence.repositoryUrl());
        json.put("taskId", evidence.taskId());
        json.put("stageRunId", evidence.stageRunId());
        json.put("role", "CODING_AGENT");
        json.put("dockerImage", evidence.dockerImage());
        json.put("containerId", evidence.containerId());
        json.put("workspacePath", evidence.workspacePath());
        json.put("commitHash", evidence.commitHash());
        json.put("patchArtifactId", evidence.patchArtifactId());
        json.put("resultArtifactId", evidence.resultArtifactId());
        json.put("testLogArtifactId", evidence.testLogArtifactId());
        json.put("dockerMetadataArtifactId", evidence.dockerMetadataArtifactId());
        json.put("patchArtifactUri", evidence.patchArtifactUri());
        json.put("resultArtifactUri", evidence.resultArtifactUri());
        json.put("testLogArtifactUri", evidence.testLogArtifactUri());
        json.put("dockerMetadataArtifactUri", evidence.dockerMetadataArtifactUri());
        json.put("changedFileCount", evidence.changedFileCount());
        json.put("validationCommand", evidence.validationCommand());
        json.put("validationExitCode", evidence.validationExitCode());
        json.put("testsRun", evidence.testsRun());
        json.put("testsFailed", evidence.testsFailed());
        json.put("patchNonEmpty", evidence.patchNonEmpty());
        json.put("resultJsonValidated", evidence.resultJsonValidated());
        json.put("realDockerRun", evidence.realDockerRun());
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static boolean dockerCodingEvidenceValidated(DockerCodingSmokeEvidence evidence) {
        return evidence != null
                && !evidence.taskId().isBlank()
                && !evidence.stageRunId().isBlank()
                && !evidence.rdBotVersion().isBlank()
                && !evidence.environmentId().isBlank()
                && !evidence.executedBy().isBlank()
                && "SUCCEEDED".equals(evidence.stageStatus())
                && !evidence.repositoryUrl().isBlank()
                && !evidence.dockerImage().isBlank()
                && !evidence.containerId().isBlank()
                && !evidence.workspacePath().isBlank()
                && fullGitCommitHash(evidence.commitHash())
                && artifactIdsAreDistinct(evidence)
                && artifactUrisAreProductionAndDistinct(evidence)
                && evidence.changedFileCount() > 0
                && !evidence.validationCommand().isBlank()
                && evidence.validationExitCode() == 0
                && evidence.testsRun() > 0
                && evidence.testsFailed() == 0
                && evidence.patchNonEmpty()
                && evidence.resultJsonValidated()
                && evidence.realDockerRun();
    }

    private static boolean artifactIdsAreDistinct(DockerCodingSmokeEvidence evidence) {
        List<String> ids = List.of(
                evidence.patchArtifactId(),
                evidence.resultArtifactId(),
                evidence.testLogArtifactId(),
                evidence.dockerMetadataArtifactId()
        );
        return ids.stream().noneMatch(String::isBlank)
                && ids.stream().distinct().count() == ids.size();
    }

    private static boolean artifactUrisAreProductionAndDistinct(DockerCodingSmokeEvidence evidence) {
        List<String> uris = List.of(
                evidence.patchArtifactUri(),
                evidence.resultArtifactUri(),
                evidence.testLogArtifactUri(),
                evidence.dockerMetadataArtifactUri()
        );
        return uris.stream().allMatch(ProductionEvidenceUris::isProductionArtifactUri)
                && uris.stream().distinct().count() == uris.size();
    }

    private static boolean fullGitCommitHash(String commitHash) {
        return oneLine(commitHash).matches("([0-9a-fA-F]{40}|[0-9a-fA-F]{64})");
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

    record DockerCodingSmokeEvidence(
            String taskId,
            String stageRunId,
            String stageStatus,
            String repositoryUrl,
            String dockerImage,
            String containerId,
            String workspacePath,
            String commitHash,
            String patchArtifactId,
            String resultArtifactId,
            String testLogArtifactId,
            String dockerMetadataArtifactId,
            String patchArtifactUri,
            String resultArtifactUri,
            String testLogArtifactUri,
            String dockerMetadataArtifactUri,
            int changedFileCount,
            String validationCommand,
            int validationExitCode,
            int testsRun,
            int testsFailed,
            boolean patchNonEmpty,
            boolean resultJsonValidated,
            boolean realDockerRun,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            List<String> secretNeedles
    ) {
        DockerCodingSmokeEvidence(
                DockerCodingProductionAcceptanceProfile profile,
                String stageRunId,
                String stageStatus,
                String dockerImage,
                String containerId,
                String workspacePath,
                String commitHash,
                String patchArtifactId,
                String resultArtifactId,
                String testLogArtifactId,
                String dockerMetadataArtifactId,
                int changedFileCount,
                String validationCommand,
                int validationExitCode,
                int testsRun,
                int testsFailed,
                boolean patchNonEmpty,
                boolean resultJsonValidated,
                boolean realDockerRun
        ) {
            this(
                    profile.taskId(),
                    stageRunId,
                    stageStatus,
                    profile.repositoryUrl(),
                    dockerImage,
                    containerId,
                    workspacePath,
                    commitHash,
                    patchArtifactId,
                    resultArtifactId,
                    testLogArtifactId,
                    dockerMetadataArtifactId,
                    profile.patchArtifactUri(),
                    profile.resultArtifactUri(),
                    profile.testLogArtifactUri(),
                    profile.dockerMetadataArtifactUri(),
                    changedFileCount,
                    validationCommand,
                    validationExitCode,
                    testsRun,
                    testsFailed,
                    patchNonEmpty,
                    resultJsonValidated,
                    realDockerRun,
                    profile.rdBotVersion(),
                    profile.environmentId(),
                    profile.executedBy(),
                    profile.secretScanNeedles()
            );
        }

        DockerCodingSmokeEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            stageStatus = oneLine(stageStatus);
            repositoryUrl = oneLine(repositoryUrl);
            dockerImage = oneLine(dockerImage);
            containerId = oneLine(containerId);
            workspacePath = oneLine(workspacePath);
            commitHash = oneLine(commitHash);
            patchArtifactId = oneLine(patchArtifactId);
            resultArtifactId = oneLine(resultArtifactId);
            testLogArtifactId = oneLine(testLogArtifactId);
            dockerMetadataArtifactId = oneLine(dockerMetadataArtifactId);
            patchArtifactUri = oneLine(patchArtifactUri);
            resultArtifactUri = oneLine(resultArtifactUri);
            testLogArtifactUri = oneLine(testLogArtifactUri);
            dockerMetadataArtifactUri = oneLine(dockerMetadataArtifactUri);
            changedFileCount = Math.max(changedFileCount, 0);
            validationCommand = oneLine(validationCommand);
            validationExitCode = Math.max(validationExitCode, 0);
            testsRun = Math.max(testsRun, 0);
            testsFailed = Math.max(testsFailed, 0);
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            secretNeedles = secretNeedles == null ? List.of() : List.copyOf(secretNeedles);
        }
    }

    private record AcceptancePoint(int number, String title) {
    }

    private record MatrixStatus(String status, String evidence) {
    }
}
