package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes provider preflight production smoke evidence.
 */
final class ProviderPreflightProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final Path reportRoot;
    private final Clock clock;

    ProviderPreflightProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static ProviderPreflightProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.provider.preflight.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new ProviderPreflightProductionAcceptanceReport(
                MultiAgentProductionAcceptanceReport.resolveReportRoot(reportDir),
                Clock.systemUTC()
        );
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        StringBuilder markdown = header("SKIPPED_PROVIDER_PREFLIGHT_SMOKE", List.of());
        markdown.append("""

                ## 未运行原因

                Provider preflight 参数不完整，本次没有调用真实模型 provider。
                缺少的条件只记录属性名或环境变量名，不记录任何 secret 值。

                """);
        for (String missing : missingRequirements == null ? List.<String>of() : missingRequirements) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendRetryCommand(markdown);
        Path reportPath = write(markdown);
        writeSkippedEvidenceJson(reportPath, missingRequirements);
        return reportPath;
    }

    Path writePassed(ProviderPreflightEvidence evidence) throws IOException {
        requirePassedEvidence(evidence);
        StringBuilder markdown = header("PASSED_PROVIDER_PREFLIGHT_SMOKE", evidence.secretNeedles());
        appendEvidence(markdown, evidence, "PASSED");
        Path reportPath = write(markdown);
        writePassedEvidenceJson(reportPath, evidence);
        return reportPath;
    }

    Path writeFailed(ProviderPreflightEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_PROVIDER_PREFLIGHT_SMOKE", evidence.secretNeedles());
        markdown.append("""

                ## 失败信息

                """);
        markdown.append("- errorType：").append(failure == null ? "" : failure.getClass().getSimpleName()).append('\n');
        markdown.append("- message：").append(redact(
                failure == null ? "" : failure.getMessage(),
                evidence.secretNeedles()
        )).append('\n');
        appendEvidence(markdown, evidence, "FAILED");
        Path reportPath = write(markdown);
        writeFailedEvidenceJson(reportPath, evidence, failure);
        return reportPath;
    }

    private StringBuilder header(String conclusion, List<String> secretNeedles) {
        return new StringBuilder()
                .append("# RD-Bot Provider Preflight 生产验收报告\n\n")
                .append("- 验收时间：").append(clock.instant()).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告只证明 provider 协议、adapter 接管、真实凭据和真实 HTTP 探活；")
                .append("不能替代完整多 Agent 生产验收。\n")
                .append("- secretNeedleCount：").append(secretNeedles == null ? 0 : secretNeedles.size()).append('\n');
    }

    private void appendRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                命令模板只记录属性名、env 名和占位符，不记录 provider secret 值。

                ```bash
                ./mvnw -pl bootstrap -am -Dtest=ProviderPreflightRealSmokeTest \\
                  -Drd.integration.provider-preflight.enabled=true \\
                  -Drd.provider.preflight.smoke.production-evidence=true \\
                  -Drd.provider.preflight.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.provider.preflight.smoke.environment-id=<production-environment-id> \\
                  -Drd.provider.preflight.smoke.executed-by=<operator> \\
                  -Drd.provider.preflight.smoke.expected-provider-count=2 \\
                  -Drd.provider.preflight.smoke.providers=long-cat,minimax \\
                  -Drd.provider.preflight.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, ProviderPreflightEvidence evidence, String status) {
        markdown.append("""

                ## Provider Preflight 证据

                """);
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- expectedProviderCount：").append(evidence.expectedProviderCount()).append('\n');
        markdown.append("- providerPreflightEvidenceValidated：").append(providerPreflightEvidenceValidated(evidence))
                .append('\n');
        markdown.append("- successfulProviderCount：").append(successfulProviderCount(evidence)).append('\n');
        markdown.append("\n| provider | protocol | adapter | baseUrl | apiKeyEnv | model | httpStatus | success | failureReason | failureCategory | responseFingerprint |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ProviderProbeEvidence probe : evidence.probes()) {
            markdown.append("| ").append(oneLine(probe.name()))
                    .append(" | ").append(oneLine(probe.protocol()))
                    .append(" | ").append(oneLine(probe.adapterName()))
                    .append(" | ").append(oneLine(probe.baseUrl()))
                    .append(" | ").append(oneLine(probe.apiKeyEnv()))
                    .append(" | ").append(oneLine(probe.model()))
                    .append(" | ").append(probe.httpStatus())
                    .append(" | ").append(probe.success())
                    .append(" | ").append(providerFailureReason(probe))
                    .append(" | ").append(oneLine(probe.failureCategory()))
                    .append(" | ").append(oneLine(probe.responseFingerprint()))
                    .append(" |\n");
        }
        markdown.append("\n## 验收点矩阵\n\n");
        markdown.append("| # | 验收点 | 结论 | 证据/缺口 |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        markdown.append("| 5 | 多 provider 降级重试真实生效 | ")
                .append(providerPreflightEvidenceValidated(evidence) ? "PREFLIGHT_PASSED" : status)
                .append(" | provider 前置探活成功数=")
                .append(successfulProviderCount(evidence))
                .append("；完整降级仍需多 Agent 总 smoke 的 providerAttemptsJson 证明。 |\n");
        markdown.append("| 15 | 生产真实测试结论要求 | NOT_RUN | Provider preflight 是总 smoke 前置门禁，不能单独判定 #15 通过。 |\n");
    }

    private void requirePassedEvidence(ProviderPreflightEvidence evidence) {
        if (!providerPreflightEvidenceValidated(evidence)) {
            throw new IllegalArgumentException("provider preflight evidence must cover all expected providers");
        }
    }

    private static boolean providerPreflightEvidenceValidated(ProviderPreflightEvidence evidence) {
        return evidence != null
                && evidence.expectedProviderCount() >= 2
                && evidence.probes().stream().filter(ProviderProbeEvidence::success).count()
                >= evidence.expectedProviderCount()
                && evidence.probes().stream().map(ProviderProbeEvidence::adapterName).noneMatch(String::isBlank);
    }

    private static long successfulProviderCount(ProviderPreflightEvidence evidence) {
        return evidence == null ? 0 : evidence.probes().stream().filter(ProviderProbeEvidence::success).count();
    }

    private Path write(StringBuilder markdown) throws IOException {
        Files.createDirectories(reportRoot);
        Path reportPath = reportRoot.resolve("provider-preflight-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writePassedEvidenceJson(Path markdownReportPath, ProviderPreflightEvidence evidence)
            throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", "PASSED_PROVIDER_PREFLIGHT_SMOKE");
        json.put("generatedAt", clock.instant().toString());
        appendEvidenceJson(json, evidence);
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private void writeSkippedEvidenceJson(Path markdownReportPath, List<String> missingRequirements)
            throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", "SKIPPED_PROVIDER_PREFLIGHT_SMOKE");
        json.put("generatedAt", clock.instant().toString());
        json.put("providerPreflightEvidenceValidated", false);
        json.put("successfulProviderCount", 0);
        json.put("missingRequirements", missingRequirements == null ? List.of() : List.copyOf(missingRequirements));
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private void writeFailedEvidenceJson(
            Path markdownReportPath,
            ProviderPreflightEvidence evidence,
            Throwable failure
    ) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", "FAILED_PROVIDER_PREFLIGHT_SMOKE");
        json.put("generatedAt", clock.instant().toString());
        json.put("errorType", failure == null ? "" : failure.getClass().getSimpleName());
        json.put("message", redact(failure == null ? "" : failure.getMessage(), evidence.secretNeedles()));
        appendEvidenceJson(json, evidence);
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private void appendEvidenceJson(Map<String, Object> json, ProviderPreflightEvidence evidence) {
        json.put("rdBotVersion", evidence.rdBotVersion());
        json.put("environmentId", evidence.environmentId());
        json.put("executedBy", evidence.executedBy());
        json.put("expectedProviderCount", evidence.expectedProviderCount());
        json.put("providerPreflightEvidenceValidated", providerPreflightEvidenceValidated(evidence));
        json.put("successfulProviderCount", successfulProviderCount(evidence));
        json.put("providers", evidence.probes().stream()
                .map(probe -> providerJson(probe, evidence.secretNeedles()))
                .toList());
    }

    private Map<String, Object> providerJson(ProviderProbeEvidence probe, List<String> secretNeedles) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("name", probe.name());
        provider.put("protocol", probe.protocol());
        provider.put("adapterName", probe.adapterName());
        provider.put("baseUrl", probe.baseUrl());
        provider.put("apiKeyEnv", probe.apiKeyEnv());
        provider.put("model", probe.model());
        provider.put("httpStatus", probe.httpStatus());
        provider.put("success", probe.success());
        provider.put("responseFingerprint", probe.responseFingerprint());
        provider.put("failureCategory", probe.failureCategory());
        provider.put("failureReason", providerFailureReason(probe));
        provider.put("message", redact(probe.message(), secretNeedles));
        return provider;
    }

    private static String providerFailureReason(ProviderProbeEvidence probe) {
        if (probe == null || probe.success()) {
            return "";
        }
        String message = oneLine(probe.message()).toLowerCase(java.util.Locale.ROOT);
        if (probe.httpStatus() == 401
                || probe.httpStatus() == 403
                || message.contains("invalid_api_key")
                || message.contains("authentication")) {
            return "PROVIDER_AUTHENTICATION_FAILED";
        }
        if (probe.httpStatus() == 429
                || message.contains("rate_limit")
                || message.contains("quota")
                || message.contains("token plan")
                || message.contains("用量上限")) {
            return "PROVIDER_QUOTA_OR_RATE_LIMIT";
        }
        if (probe.httpStatus() >= 500) {
            return "PROVIDER_UPSTREAM_UNAVAILABLE";
        }
        if (probe.httpStatus() >= 400) {
            return "PROVIDER_HTTP_FAILED";
        }
        return probe.failureCategory().isBlank() ? "PROVIDER_SCHEMA_FAILED" : probe.failureCategory();
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static String redact(String value, List<String> secretNeedles) {
        String redacted = value == null ? "" : value;
        for (String needle : secretNeedles == null ? List.<String>of() : secretNeedles) {
            if (needle != null && !needle.isBlank()) {
                redacted = redacted.replace(needle, "***");
            }
        }
        return oneLine(redacted);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    record ProviderPreflightEvidence(
            String rdBotVersion,
            String environmentId,
            String executedBy,
            int expectedProviderCount,
            List<ProviderProbeEvidence> probes,
            List<String> secretNeedles
    ) {

        ProviderPreflightEvidence {
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            probes = probes == null ? List.of() : List.copyOf(probes);
            secretNeedles = secretNeedles == null ? List.of() : List.copyOf(secretNeedles);
        }
    }

    record ProviderProbeEvidence(
            String name,
            String protocol,
            String adapterName,
            String baseUrl,
            String apiKeyEnv,
            String model,
            int httpStatus,
            boolean success,
            String responseFingerprint,
            String failureCategory,
            String message
    ) {

        ProviderProbeEvidence {
            name = oneLine(name);
            protocol = oneLine(protocol);
            adapterName = oneLine(adapterName);
            baseUrl = oneLine(baseUrl);
            apiKeyEnv = oneLine(apiKeyEnv);
            model = oneLine(model);
            responseFingerprint = oneLine(responseFingerprint);
            failureCategory = oneLine(failureCategory);
            message = oneLine(message);
        }
    }
}
