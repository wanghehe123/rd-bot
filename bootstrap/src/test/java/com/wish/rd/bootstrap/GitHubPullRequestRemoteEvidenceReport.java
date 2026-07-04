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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes Markdown evidence for the independent remote GitHub PR body verification smoke.
 */
final class GitHubPullRequestRemoteEvidenceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final Pattern ARTIFACT_URI = Pattern.compile(
            "(?:https?://|s3://|rd-artifact://)[^\\s)\\]}>,\"']+"
    );
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

    GitHubPullRequestRemoteEvidenceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static GitHubPullRequestRemoteEvidenceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.github.pr-evidence.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new GitHubPullRequestRemoteEvidenceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED", List.of());
        markdown.append("""

                ## 未运行原因

                GitHub PR 远端反查 smoke 参数不完整，本次没有调用真实 GitHub PR 查询接口。
                缺少的条件只记录属性名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, notRunMatrix("缺少真实 GitHub PR 远端反查前置条件"));
        return write(markdown);
    }

    Path writePassed(RemotePrEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, passedMatrix(evidence));
        Path reportPath = write(markdown);
        writePassedEvidenceJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(RemotePrEvidence evidence, Throwable failure) throws IOException {
        RemotePrEvidence safeEvidence = evidence == null ? RemotePrEvidence.empty() : evidence;
        StringBuilder markdown = header("FAILED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE", safeEvidence.secretNeedles());
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
                .append("# RD-Bot GitHub PR 远端反查生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明远端 GitHub PR body 反查 smoke 覆盖的证据；")
                .append("未真实运行的验收点不得记为通过。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名和占位符，不记录 GitHub token 或 secret needle 的真实值。

                ```bash
                GITHUB_PAT=<secret> ./mvnw -pl bootstrap -am -Dtest=GitHubPullRequestRemoteEvidenceRealSmokeTest \\
                  -Drd.integration.github-pr-evidence.enabled=true \\
                  -Drd.github.pr-evidence.production-evidence=true \\
                  -Drd.github.pr-evidence.rd-bot-version=<rd-bot-version> \\
                  -Drd.github.pr-evidence.environment-id=<production-environment-id> \\
                  -Drd.github.pr-evidence.executed-by=<operator> \\
                  -Drd.github.pr-evidence.task-id=<task-id> \\
                  -Drd.github.pr-evidence.repo-owner=<repo-owner> \\
                  -Drd.github.pr-evidence.repo-name=<repo-name> \\
                  -Drd.github.pr-evidence.base-branch=<base-branch> \\
                  -Drd.github.pr-evidence.work-branch=<work-branch> \\
                  -Drd.github.pr-evidence.pull-number=<pull-number> \\
                  -Drd.github.pr-evidence.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, RemotePrEvidence evidence) {
        markdown.append("\n## GitHub PR 远端反查证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- repository：").append(oneLine(evidence.repository())).append('\n');
        markdown.append("- baseBranch：").append(oneLine(evidence.baseBranch())).append('\n');
        markdown.append("- workBranch：").append(oneLine(evidence.workBranch())).append('\n');
        markdown.append("- pullRequestUrl：").append(oneLine(evidence.pullRequestUrl())).append('\n');
        markdown.append("- pullRequestNumber：").append(oneLine(evidence.pullRequestNumber())).append('\n');
        markdown.append("- pullRequestBodyLength：").append(evidence.body().length()).append('\n');
        markdown.append("- pullRequestBodyIncludesDeliveryReview：").append(bodyIncludesDeliveryReview(evidence)).append('\n');
        markdown.append("- pullRequestBodyIncludesQaEvidence：").append(bodyIncludesQaEvidence(evidence)).append('\n');
        markdown.append("- pullRequestBodyContainsTaskId：").append(bodyContainsTaskId(evidence)).append('\n');
        markdown.append("- pullRequestBodyContainsArtifactLink：").append(bodyContainsArtifactLink(evidence)).append('\n');
        markdown.append("- secretScanEvidenceValidated：").append(secretScanEvidenceValidated(evidence)).append('\n');
        markdown.append("- secretScannedValueCount：").append(evidence.secretNeedles().size()).append('\n');
        markdown.append("- secretLeakFound：").append(secretLeakFound(evidence)).append('\n');
        markdown.append("- githubPrRemoteEvidenceValidated：").append(githubPrRemoteEvidenceValidated(evidence)).append('\n');
        markdown.append("- remotePrTraceValidated：").append(githubPrRemoteEvidenceValidated(evidence)).append('\n');
    }

    private void requirePassedEvidence(RemotePrEvidence evidence) {
        requireEvidenceText(evidence.rdBotVersion(), "rdBotVersion");
        requireEvidenceText(evidence.environmentId(), "environmentId");
        requireEvidenceText(evidence.executedBy(), "executedBy");
        requireEvidenceText(evidence.taskId(), "taskId");
        requireEvidenceText(evidence.repoOwner(), "repoOwner");
        requireEvidenceText(evidence.repoName(), "repoName");
        requireEvidenceText(evidence.baseBranch(), "baseBranch");
        requireEvidenceText(evidence.workBranch(), "workBranch");
        requireEvidenceText(evidence.pullRequestUrl(), "pullRequestUrl");
        requireEvidenceText(evidence.pullRequestNumber(), "pullRequestNumber");
        if (!workBranchMatchesTaskBranch(evidence)) {
            throw new IllegalArgumentException("workBranch must equal requirement/{taskId} for PASSED report");
        }
        if (!pullRequestUrlMatchesConfiguredRepository(evidence)) {
            throw new IllegalArgumentException("pullRequestUrl must match configured repository for PASSED report");
        }
        if (!bodyIncludesDeliveryReview(evidence)) {
            throw new IllegalArgumentException("PR body must include RD-Bot Delivery Review for PASSED report");
        }
        if (!bodyIncludesQaEvidence(evidence)) {
            throw new IllegalArgumentException("PR body must include RD-Bot QA Evidence for PASSED report");
        }
        if (!bodyContainsTaskId(evidence)) {
            throw new IllegalArgumentException("PR body must include taskId for PASSED report");
        }
        if (!bodyContainsArtifactLink(evidence)) {
            throw new IllegalArgumentException("PR body must include a non-mock artifact link for PASSED report");
        }
        if (!secretScanEvidenceValidated(evidence)) {
            throw new IllegalArgumentException("secret scan evidence must pass for PASSED report");
        }
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> passedMatrix(RemotePrEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("GitHub PR 远端反查 smoke 未覆盖该验收点，需要生产专项演练。");
        statuses.put(8, new MatrixStatus(
                "NOT_RUN",
                "远端 PR body 子证据已通过：pullRequestUrl=" + oneLine(evidence.pullRequestUrl())
                        + "，包含交付复核和 QA 证据段；仍需完整多 Agent 状态机和交付复核证据。"
        ));
        statuses.put(13, new MatrixStatus(
                "NOT_RUN",
                "远端 PR body secret needle 扫描未发现明文泄露；完整 #13 还需覆盖 prompt、产物、Feishu 消息和经验条目。"
        ));
        statuses.put(14, new MatrixStatus(
                "NOT_RUN",
                "远端 PR 可反查 taskId；完整 #14 仍需从 taskId 反查数据库审计链和指标。"
        ));
        statuses.put(15, new MatrixStatus(
                "NOT_RUN",
                "GitHub PR 远端反查 smoke 只能作为 #8/#13/#14 子证据；#15 必须由多 Agent 总报告在 #1-#14 全部 PASSED 后判定。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("GitHub PR 远端反查 smoke 未覆盖该验收点，需要生产专项演练。");
        statuses.put(8, new MatrixStatus(
                "FAILED",
                "GitHub PR 远端反查失败，PR body 没有证明交付复核和 QA 证据。"
        ));
        statuses.put(13, new MatrixStatus(
                "FAILED",
                "GitHub PR 远端反查失败，PR body secret 扫描未通过或证据不足。"
        ));
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "GitHub PR 远端反查 smoke 失败；#15 不能标记为通过。"
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
        Path reportPath = reportRoot.resolve("github-pr-remote-evidence-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writePassedEvidenceJson(Path markdownReportPath, RemotePrEvidence evidence) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", "PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE");
        json.put("generatedAt", clock.instant().toString());
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("taskId", evidence.taskId());
        json.put("repoOwner", evidence.repoOwner());
        json.put("repoName", evidence.repoName());
        json.put("repository", evidence.repository());
        json.put("baseBranch", evidence.baseBranch());
        json.put("workBranch", evidence.workBranch());
        json.put("pullRequestUrl", evidence.pullRequestUrl());
        json.put("pullRequestNumber", evidence.pullRequestNumber());
        json.put("pullRequestBodyLength", evidence.body().length());
        json.put("pullRequestBodyIncludesDeliveryReview", bodyIncludesDeliveryReview(evidence));
        json.put("pullRequestBodyIncludesQaEvidence", bodyIncludesQaEvidence(evidence));
        json.put("pullRequestBodyContainsTaskId", bodyContainsTaskId(evidence));
        json.put("pullRequestBodyContainsExactTaskId", bodyContainsTaskId(evidence));
        json.put("pullRequestBodyContainsArtifactLink", bodyContainsArtifactLink(evidence));
        json.put("secretScanEvidenceValidated", secretScanEvidenceValidated(evidence));
        json.put("secretScannedValueCount", evidence.secretNeedles().size());
        json.put("secretLeakFound", secretLeakFound(evidence));
        json.put("githubPrRemoteEvidenceValidated", githubPrRemoteEvidenceValidated(evidence));
        json.put("remotePrTraceValidated", githubPrRemoteEvidenceValidated(evidence));
        json.put("scope", "independent-github-pr-remote-body-evidence-smoke");
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static boolean githubPrRemoteEvidenceValidated(RemotePrEvidence evidence) {
        return evidence != null
                && pullRequestUrlMatchesConfiguredRepository(evidence)
                && bodyIncludesDeliveryReview(evidence)
                && bodyIncludesQaEvidence(evidence)
                && bodyContainsTaskId(evidence)
                && bodyContainsArtifactLink(evidence)
                && secretScanEvidenceValidated(evidence);
    }

    private static boolean pullRequestUrlMatchesConfiguredRepository(RemotePrEvidence evidence) {
        String pullRequestUrl = oneLine(evidence.pullRequestUrl());
        if (pullRequestUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(pullRequestUrl);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String host = oneLine(uri.getHost()).toLowerCase(java.util.Locale.ROOT);
            String expectedPath = "/" + oneLine(evidence.repoOwner()) + "/" + oneLine(evidence.repoName())
                    + "/pull/" + oneLine(evidence.pullRequestNumber());
            return uri.isAbsolute()
                    && isHttpOrHttps(uri)
                    && "github.com".equals(host)
                    && path.equals(expectedPath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isHttpOrHttps(URI uri) {
        String scheme = oneLine(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    private static boolean workBranchMatchesTaskBranch(RemotePrEvidence evidence) {
        String taskId = oneLine(evidence.taskId());
        return !taskId.isBlank() && ("requirement/" + taskId).equals(oneLine(evidence.workBranch()));
    }

    private static boolean bodyIncludesDeliveryReview(RemotePrEvidence evidence) {
        return evidence.body().contains("RD-Bot Delivery Review");
    }

    private static boolean bodyIncludesQaEvidence(RemotePrEvidence evidence) {
        return evidence.body().contains("RD-Bot QA Evidence");
    }

    private static boolean bodyContainsTaskId(RemotePrEvidence evidence) {
        String taskId = oneLine(evidence.taskId());
        if (taskId.isBlank()) {
            return false;
        }
        Pattern exactTaskId = Pattern.compile("(?<![A-Za-z0-9_-])"
                + Pattern.quote(taskId)
                + "(?![A-Za-z0-9_-])");
        return exactTaskId.matcher(evidence.body()).find();
    }

    private static boolean bodyContainsArtifactLink(RemotePrEvidence evidence) {
        String body = evidence.body();
        if (body.contains("mock://")) {
            return false;
        }
        for (String line : body.split("\\R")) {
            if (!artifactEvidenceLine(line)) {
                continue;
            }
            Matcher matcher = ARTIFACT_URI.matcher(line);
            while (matcher.find()) {
                if (ProductionEvidenceUris.isProductionArtifactUri(matcher.group())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean artifactEvidenceLine(String line) {
        String normalized = oneLine(line).toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("artifact")
                || normalized.contains("evidence")
                || normalized.contains("log")
                || normalized.contains("report");
    }

    private static boolean secretScanEvidenceValidated(RemotePrEvidence evidence) {
        return !evidence.secretNeedles().isEmpty() && !secretLeakFound(evidence);
    }

    private static boolean secretLeakFound(RemotePrEvidence evidence) {
        for (String needle : evidence.secretNeedles()) {
            if (!needle.isBlank() && evidence.body().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void requireEvidenceText(String value, String fieldName) {
        if (oneLine(value).isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank for PASSED report");
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

    record RemotePrEvidence(
            String rdBotVersion,
            String environmentId,
            String executedBy,
            String taskId,
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            String pullRequestUrl,
            String pullRequestNumber,
            String body,
            List<String> secretNeedles
    ) {
        RemotePrEvidence {
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            taskId = oneLine(taskId);
            repoOwner = oneLine(repoOwner);
            repoName = oneLine(repoName);
            baseBranch = oneLine(baseBranch);
            workBranch = oneLine(workBranch);
            pullRequestUrl = oneLine(pullRequestUrl);
            pullRequestNumber = oneLine(pullRequestNumber);
            body = body == null ? "" : body;
            secretNeedles = secretNeedles == null ? List.of() : secretNeedles.stream()
                    .map(GitHubPullRequestRemoteEvidenceReport::oneLine)
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        static RemotePrEvidence empty() {
            return new RemotePrEvidence("", "", "", "", "", "", "", "", "", "", "", List.of());
        }

        String repository() {
            if (repoOwner.isBlank() || repoName.isBlank()) {
                return "";
            }
            return repoOwner + "/" + repoName;
        }

        RemotePrEvidence withBody(String body) {
            return new RemotePrEvidence(
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    taskId,
                    repoOwner,
                    repoName,
                    baseBranch,
                    workBranch,
                    pullRequestUrl,
                    pullRequestNumber,
                    body,
                    secretNeedles
            );
        }
    }

    private record AcceptancePoint(int number, String title) {
    }

    private record MatrixStatus(String status, String evidence) {
    }
}
