package com.wish.rd.engine.requirement.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * 需求交付在审核和 PR 发布之间共享的不可变事实视图。
 *
 * @param schemaVersion      视图结构版本
 * @param summary            交付摘要
 * @param agentNarrative     Coding Agent 提供的候选叙述
 * @param coding             Coding 交付事实
 * @param qa                 QA 交付事实
 * @param deliveryReview     确定性审核结论
 * @param evidenceReferences 持久证据引用
 * @param successfulRoles    已成功的角色名称
 */
public record RequirementDeliveryPublicationView(
        int schemaVersion,
        String summary,
        String agentNarrative,
        CodingDelivery coding,
        QaDelivery qa,
        DeliveryReview deliveryReview,
        List<String> evidenceReferences,
        List<String> successfulRoles
) {

    /** 首版发布视图结构版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RequirementDeliveryPublicationView {
        summary = safe(summary);
        agentNarrative = safe(agentNarrative);
        coding = coding == null ? CodingDelivery.empty() : coding;
        qa = qa == null ? QaDelivery.empty() : qa;
        deliveryReview = deliveryReview == null ? DeliveryReview.empty() : deliveryReview;
        evidenceReferences = copy(evidenceReferences);
        successfulRoles = copy(successfulRoles);
    }

    /**
     * 返回附加审核结论的新视图。
     *
     * @param review 确定性审核结论
     * @return 新发布视图
     */
    public RequirementDeliveryPublicationView withDeliveryReview(DeliveryReview review) {
        return new RequirementDeliveryPublicationView(
                schemaVersion, summary, agentNarrative, coding, qa, review,
                evidenceReferences, successfulRoles
        );
    }

    /**
     * 计算审核所绑定的规范化发布事实指纹；审核结论本身不参与计算。
     *
     * @return 带算法前缀的 SHA-256 指纹
     */
    public String publicationFactsHash() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, Integer.toString(schemaVersion));
        append(canonical, summary);
        append(canonical, agentNarrative);
        appendSorted(canonical, coding.changedFiles());
        appendSorted(canonical, coding.testCommands());
        append(canonical, coding.testStatus());
        append(canonical, coding.riskLevel());
        append(canonical, qa.status());
        append(canonical, qa.summary());
        append(canonical, qa.failureCategory());
        append(canonical, qa.retryRecommendation());
        BrowserValidation browser = qa.browserValidation();
        append(canonical, Boolean.toString(browser.required()));
        append(canonical, Boolean.toString(browser.performed()));
        append(canonical, Boolean.toString(browser.requiredReported()));
        append(canonical, Boolean.toString(browser.performedReported()));
        append(canonical, browser.decisionSource());
        append(canonical, browser.baseUrl());
        append(canonical, browser.browser());
        appendSorted(canonical, browser.viewports());
        append(canonical, Integer.toString(qa.acceptanceResults().size()));
        for (AcceptanceResult result : qa.acceptanceResults()) {
            append(canonical, result.criteria());
            append(canonical, result.scope());
            append(canonical, result.command());
            append(canonical, result.status());
            append(canonical, result.exitCode() == null ? "" : Long.toString(result.exitCode()));
            append(canonical, result.durationMillis() == null ? "" : Long.toString(result.durationMillis()));
            append(canonical, result.logArtifactId());
            appendList(canonical, result.evidenceArtifactIds());
        }
        append(canonical, qa.evidenceManifestArtifactId());
        appendSorted(canonical, evidenceReferences);
        appendSorted(canonical, successfulRoles);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Coding 阶段的可发布事实。 */
    public record CodingDelivery(
            List<String> changedFiles,
            List<String> testCommands,
            String testStatus,
            String riskLevel
    ) {
        public CodingDelivery {
            changedFiles = copy(changedFiles);
            testCommands = copy(testCommands);
            testStatus = safe(testStatus);
            riskLevel = safe(riskLevel);
        }

        /** @return 空 Coding 事实 */
        public static CodingDelivery empty() {
            return new CodingDelivery(List.of(), List.of(), "", "");
        }
    }

    /** QA 阶段的可发布事实。 */
    public record QaDelivery(
            String status,
            String summary,
            String failureCategory,
            String retryRecommendation,
            BrowserValidation browserValidation,
            List<AcceptanceResult> acceptanceResults,
            String evidenceManifestArtifactId
    ) {
        public QaDelivery {
            status = safe(status);
            summary = safe(summary);
            failureCategory = safe(failureCategory);
            retryRecommendation = safe(retryRecommendation);
            browserValidation = browserValidation == null ? BrowserValidation.empty() : browserValidation;
            acceptanceResults = acceptanceResults == null ? List.of() : List.copyOf(acceptanceResults);
            evidenceManifestArtifactId = safe(evidenceManifestArtifactId);
        }

        /** @return 空 QA 事实 */
        public static QaDelivery empty() {
            return new QaDelivery("", "", "", "", BrowserValidation.empty(), List.of(), "");
        }
    }

    /** QA 浏览器验收决策。 */
    public record BrowserValidation(
            boolean required,
            boolean performed,
            boolean requiredReported,
            boolean performedReported,
            String decisionSource,
            String baseUrl,
            String browser,
            List<String> viewports
    ) {
        public BrowserValidation {
            decisionSource = safe(decisionSource);
            baseUrl = safe(baseUrl);
            browser = safe(browser);
            viewports = copy(viewports);
        }

        /** @return 空浏览器验收决策 */
        public static BrowserValidation empty() {
            return new BrowserValidation(false, false, false, false, "", "", "", List.of());
        }
    }

    /** 单条 QA 验收结果。 */
    public record AcceptanceResult(
            String criteria,
            String scope,
            String command,
            String status,
            Long exitCode,
            Long durationMillis,
            String logArtifactId,
            List<String> evidenceArtifactIds
    ) {
        public AcceptanceResult {
            criteria = safe(criteria);
            scope = safe(scope);
            command = safe(command);
            status = safe(status);
            logArtifactId = safe(logArtifactId);
            evidenceArtifactIds = copy(evidenceArtifactIds);
        }
    }

    /** 确定性审核结论。 */
    public record DeliveryReview(
            String taskId,
            String reviewer,
            boolean approved,
            String reason,
            String factsHash
    ) {
        public DeliveryReview {
            taskId = safe(taskId);
            reviewer = safe(reviewer);
            reason = safe(reason);
            factsHash = safe(factsHash);
        }

        /** 源码兼容构造器；空事实指纹不能通过发布校验。 */
        public DeliveryReview(String taskId, String reviewer, boolean approved, String reason) {
            this(taskId, reviewer, approved, reason, "");
        }

        /** @return 空审核结论 */
        public static DeliveryReview empty() {
            return new DeliveryReview("", "", false, "", "");
        }
    }

    private static void appendSorted(StringBuilder target, Collection<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        if (values != null) {
            values.stream().map(RequirementDeliveryPublicationView::safe)
                    .filter(value -> !value.isBlank()).forEach(sorted::add);
        }
        appendList(target, new ArrayList<>(sorted));
    }

    private static void appendList(StringBuilder target, Collection<String> values) {
        List<String> normalized = values == null
                ? List.of()
                : values.stream().map(RequirementDeliveryPublicationView::safe).toList();
        append(target, Integer.toString(normalized.size()));
        normalized.forEach(value -> append(target, value));
    }

    private static void append(StringBuilder target, String value) {
        String normalized = safe(value);
        target.append(normalized.length()).append(':').append(normalized);
    }

    private static List<String> copy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(RequirementDeliveryPublicationView::safe).toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
