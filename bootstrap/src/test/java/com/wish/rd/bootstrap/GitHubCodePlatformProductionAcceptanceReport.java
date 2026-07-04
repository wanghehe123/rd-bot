package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Markdown evidence for the independent GitHub PR production smoke.
 */
final class GitHubCodePlatformProductionAcceptanceReport {

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

    GitHubCodePlatformProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static GitHubCodePlatformProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.github.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new GitHubCodePlatformProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                真实 GitHub PR smoke 参数不完整，本次没有调用真实 GitHub PR 创建接口。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 GitHub PR smoke 前置条件"));
        return write(markdown);
    }

    Path writePassed(GitHubPrSmokeEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_GITHUB_PR_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writePassedEvidenceJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(GitHubPrSmokeEvidence evidence, Throwable failure) throws IOException {
        GitHubPrSmokeEvidence safeEvidence = evidence == null ? GitHubPrSmokeEvidence.empty() : evidence;
        StringBuilder markdown = header("FAILED_GITHUB_PR_SMOKE", safeEvidence.secretNeedles());
        markdown.append("\n## 失败信息\n\n");
        markdown.append("- errorType：").append(failure == null ? "" : failure.getClass().getSimpleName()).append('\n');
        markdown.append("- message：").append(redact(
                failure == null ? "" : failure.getMessage(),
                safeEvidence.secretNeedles()
        )).append('\n');
        appendEvidence(markdown, safeEvidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, failedMatrix());
        return write(markdown);
    }

    private StringBuilder header(String conclusion, List<String> secretNeedles) {
        return new StringBuilder()
                .append("# RD-Bot GitHub PR 生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 GitHubCodePlatformAdapter 真实 PR 创建 smoke 覆盖的证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 GitHub token 的真实值。

                ```bash
                GITHUB_PAT=<secret> ./mvnw -pl bootstrap -am -Dtest=GitHubCodePlatformRealSmokeTest \\
                  -Drd.integration.github.enabled=true \\
                  -Drd.github.smoke.production-evidence=true \\
                  -Drd.github.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.github.smoke.environment-id=<production-environment-id> \\
                  -Drd.github.smoke.executed-by=<operator> \\
                  -Drd.github.smoke.repo-owner=<repo-owner> \\
                  -Drd.github.smoke.repo-name=<repo-name> \\
                  -Drd.github.smoke.base-branch=<base-branch> \\
                  -Drd.github.smoke.work-branch=<existing-work-branch> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, GitHubPrSmokeEvidence evidence) {
        markdown.append("\n## GitHub PR Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- repository：").append(oneLine(evidence.repository())).append('\n');
        markdown.append("- baseBranch：").append(oneLine(evidence.baseBranch())).append('\n');
        markdown.append("- workBranch：").append(oneLine(evidence.workBranch())).append('\n');
        markdown.append("- pullRequestUrl：").append(oneLine(evidence.pullRequestUrl())).append('\n');
        markdown.append("- pullRequestNumber：").append(oneLine(evidence.pullRequestNumber())).append('\n');
        markdown.append("- productionEvidence：").append(evidence.productionEvidence()).append('\n');
        markdown.append("- codePlatformMode：REAL\n");
        markdown.append("- githubAuthMode：PAT_LOCAL_SMOKE\n");
        markdown.append("- githubPrEvidenceValidated：").append(githubPrEvidenceValidated(evidence)).append('\n');
    }

    private void requirePassedEvidence(GitHubPrSmokeEvidence evidence) {
        requireEvidenceText(evidence.rdBotVersion(), "rdBotVersion");
        requireEvidenceText(evidence.environmentId(), "environmentId");
        requireEvidenceText(evidence.executedBy(), "executedBy");
        requireEvidenceText(evidence.repoOwner(), "repoOwner");
        requireEvidenceText(evidence.repoName(), "repoName");
        requireEvidenceText(evidence.baseBranch(), "baseBranch");
        requireEvidenceText(evidence.workBranch(), "workBranch");
        requireEvidenceText(evidence.pullRequestUrl(), "pullRequestUrl");
        requireEvidenceText(evidence.pullRequestNumber(), "pullRequestNumber");
        if (!evidence.productionEvidence()) {
            throw new IllegalArgumentException("productionEvidence must be true for PASSED report");
        }
        if (evidence.baseBranch().equals(evidence.workBranch())) {
            throw new IllegalArgumentException("baseBranch and workBranch must be distinct for PASSED report");
        }
        if (!pullRequestUrlMatchesConfiguredRepository(evidence)) {
            throw new IllegalArgumentException("pullRequestUrl must match configured repository for PASSED report");
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(GitHubPrSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("GitHub PR smoke 未覆盖该验收点，需要生产专项演练。");
        statuses.put(8, new MatrixStatus(
                "NOT_RUN",
                "PR 创建子证据：repository=" + oneLine(evidence.repository())
                        + ", pullRequestUrl=" + oneLine(evidence.pullRequestUrl())
                        + "；该专项 smoke 未执行多 Agent 交付复核、状态机和 PR body 反查，不能单独计入 #8 通过。"
        ));
        statuses.put(13, new MatrixStatus(
                "NOT_RUN",
                "GitHub token 未写入报告；完整密钥扫描仍需多 Agent 总验收和远端 PR body 反查。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "GitHub PR smoke 只能作为 PR 创建专项证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("GitHub PR smoke 未覆盖该验收点，需要生产专项演练。");
        statuses.put(8, new MatrixStatus(
                "FAILED",
                "GitHub PR smoke 失败，真实 GitHub PR 创建子证据没有通过。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "GitHub PR smoke 失败，#8 的 PR 创建子证据缺失；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("github-code-platform-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writePassedEvidenceJson(Path markdownReportPath, GitHubPrSmokeEvidence evidence) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", "PASSED_GITHUB_PR_SMOKE");
        json.put("generatedAt", clock.instant().toString());
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("repoOwner", evidence.repoOwner());
        json.put("repoName", evidence.repoName());
        json.put("repository", evidence.repository());
        json.put("baseBranch", evidence.baseBranch());
        json.put("workBranch", evidence.workBranch());
        json.put("pullRequestUrl", evidence.pullRequestUrl());
        json.put("pullRequestNumber", evidence.pullRequestNumber());
        json.put("productionEvidence", evidence.productionEvidence());
        json.put("codePlatformMode", "REAL");
        json.put("githubAuthMode", "PAT_LOCAL_SMOKE");
        json.put("githubPrEvidenceValidated", githubPrEvidenceValidated(evidence));
        json.put("pullRequestPublicationSucceeded", true);
        json.put("deliveryReviewGateExercised", false);
        json.put("scope", "independent-github-code-platform-adapter-smoke");
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static void requireEvidenceText(String value, String fieldName) {
        if (oneLine(value).isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank for PASSED report");
        }
    }

    private static boolean githubPrEvidenceValidated(GitHubPrSmokeEvidence evidence) {
        return evidence != null
                && evidence.productionEvidence()
                && !oneLine(evidence.rdBotVersion()).isBlank()
                && !oneLine(evidence.environmentId()).isBlank()
                && !oneLine(evidence.executedBy()).isBlank()
                && !oneLine(evidence.pullRequestNumber()).isBlank()
                && !oneLine(evidence.baseBranch()).isBlank()
                && !oneLine(evidence.workBranch()).isBlank()
                && !oneLine(evidence.baseBranch()).equals(oneLine(evidence.workBranch()))
                && pullRequestUrlMatchesConfiguredRepository(evidence);
    }

    private static boolean pullRequestUrlMatchesConfiguredRepository(GitHubPrSmokeEvidence evidence) {
        String pullRequestUrl = oneLine(evidence.pullRequestUrl());
        if (pullRequestUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(pullRequestUrl);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String expectedPath = "/" + oneLine(evidence.repoOwner()) + "/" + oneLine(evidence.repoName())
                    + "/pull/" + oneLine(evidence.pullRequestNumber());
            return uri.isAbsolute()
                    && isHttpOrHttps(uri)
                    && "github.com".equalsIgnoreCase(oneLine(uri.getHost()))
                    && path.equals(expectedPath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isHttpOrHttps(URI uri) {
        String scheme = oneLine(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
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

    record GitHubPrSmokeEvidence(
            String rdBotVersion,
            String environmentId,
            String executedBy,
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            String pullRequestUrl,
            String pullRequestNumber,
            boolean productionEvidence,
            List<String> secretNeedles
    ) {
        GitHubPrSmokeEvidence {
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            repoOwner = oneLine(repoOwner);
            repoName = oneLine(repoName);
            baseBranch = oneLine(baseBranch);
            workBranch = oneLine(workBranch);
            pullRequestUrl = oneLine(pullRequestUrl);
            pullRequestNumber = oneLine(pullRequestNumber);
            secretNeedles = secretNeedles == null ? List.of() : List.copyOf(secretNeedles);
        }

        static GitHubPrSmokeEvidence empty() {
            return new GitHubPrSmokeEvidence("", "", "", "", "", "", "", "", "", false, List.of());
        }

        String repository() {
            if (repoOwner.isBlank() || repoName.isBlank()) {
                return "";
            }
            return repoOwner + "/" + repoName;
        }
    }

    private record AcceptancePoint(int number, String title) {
    }

    private record MatrixStatus(String status, String evidence) {
    }
}
