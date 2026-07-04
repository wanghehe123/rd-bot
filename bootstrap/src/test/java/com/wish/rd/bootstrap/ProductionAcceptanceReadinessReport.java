package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes a side-effect-free readiness snapshot for deciding whether to start the long multi-agent smoke.
 */
final class ProductionAcceptanceReadinessReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final Path reportRoot;
    private final Clock clock;

    ProductionAcceptanceReadinessReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    Path write(Path evidenceRoot, Map<String, String> env) throws IOException {
        Path safeEvidenceRoot = evidenceRoot == null
                ? MultiAgentProductionAcceptanceReport.resolveReportRoot("qa-runs/multi-agent-production-acceptance")
                : evidenceRoot.toAbsolutePath().normalize();
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        EvidenceCheck providerPreflight = evidenceCheck(
                safeEvidenceRoot,
                "provider-preflight-production-acceptance-",
                "providerPreflightEvidenceValidated",
                List.of("PASSED_PROVIDER_PREFLIGHT_SMOKE"),
                "Provider preflight PASSED sidecar"
        );
        EvidenceCheck feishuAlert = evidenceCheck(
                safeEvidenceRoot,
                "feishu-alert-production-acceptance-",
                "feishuAlertEvidenceValidated",
                List.of("PASSED_FEISHU_ALERT_SMOKE"),
                "Feishu alert PASSED sidecar"
        );
        EvidenceCheck skillPolicy = evidenceCheck(
                safeEvidenceRoot,
                "skill-production-acceptance-",
                "skillPolicyEvidenceValidated",
                List.of("", "PASSED_SKILL_POLICY_SMOKE"),
                "Skill policy PASSED sidecar"
        );
        EvidenceCheck githubRemote = evidenceCheck(
                safeEvidenceRoot,
                "github-pr-remote-evidence-production-acceptance-",
                "githubPrRemoteEvidenceValidated",
                List.of("PASSED_GITHUB_PR_REMOTE_EVIDENCE_SMOKE"),
                "GitHub PR remote PASSED sidecar"
        );
        List<RequirementCheck> coreEnvChecks = coreEnvChecks(safeEnv);
        List<RequirementCheck> refreshEnvChecks = refreshEnvChecks(safeEnv);
        List<RequirementCheck> envChecks = concat(coreEnvChecks, refreshEnvChecks);
        List<EvidenceCheck> evidenceChecks = List.of(providerPreflight, feishuAlert, skillPolicy, githubRemote);
        List<DiagnosticCheck> diagnosticChecks = List.of(diagnosticCheck(
                safeEvidenceRoot,
                "minimax-mmx-cli-real-probe-",
                List.of("PASSED_MINIMAX_MMX_CLI_SMOKE"),
                "MiniMax mmx CLI probe"
        ));
        List<ProviderFailureCheck> providerFailures = providerFailures(safeEvidenceRoot, providerPreflight);
        boolean readyForFullSmoke = evidenceChecks.stream().allMatch(EvidenceCheck::ready)
                && coreEnvChecks.stream().allMatch(RequirementCheck::ready);

        String conclusion = readyForFullSmoke ? "READY_FOR_FULL_SMOKE" : "NOT_READY_FOR_FULL_SMOKE";
        StringBuilder markdown = new StringBuilder()
                .append("# RD-Bot 生产验收 Readiness 快照\n\n")
                .append("- 生成时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该快照只检查能否进入长链路 smoke，不把任何未运行项记为通过；")
                .append("只记录 env 名和证据路径，不记录 secret 值。\n\n");
        markdown.append("## 关键证据\n\n");
        markdown.append("| item | ready | evidence | conclusion |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        for (EvidenceCheck check : evidenceChecks) {
            markdown.append("| ").append(check.label())
                    .append(" | ").append(check.ready())
                    .append(" | ").append(check.evidence())
                    .append(" | ").append(check.conclusion())
                    .append(" |\n");
        }
        markdown.append("\n## 环境变量 Presence\n\n");
        markdown.append("| item | ready | env |\n");
        markdown.append("| --- | --- | --- |\n");
        for (RequirementCheck check : envChecks) {
            markdown.append("| ").append(check.label())
                    .append(" | ").append(check.ready())
                    .append(" | ").append(check.envName())
                    .append(" |\n");
        }
        if (!providerFailures.isEmpty()) {
            markdown.append("\n## Provider Preflight Failure Reasons\n\n");
            markdown.append("| provider | httpStatus | failureReason |\n");
            markdown.append("| --- | --- | --- |\n");
            for (ProviderFailureCheck failure : providerFailures) {
                markdown.append("| ").append(failure.name())
                        .append(" | ").append(failure.httpStatus())
                        .append(" | ").append(failure.failureReason())
                        .append(" |\n");
            }
        }
        markdown.append("\n## 本机诊断\n\n");
        markdown.append("| item | ready | evidence | conclusion |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        for (DiagnosticCheck check : diagnosticChecks) {
            markdown.append("| ").append(check.label())
                    .append(" | ").append(check.ready())
                    .append(" | ").append(check.evidence())
                    .append(" | ").append(check.conclusion())
                    .append(" |\n");
        }
        List<String> missing = missing(evidenceChecks, coreEnvChecks);
        markdown.append("\n## 缺口\n\n");
        if (missing.isEmpty()) {
            markdown.append("- 无。可以进入完整 smoke；完整验收是否通过仍以 smoke 报告 matrix 为准。\n");
        } else {
            missing.forEach(item -> markdown.append("- ").append(item).append('\n'));
        }
        Path reportPath = writeMarkdown(markdown);
        writeJson(
                reportPath,
                conclusion,
                readyForFullSmoke,
                providerPreflight,
                evidenceChecks,
                envChecks,
                diagnosticChecks,
                providerFailures,
                missing
        );
        return reportPath;
    }

    private static List<RequirementCheck> coreEnvChecks(Map<String, String> env) {
        return List.of(
                envCheck("LongCat provider secret", "LONGCAT_API_KEY", env),
                envCheck("MiniMax provider secret", "MINIMAX_API_KEY", env),
                envCheck("Secret scan needles", "RD_BOT_SECRET_SCAN_NEEDLES", env)
        );
    }

    private static List<RequirementCheck> refreshEnvChecks(Map<String, String> env) {
        return List.of(
                envAnyCheck("GitHub PAT credential (PAT refresh only)", List.of("GITHUB_PAT", "GH_TOKEN"), env),
                envCheck("Feishu app id (refresh sidecar)", "FEISHU_APP_ID", env),
                envCheck("Feishu app secret (refresh sidecar)", "FEISHU_APP_SECRET", env),
                envCheck("Feishu alert chat (refresh sidecar)", "FEISHU_IM_ALERT_CHAT_ID", env)
        );
    }

    private static List<RequirementCheck> concat(
            List<RequirementCheck> left,
            List<RequirementCheck> right
    ) {
        java.util.ArrayList<RequirementCheck> combined = new java.util.ArrayList<>();
        combined.addAll(left);
        combined.addAll(right);
        return List.copyOf(combined);
    }

    private static RequirementCheck envCheck(String label, String envName, Map<String, String> env) {
        return new RequirementCheck(label, envName, !safe(env.get(envName)).isBlank());
    }

    private static RequirementCheck envAnyCheck(String label, List<String> envNames, Map<String, String> env) {
        boolean ready = envNames.stream().anyMatch(name -> !safe(env.get(name)).isBlank());
        return new RequirementCheck(label, String.join("|", envNames), ready);
    }

    private static EvidenceCheck evidenceCheck(
            Path evidenceRoot,
            String prefix,
            String validationField,
            List<String> acceptedConclusions,
            String label
    ) throws IOException {
        Optional<Path> latestPassed = latestPassed(evidenceRoot, prefix, validationField, acceptedConclusions);
        if (latestPassed.isPresent()) {
            Path path = latestPassed.get();
            return new EvidenceCheck(label, true, evidenceRoot.relativize(path).toString(), jsonConclusion(path));
        }
        Optional<Path> latest = latest(evidenceRoot, prefix);
        if (latest.isPresent()) {
            Path path = latest.get();
            return new EvidenceCheck(label, false, evidenceRoot.relativize(path).toString(), jsonConclusion(path));
        }
        return new EvidenceCheck(
                label,
                false,
                "MISSING",
                "NOT_RUN"
        );
    }

    private static DiagnosticCheck diagnosticCheck(
            Path evidenceRoot,
            String prefix,
            List<String> acceptedConclusions,
            String label
    ) throws IOException {
        Optional<Path> latest = latest(evidenceRoot, prefix);
        if (latest.isEmpty()) {
            return new DiagnosticCheck(label, false, "MISSING", "NOT_RUN");
        }
        String conclusion = jsonConclusion(latest.get());
        return new DiagnosticCheck(
                label,
                acceptedConclusions.contains(conclusion),
                evidenceRoot.relativize(latest.get()).toString(),
                conclusion.isBlank() ? "UNKNOWN" : conclusion
        );
    }

    private static Optional<Path> latestPassed(
            Path evidenceRoot,
            String prefix,
            String validationField,
            List<String> acceptedConclusions
    ) throws IOException {
        if (!Files.isDirectory(evidenceRoot)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> paths = Files.list(evidenceRoot)) {
            return paths.filter(path -> matchesPrefix(path, prefix))
                    .filter(path -> jsonFieldTrue(path, validationField, acceptedConclusions))
                    .max(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        }
    }

    private static Optional<Path> latest(Path evidenceRoot, String prefix) throws IOException {
        if (!Files.isDirectory(evidenceRoot)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> paths = Files.list(evidenceRoot)) {
            return paths.filter(path -> matchesPrefix(path, prefix))
                    .max(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        }
    }

    private static boolean matchesPrefix(Path path, String prefix) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith(prefix) && fileName.endsWith(".json");
    }

    private static boolean jsonFieldTrue(Path path, String validationField, List<String> acceptedConclusions) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            return acceptedConclusions.contains(root.path("conclusion").asText(""))
                    && root.path(validationField).asBoolean(false);
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private static String jsonConclusion(Path path) {
        try {
            return OBJECT_MAPPER.readTree(path.toFile()).path("conclusion").asText("");
        } catch (RuntimeException | IOException ignored) {
            return "UNREADABLE";
        }
    }

    private static List<ProviderFailureCheck> providerFailures(Path evidenceRoot, EvidenceCheck providerPreflight) {
        if (providerPreflight.ready() || providerPreflight.evidence().isBlank()
                || "MISSING".equals(providerPreflight.evidence())) {
            return List.of();
        }
        try {
            Path evidencePath = evidenceRoot.resolve(providerPreflight.evidence()).normalize();
            JsonNode providers = OBJECT_MAPPER.readTree(evidencePath.toFile()).path("providers");
            if (!providers.isArray()) {
                return List.of();
            }
            java.util.ArrayList<ProviderFailureCheck> failures = new java.util.ArrayList<>();
            for (JsonNode provider : providers) {
                String failureReason = safe(provider.path("failureReason").asText(""));
                if (!failureReason.isBlank()) {
                    failures.add(new ProviderFailureCheck(
                            provider.path("name").asText(""),
                            provider.path("httpStatus").asInt(-1),
                            failureReason
                    ));
                }
            }
            return List.copyOf(failures);
        } catch (RuntimeException | IOException ignored) {
            return List.of();
        }
    }

    private static List<String> missing(
            List<EvidenceCheck> evidenceChecks,
            List<RequirementCheck> envChecks
    ) {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        evidenceChecks.stream()
                .filter(check -> !check.ready())
                .map(EvidenceCheck::label)
                .forEach(missing::add);
        envChecks.stream()
                .filter(check -> !check.ready())
                .map(check -> check.label() + " (" + check.envName() + ")")
                .forEach(missing::add);
        return List.copyOf(missing);
    }

    private Path writeMarkdown(StringBuilder markdown) throws IOException {
        Files.createDirectories(reportRoot);
        Path reportPath = reportRoot.resolve("multi-agent-production-readiness-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeJson(
            Path markdownReportPath,
            String conclusion,
            boolean readyForFullSmoke,
            EvidenceCheck providerPreflight,
            List<EvidenceCheck> evidenceChecks,
            List<RequirementCheck> envChecks,
            List<DiagnosticCheck> diagnosticChecks,
            List<ProviderFailureCheck> providerFailures,
            List<String> missing
    ) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", conclusion);
        json.put("generatedAt", clock.instant().toString());
        json.put("readyForFullSmoke", readyForFullSmoke);
        json.put("providerPreflightReady", providerPreflight.ready());
        json.put("evidence", evidenceChecks.stream().map(ProductionAcceptanceReadinessReport::evidenceJson).toList());
        json.put("env", envChecks.stream().map(ProductionAcceptanceReadinessReport::envJson).toList());
        json.put("diagnostics",
                diagnosticChecks.stream().map(ProductionAcceptanceReadinessReport::diagnosticJson).toList());
        json.put("providerFailures",
                providerFailures.stream().map(ProductionAcceptanceReadinessReport::providerFailureJson).toList());
        json.put("missing", missing);
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private static Map<String, Object> evidenceJson(EvidenceCheck check) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("label", check.label());
        json.put("ready", check.ready());
        json.put("evidence", check.evidence());
        json.put("conclusion", check.conclusion());
        return json;
    }

    private static Map<String, Object> providerFailureJson(ProviderFailureCheck check) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", check.name());
        json.put("httpStatus", check.httpStatus());
        json.put("failureReason", check.failureReason());
        return json;
    }

    private static Map<String, Object> envJson(RequirementCheck check) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("label", check.label());
        json.put("ready", check.ready());
        json.put("envName", check.envName());
        return json;
    }

    private static Map<String, Object> diagnosticJson(DiagnosticCheck check) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("label", check.label());
        json.put("ready", check.ready());
        json.put("evidence", check.evidence());
        json.put("conclusion", check.conclusion());
        return json;
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record EvidenceCheck(String label, boolean ready, String evidence, String conclusion) {
        EvidenceCheck {
            label = safe(label);
            evidence = safe(evidence);
            conclusion = safe(conclusion);
        }
    }

    private record RequirementCheck(String label, String envName, boolean ready) {
        RequirementCheck {
            label = safe(label);
            envName = safe(envName);
        }
    }

    private record DiagnosticCheck(String label, boolean ready, String evidence, String conclusion) {
        DiagnosticCheck {
            label = safe(label);
            evidence = safe(evidence);
            conclusion = safe(conclusion);
        }
    }

    private record ProviderFailureCheck(String name, int httpStatus, String failureReason) {
        ProviderFailureCheck {
            name = safe(name);
            failureReason = safe(failureReason);
        }
    }
}
