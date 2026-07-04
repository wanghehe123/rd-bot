package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates already-written production evidence sidecars into a 15-point acceptance ledger.
 */
final class ProductionAcceptanceEvidenceLedger {

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

    ProductionAcceptanceEvidenceLedger(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static ProductionAcceptanceEvidenceLedger fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.multi-agent.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new ProductionAcceptanceEvidenceLedger(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path write(Path evidenceRoot) throws IOException {
        Path safeEvidenceRoot = evidenceRoot == null
                ? MultiAgentProductionAcceptanceReport.resolveReportRoot("qa-runs/multi-agent-production-acceptance")
                : evidenceRoot.toAbsolutePath().normalize();
        Map<Integer, LedgerRow> rows = initialRows();
        applyMultiAgentMatrix(safeEvidenceRoot, rows);
        applyProviderPreflight(safeEvidenceRoot, rows);
        applySimpleSidecar(
                safeEvidenceRoot,
                rows,
                3,
                "requirement-review-blocker-production-acceptance-",
                "requirementReviewBlockerEvidenceValidated",
                "PASSED_REQUIREMENT_REVIEW_BLOCKER_SMOKE",
                "需求评审阻断 sidecar 已通过"
        );
        applySimpleSidecar(
                safeEvidenceRoot,
                rows,
                6,
                "docker-coding-production-acceptance-",
                "dockerCodingEvidenceValidated",
                "PASSED_DOCKER_CODING_SMOKE",
                "Docker 编码 sidecar 已通过"
        );
        applySimpleSidecar(
                safeEvidenceRoot,
                rows,
                7,
                "qa-failure-blocker-production-acceptance-",
                "qaFailureBlockerEvidenceValidated",
                "PASSED_QA_FAILURE_BLOCKER_SMOKE",
                "QA 失败阻断 sidecar 已通过"
        );
        applySimpleSidecar(
                safeEvidenceRoot,
                rows,
                9,
                "workflow-recovery-production-acceptance-",
                "workflowRecoveryEvidenceValidated",
                "PASSED_WORKFLOW_RECOVERY_SMOKE",
                "状态恢复 sidecar 已通过"
        );
        applySimpleSidecar(
                safeEvidenceRoot,
                rows,
                10,
                "feishu-alert-production-acceptance-",
                "feishuAlertEvidenceValidated",
                "PASSED_FEISHU_ALERT_SMOKE",
                "Feishu 六类告警 sidecar 已通过"
        );
        applySimpleSidecar(
                safeEvidenceRoot,
                rows,
                11,
                "skill-production-acceptance-",
                "skillPolicyEvidenceValidated",
                "PASSED_SKILL_POLICY_SMOKE",
                "Skill 策略 sidecar 已通过"
        );
        applySimpleSidecar(
                safeEvidenceRoot,
                rows,
                14,
                "observability-metrics-production-acceptance-",
                "observabilityMetricsEvidenceValidated",
                "PASSED_OBSERVABILITY_METRICS_SMOKE",
                "指标与审计 sidecar 已通过"
        );
        applyDeliveryReviewGateEvidence(safeEvidenceRoot, rows);
        applyGitHubRemoteEvidence(safeEvidenceRoot, rows);
        finalizeAcceptance(rows);
        List<Diagnostic> diagnostics = diagnostics(safeEvidenceRoot);
        List<NextAction> nextActions = nextActions(safeEvidenceRoot, rows);
        String conclusion = conclusion(rows);
        Path reportPath = writeMarkdown(safeEvidenceRoot, rows, diagnostics, nextActions, conclusion);
        writeJson(reportPath, rows, diagnostics, nextActions, conclusion);
        return reportPath;
    }

    private Map<Integer, LedgerRow> initialRows() {
        Map<Integer, LedgerRow> rows = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            rows.put(point.number(), new LedgerRow(
                    point.number(),
                    point.title(),
                    "NOT_RUN",
                    "尚无真实生产 sidecar 证明该验收点。",
                    "MISSING"
            ));
        }
        return rows;
    }

    private void applyMultiAgentMatrix(Path evidenceRoot, Map<Integer, LedgerRow> rows) throws IOException {
        Optional<Path> latest = latest(evidenceRoot, "multi-agent-production-acceptance-");
        if (latest.isEmpty()) {
            return;
        }
        JsonNode root = readTree(latest.get());
        JsonNode matrix = root.path("matrix");
        if (!matrix.isArray()) {
            return;
        }
        String evidencePath = relative(evidenceRoot, latest.get());
        String missingRequirements = missingRequirementsSummary(root);
        for (JsonNode row : matrix) {
            int number = row.path("number").asInt(-1);
            if (!rows.containsKey(number)) {
                continue;
            }
            String status = normalizeStatus(row.path("status").asText(""));
            String evidence = safe(row.path("evidence").asText(""));
            String rowEvidence = evidencePath + "；"
                    + (evidence.isBlank() ? "总 smoke matrix 已记录该验收点。" : evidence);
            if ("NOT_RUN".equals(status) && !missingRequirements.isBlank()) {
                rowEvidence = rowEvidence + "；missing=" + missingRequirements;
            }
            rows.put(number, new LedgerRow(
                    number,
                    title(number),
                    status,
                    rowEvidence,
                    evidencePath
            ));
        }
    }

    private String missingRequirementsSummary(JsonNode root) {
        JsonNode missingRequirements = root.path("missingRequirements");
        if (!missingRequirements.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode missingRequirement : missingRequirements) {
            String value = safe(missingRequirement.asText(""));
            if (value.isBlank()) {
                continue;
            }
            int equalsIndex = value.indexOf('=');
            names.add(oneLine(equalsIndex >= 0 ? value.substring(0, equalsIndex) : value));
        }
        return String.join(", ", names);
    }

    private void applyProviderPreflight(Path evidenceRoot, Map<Integer, LedgerRow> rows) throws IOException {
        Optional<Path> latest = latest(evidenceRoot, "provider-preflight-production-acceptance-");
        if (latest.isEmpty() || "PASSED".equals(rows.get(5).status())) {
            return;
        }
        Path sidecar = latest.get();
        JsonNode root = readTree(sidecar);
        String evidencePath = relative(evidenceRoot, sidecar);
        String conclusion = root.path("conclusion").asText("");
        boolean validated = "PASSED_PROVIDER_PREFLIGHT_SMOKE".equals(conclusion)
                && root.path("providerPreflightEvidenceValidated").asBoolean(false);
        if (validated) {
            rows.put(5, new LedgerRow(
                    5,
                    title(5),
                    "PASSED",
                    evidencePath + "；provider preflight 通过，可进入真实降级/重试验收。",
                    evidencePath
            ));
            return;
        }
        if (!conclusion.isBlank() && !"SKIPPED_PROVIDER_PREFLIGHT_SMOKE".equals(conclusion)) {
            rows.put(5, new LedgerRow(
                    5,
                    title(5),
                    "FAILED",
                    evidencePath + "；provider preflight 未通过：" + providerFailureSummary(root),
                    evidencePath
            ));
        }
    }

    private void applySimpleSidecar(
            Path evidenceRoot,
            Map<Integer, LedgerRow> rows,
            int point,
            String prefix,
            String validatedField,
            String passedConclusion,
            String passedSummary
    ) throws IOException {
        if ("PASSED".equals(rows.get(point).status())) {
            return;
        }
        Optional<Path> latest = latest(evidenceRoot, prefix);
        if (latest.isEmpty()) {
            return;
        }
        Path sidecar = latest.get();
        JsonNode root = readTree(sidecar);
        String evidencePath = relative(evidenceRoot, sidecar);
        String sidecarConclusion = root.path("conclusion").asText("");
        if ((passedConclusion.equals(sidecarConclusion) || sidecarConclusion.isBlank())
                && root.path(validatedField).asBoolean(false)) {
            rows.put(point, new LedgerRow(
                    point,
                    title(point),
                    "PASSED",
                    evidencePath + "；" + passedSummary + taskSummary(root),
                    evidencePath
            ));
        } else if (!sidecarConclusion.isBlank() && sidecarConclusion.startsWith("FAILED_")) {
            rows.put(point, new LedgerRow(
                    point,
                    title(point),
                    "FAILED",
                    evidencePath + "；专项 sidecar 失败，conclusion=" + oneLine(sidecarConclusion),
                    evidencePath
            ));
        }
    }

    private void applyDeliveryReviewGateEvidence(Path evidenceRoot, Map<Integer, LedgerRow> rows) throws IOException {
        if ("PASSED".equals(rows.get(8).status())) {
            return;
        }
        Optional<Path> latestDeliveryFailure = latest(
                evidenceRoot,
                "delivery-review-failure-production-acceptance-"
        );
        Optional<Path> latestRemotePr = latest(
                evidenceRoot,
                "github-pr-remote-evidence-production-acceptance-"
        );
        if (latestDeliveryFailure.isEmpty() || latestRemotePr.isEmpty()) {
            return;
        }
        Path deliverySidecar = latestDeliveryFailure.get();
        Path remotePrSidecar = latestRemotePr.get();
        JsonNode delivery = readTree(deliverySidecar);
        JsonNode remotePr = readTree(remotePrSidecar);
        String deliveryEvidence = relative(evidenceRoot, deliverySidecar);
        String remotePrEvidence = relative(evidenceRoot, remotePrSidecar);
        boolean deliveryFailureBlocked = delivery.path("deliveryReviewFailureEvidenceValidated").asBoolean(false)
                && !delivery.path("deliveryReviewApproved").asBoolean(true)
                && "REJECTED".equals(value(delivery, "taskStatus"))
                && "FAILED".equals(value(delivery, "reviewDecision"))
                && "DELIVERY_REVIEWER".equals(value(delivery, "reviewer"))
                && ProductionEvidenceUris.isProductionArtifactUri(value(delivery, "reviewArtifactUri"))
                && !delivery.path("pullRequestPublicationAttempted").asBoolean(true)
                && !delivery.path("prCreated").asBoolean(true)
                && !delivery.path("successReportCreated").asBoolean(true)
                && delivery.path("failureReportCreated").asBoolean(false)
                && !delivery.path("successDeliveryReportExperienceCreated").asBoolean(true)
                && delivery.path("blockedBeforePrCreating").asBoolean(false);
        boolean remotePrBodyProved = "PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE".equals(value(remotePr, "conclusion"))
                && remotePr.path("githubPrRemoteEvidenceValidated").asBoolean(false)
                && remotePr.path("remotePrTraceValidated").asBoolean(false)
                && remotePr.path("pullRequestBodyIncludesDeliveryReview").asBoolean(false)
                && remotePr.path("pullRequestBodyIncludesQaEvidence").asBoolean(false)
                && remotePr.path("pullRequestBodyContainsTaskId").asBoolean(false)
                && remotePr.path("pullRequestBodyContainsArtifactLink").asBoolean(false);
        boolean sameRun = sameProductionRun(delivery, remotePr);
        if (deliveryFailureBlocked && remotePrBodyProved && sameRun) {
            rows.put(8, new LedgerRow(
                    8,
                    title(8),
                    "PASSED",
                    deliveryEvidence + " + " + remotePrEvidence
                            + "；复核失败阻断 PR/成功报告/完成态，复核通过后的远端 PR body 包含交付复核、QA 证据、taskId 和产物链接"
                            + taskSummary(delivery) + pullRequestSummary(remotePr),
                    deliveryEvidence + " + " + remotePrEvidence
            ));
            return;
        }
        boolean failedConclusion = value(delivery, "conclusion").startsWith("FAILED_")
                || value(remotePr, "conclusion").startsWith("FAILED_");
        if (failedConclusion) {
            rows.put(8, new LedgerRow(
                    8,
                    title(8),
                    "FAILED",
                    deliveryEvidence + " + " + remotePrEvidence
                            + "；交付复核门控证据不足：deliveryFailureBlocked=" + deliveryFailureBlocked
                            + ", remotePrBodyProved=" + remotePrBodyProved
                            + ", sameRun=" + sameRun,
                    deliveryEvidence + " + " + remotePrEvidence
            ));
        }
    }

    private void applyGitHubRemoteEvidence(Path evidenceRoot, Map<Integer, LedgerRow> rows) throws IOException {
        if ("PASSED".equals(rows.get(13).status())) {
            return;
        }
        Optional<Path> latest = latest(evidenceRoot, "github-pr-remote-evidence-production-acceptance-");
        if (latest.isEmpty()) {
            return;
        }
        Path sidecar = latest.get();
        JsonNode root = readTree(sidecar);
        String evidencePath = relative(evidenceRoot, sidecar);
        boolean validated = "PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE".equals(root.path("conclusion").asText(""))
                && root.path("githubPrRemoteEvidenceValidated").asBoolean(false)
                && root.path("secretScanEvidenceValidated").asBoolean(false);
        if (validated) {
            rows.put(13, new LedgerRow(
                    13,
                    title(13),
                    "PASSED",
                    evidencePath + "；远端 PR 反查和 secret needle 扫描通过" + taskSummary(root)
                            + pullRequestSummary(root),
                    evidencePath
            ));
            return;
        }
        if (root.path("conclusion").asText("").startsWith("FAILED_")) {
            rows.put(13, new LedgerRow(
                    13,
                    title(13),
                    "FAILED",
                    evidencePath + "；远端 PR/secret 扫描 sidecar 失败，conclusion="
                            + oneLine(root.path("conclusion").asText("")),
                    evidencePath
            ));
        }
    }

    private void finalizeAcceptance(Map<Integer, LedgerRow> rows) {
        List<String> pending = pendingPriorAcceptances(rows);
        String status;
        if (pending.isEmpty()) {
            status = "PASSED";
        } else if (rows.values().stream().anyMatch(row -> row.number() <= 14 && "FAILED".equals(row.status()))) {
            status = "FAILED";
        } else {
            status = "NOT_RUN";
        }
        String evidence = pending.isEmpty()
                ? passedFinalEvidence(rows.get(15))
                : "必须先完成 #1-#14 全部生产验收；未通过或未运行：" + String.join(", ", pending) + "。";
        String sourceEvidence = status.equals("PASSED") ? passedFinalSource(rows.get(15)) : "MISSING";
        rows.put(15, new LedgerRow(15, title(15), status, evidence, sourceEvidence));
    }

    private String passedFinalEvidence(LedgerRow currentFinalRow) {
        if (currentFinalRow != null && "PASSED".equals(currentFinalRow.status())
                && !"MISSING".equals(currentFinalRow.sourceEvidence())) {
            return currentFinalRow.evidence();
        }
        return "#1-#14 均已通过，可归档完整生产验收结论。";
    }

    private String passedFinalSource(LedgerRow currentFinalRow) {
        if (currentFinalRow != null && !"MISSING".equals(currentFinalRow.sourceEvidence())) {
            return currentFinalRow.sourceEvidence();
        }
        return "MATRIX";
    }

    private List<String> pendingPriorAcceptances(Map<Integer, LedgerRow> rows) {
        List<String> pending = new ArrayList<>();
        for (int point = 1; point <= 14; point++) {
            LedgerRow row = rows.get(point);
            if (row == null || !"PASSED".equals(row.status())) {
                pending.add("#" + point);
            }
        }
        return List.copyOf(pending);
    }

    private List<Diagnostic> diagnostics(Path evidenceRoot) throws IOException {
        List<Diagnostic> diagnostics = new ArrayList<>();
        addLatestDiagnostic(
                evidenceRoot,
                diagnostics,
                "full-maven-local-regression-",
                "本机 Maven 回归",
                "本机 Maven 回归只作为代码质量证据，不计入生产验收点通过"
        );
        addLatestDiagnostic(
                evidenceRoot,
                diagnostics,
                "multi-agent-production-readiness-",
                "完整 smoke readiness",
                "readiness 只判断是否可以进入长链路 smoke"
        );
        addLatestDiagnostic(
                evidenceRoot,
                diagnostics,
                "minimax-mmx-cli-real-probe-",
                "MiniMax CLI 探活",
                "MiniMax CLI 探活只作为 provider 诊断"
        );
        return List.copyOf(diagnostics);
    }

    private List<NextAction> nextActions(Path evidenceRoot, Map<Integer, LedgerRow> rows) throws IOException {
        List<NextAction> nextActions = new ArrayList<>();
        if ("FAILED".equals(rows.get(5).status())) {
            providerFailureNextActions(evidenceRoot, nextActions);
        }
        if (nextActions.isEmpty() && !"PASSED".equals(rows.get(15).status())) {
            nextActions.add(new NextAction(
                    "完整生产验收",
                    "PRIOR_ACCEPTANCE_PENDING",
                    "先补齐 #1-#14 中仍为 FAILED 或 NOT_RUN 的生产证据，再重跑完整总 smoke。",
                    rows.get(15).sourceEvidence()
            ));
        }
        return List.copyOf(nextActions);
    }

    private void providerFailureNextActions(Path evidenceRoot, List<NextAction> nextActions) throws IOException {
        Optional<Path> latest = latest(evidenceRoot, "provider-preflight-production-acceptance-");
        if (latest.isEmpty()) {
            return;
        }
        JsonNode providers = readTree(latest.get()).path("providers");
        if (!providers.isArray()) {
            return;
        }
        String evidencePath = relative(evidenceRoot, latest.get());
        for (JsonNode provider : providers) {
            String providerName = oneLine(provider.path("name").asText("provider"));
            String failureReason = oneLine(provider.path("failureReason").asText(""));
            if (failureReason.isBlank()) {
                continue;
            }
            nextActions.add(new NextAction(
                    providerName,
                    failureReason,
                    providerName + "：" + providerFailureAction(failureReason),
                    evidencePath
            ));
        }
    }

    private String providerFailureAction(String failureReason) {
        return switch (failureReason) {
            case "PROVIDER_AUTHENTICATION_FAILED" ->
                    "重新注入有效 provider secret，并重跑 ProviderPreflightRealSmokeTest。";
            case "PROVIDER_QUOTA_OR_RATE_LIMIT" ->
                    "恢复 provider quota 或 Token Plan 额度，并重跑 ProviderPreflightRealSmokeTest。";
            case "PROVIDER_UPSTREAM_UNAVAILABLE" ->
                    "等待上游恢复或切换同协议 provider，并重跑 ProviderPreflightRealSmokeTest。";
            default -> "按 failureReason 修复 provider 侧问题，并重跑 ProviderPreflightRealSmokeTest。";
        };
    }

    private void addLatestDiagnostic(
            Path evidenceRoot,
            List<Diagnostic> diagnostics,
            String prefix,
            String label,
            String note
    ) throws IOException {
        Optional<Path> latest = latest(evidenceRoot, prefix);
        if (latest.isEmpty()) {
            return;
        }
        String conclusion = readTree(latest.get()).path("conclusion").asText("UNKNOWN");
        diagnostics.add(new Diagnostic(label, relative(evidenceRoot, latest.get()), conclusion, note));
    }

    private String conclusion(Map<Integer, LedgerRow> rows) {
        if ("PASSED".equals(rows.get(15).status())) {
            return "PASSED_PRODUCTION_ACCEPTANCE_LEDGER";
        }
        boolean hasFailed = rows.values().stream().anyMatch(row -> "FAILED".equals(row.status()));
        return hasFailed ? "FAILED_PRODUCTION_ACCEPTANCE_LEDGER" : "NOT_RUN_PRODUCTION_ACCEPTANCE_LEDGER";
    }

    private Path writeMarkdown(
            Path evidenceRoot,
            Map<Integer, LedgerRow> rows,
            List<Diagnostic> diagnostics,
            List<NextAction> nextActions,
            String conclusion
    ) throws IOException {
        Files.createDirectories(reportRoot);
        Path reportPath = reportRoot.resolve("production-acceptance-evidence-ledger-"
                + FILE_TIME.format(clock.instant()) + ".md");
        StringBuilder markdown = new StringBuilder()
                .append("# RD-Bot 生产验收证据台账\n\n")
                .append("- 生成时间：").append(clock.instant()).append('\n')
                .append("- 证据目录：").append(oneLine(evidenceRoot.toString())).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 验收点总数：").append(ACCEPTANCE_POINTS.size()).append('\n')
                .append("- PASSED：").append(count(rows, "PASSED")).append('\n')
                .append("- FAILED：").append(count(rows, "FAILED")).append('\n')
                .append("- NOT_RUN：").append(count(rows, "NOT_RUN")).append('\n')
                .append("- 说明：该台账只汇总真实 sidecar 和本机诊断；本机回归不替代生产验收。\n\n");
        if (!diagnostics.isEmpty()) {
            markdown.append("## 本机诊断\n\n");
            markdown.append("| item | evidence | conclusion | note |\n");
            markdown.append("| --- | --- | --- | --- |\n");
            for (Diagnostic diagnostic : diagnostics) {
                markdown.append("| ").append(diagnostic.label())
                        .append(" | ").append(diagnostic.evidence())
                        .append(" | ").append(diagnostic.conclusion())
                        .append(" | ").append(diagnostic.note())
                        .append(" |\n");
            }
            markdown.append('\n');
        }
        if (!nextActions.isEmpty()) {
            markdown.append("## 下一步动作\n\n");
            markdown.append("| item | reason | action | evidence |\n");
            markdown.append("| --- | --- | --- | --- |\n");
            for (NextAction nextAction : nextActions) {
                markdown.append("| ").append(nextAction.item())
                        .append(" | ").append(nextAction.reason())
                        .append(" | ").append(nextAction.action())
                        .append(" | ").append(nextAction.evidence())
                        .append(" |\n");
            }
            markdown.append('\n');
        }
        markdown.append("## 验收点矩阵\n\n");
        markdown.append("| # | 验收点 | 结论 | 证据/缺口 |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            LedgerRow row = rows.get(point.number());
            markdown.append("| ").append(point.number())
                    .append(" | ").append(point.title())
                    .append(" | ").append(row.status())
                    .append(" | ").append(oneLine(row.evidence()))
                    .append(" |\n");
        }
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(
            Path reportPath,
            Map<Integer, LedgerRow> rows,
            List<Diagnostic> diagnostics,
            List<NextAction> nextActions,
            String conclusion
    ) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", conclusion);
        json.put("generatedAt", clock.instant().toString());
        json.put("totalAcceptancePointCount", ACCEPTANCE_POINTS.size());
        json.put("passedCount", count(rows, "PASSED"));
        json.put("failedCount", count(rows, "FAILED"));
        json.put("notRunCount", count(rows, "NOT_RUN"));
        json.put("rows", rows.values().stream().map(this::rowJson).toList());
        json.put("diagnostics", diagnostics.stream().map(this::diagnosticJson).toList());
        json.put("nextActions", nextActions.stream().map(this::nextActionJson).toList());
        Files.writeString(jsonPath(reportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private Map<String, Object> rowJson(LedgerRow row) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("number", row.number());
        json.put("title", row.title());
        json.put("status", row.status());
        json.put("evidence", row.evidence());
        json.put("sourceEvidence", row.sourceEvidence());
        return json;
    }

    private Map<String, Object> diagnosticJson(Diagnostic diagnostic) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("label", diagnostic.label());
        json.put("evidence", diagnostic.evidence());
        json.put("conclusion", diagnostic.conclusion());
        json.put("note", diagnostic.note());
        return json;
    }

    private Map<String, Object> nextActionJson(NextAction nextAction) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("item", nextAction.item());
        json.put("reason", nextAction.reason());
        json.put("action", nextAction.action());
        json.put("evidence", nextAction.evidence());
        return json;
    }

    private long count(Map<Integer, LedgerRow> rows, String status) {
        return rows.values().stream().filter(row -> status.equals(row.status())).count();
    }

    private String providerFailureSummary(JsonNode root) {
        JsonNode providers = root.path("providers");
        if (!providers.isArray()) {
            return "conclusion=" + oneLine(root.path("conclusion").asText("UNKNOWN"));
        }
        List<String> failures = new ArrayList<>();
        for (JsonNode provider : providers) {
            String reason = safe(provider.path("failureReason").asText(""));
            if (reason.isBlank()) {
                continue;
            }
            failures.add(oneLine(provider.path("name").asText("provider"))
                    + " httpStatus=" + provider.path("httpStatus").asInt(-1)
                    + " failureReason=" + oneLine(reason));
        }
        if (failures.isEmpty()) {
            return "conclusion=" + oneLine(root.path("conclusion").asText("UNKNOWN"));
        }
        return String.join("; ", failures);
    }

    private String taskSummary(JsonNode root) {
        String taskId = safe(root.path("taskId").asText(""));
        return taskId.isBlank() ? "" : "，taskId=" + oneLine(taskId);
    }

    private String pullRequestSummary(JsonNode root) {
        String pullRequestUrl = safe(root.path("pullRequestUrl").asText(""));
        return pullRequestUrl.isBlank() ? "" : "，PR=" + oneLine(pullRequestUrl);
    }

    private boolean sameProductionRun(JsonNode left, JsonNode right) {
        return sameRequiredField(left, right, "rdBotVersion")
                && sameRequiredField(left, right, "environmentId")
                && sameRequiredField(left, right, "executedBy");
    }

    private boolean sameRequiredField(JsonNode left, JsonNode right, String fieldName) {
        String leftValue = value(left, fieldName);
        String rightValue = value(right, fieldName);
        return !leftValue.isBlank() && leftValue.equals(rightValue);
    }

    private String value(JsonNode root, String fieldName) {
        return safe(root.path(fieldName).asText(""));
    }

    private Optional<Path> latest(Path evidenceRoot, String prefix) throws IOException {
        if (!Files.isDirectory(evidenceRoot)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> paths = Files.list(evidenceRoot)) {
            return paths.filter(path -> matches(path, prefix))
                    .max(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        }
    }

    private boolean matches(Path path, String prefix) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith(prefix) && fileName.endsWith(".json");
    }

    private JsonNode readTree(Path path) throws IOException {
        return OBJECT_MAPPER.readTree(path.toFile());
    }

    private String relative(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
    }

    private String normalizeStatus(String status) {
        String safeStatus = safe(status);
        if ("PASSED".equals(safeStatus) || "FAILED".equals(safeStatus) || "NOT_RUN".equals(safeStatus)) {
            return safeStatus;
        }
        return "NOT_RUN";
    }

    private String title(int point) {
        return ACCEPTANCE_POINTS.stream()
                .filter(acceptancePoint -> acceptancePoint.number() == point)
                .findFirst()
                .map(AcceptancePoint::title)
                .orElse("验收点 " + point);
    }

    private Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private String oneLine(String value) {
        return safe(value).replaceAll("[\\r\\n|]+", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record AcceptancePoint(int number, String title) {
        AcceptancePoint {
            title = safe(title);
        }
    }

    private record LedgerRow(int number, String title, String status, String evidence, String sourceEvidence) {
        LedgerRow {
            title = safe(title);
            status = safe(status);
            evidence = safe(evidence);
            sourceEvidence = safe(sourceEvidence);
        }
    }

    private record Diagnostic(String label, String evidence, String conclusion, String note) {
        Diagnostic {
            label = safe(label);
            evidence = safe(evidence);
            conclusion = safe(conclusion);
            note = safe(note);
        }
    }

    private record NextAction(String item, String reason, String action, String evidence) {
        NextAction {
            item = safe(item);
            reason = safe(reason);
            action = safe(action);
            evidence = safe(evidence);
        }
    }
}
