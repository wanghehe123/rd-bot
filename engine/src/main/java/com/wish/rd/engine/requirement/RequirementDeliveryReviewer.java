package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.AcceptanceResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.BrowserValidation;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 需求交付复核器。
 *
 * <p>该组件只复核统一发布视图，不调用外部 provider，也不修改代码或创建 PR。
 */
@Component
public class RequirementDeliveryReviewer {

    private final RequirementDeliveryPublicationViewAssembler publicationViewAssembler;

    /** 使用默认统一发布视图组装器。 */
    public RequirementDeliveryReviewer() {
        this(new RequirementDeliveryPublicationViewAssembler());
    }

    /**
     * 创建复核器。
     *
     * @param publicationViewAssembler 统一发布视图组装器
     */
    public RequirementDeliveryReviewer(RequirementDeliveryPublicationViewAssembler publicationViewAssembler) {
        this.publicationViewAssembler = publicationViewAssembler == null
                ? new RequirementDeliveryPublicationViewAssembler()
                : publicationViewAssembler;
    }

    /**
     * 兼容旧调用方：只在边界组装一次统一视图，然后执行同一套复核。
     *
     * @param taskId             RD 任务 ID
     * @param deliveryResultJson 多 Agent 聚合结果 JSON
     * @return 复核结果
     */
    public RequirementDeliveryReviewResult review(String taskId, String deliveryResultJson) {
        String normalizedTaskId = safe(taskId);
        if (normalizedTaskId.isBlank()) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "taskId must not be blank");
        }
        try {
            return review(normalizedTaskId, publicationViewAssembler.assemble(deliveryResultJson));
        } catch (IllegalArgumentException exception) {
            String reason = safe(exception.getMessage());
            if (reason.startsWith("deliveryResultJson:")) {
                reason = "invalid delivery result json: " + reason.substring("deliveryResultJson:".length()).strip();
            }
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, reason);
        }
    }

    /**
     * 复核已组装的统一发布视图。
     *
     * @param taskId RD 任务 ID
     * @param view   统一发布视图
     * @return 复核结果
     */
    public RequirementDeliveryReviewResult review(String taskId, RequirementDeliveryPublicationView view) {
        String normalizedTaskId = safe(taskId);
        if (normalizedTaskId.isBlank()) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "taskId must not be blank");
        }
        if (view == null) {
            return RequirementDeliveryReviewResult.rejected(normalizedTaskId, "publication view must not be null");
        }
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            if (!view.successfulRoles().contains(role.name())) {
                return rejected(normalizedTaskId, "required agent stage missing or failed: " + role.name());
            }
        }
        if (view.coding().changedFiles().isEmpty()) {
            return rejected(normalizedTaskId, "CODING_AGENT.changedFiles must not be empty");
        }
        if (view.coding().testCommands().isEmpty()) {
            return rejected(normalizedTaskId, "CODING_AGENT.testCommands must not be empty");
        }
        if (!isOneOf(view.coding().testStatus(), "PASSED", "SKIPPED")) {
            return rejected(normalizedTaskId, "CODING_AGENT.testStatus must be PASSED or SKIPPED");
        }
        if (!isOneOf(view.coding().riskLevel(), "LOW", "MEDIUM", "HIGH")) {
            return rejected(normalizedTaskId, "CODING_AGENT.riskLevel must be LOW, MEDIUM, or HIGH");
        }

        RequirementDeliveryPublicationView.QaDelivery qa = view.qa();
        if (!"PASSED".equalsIgnoreCase(qa.status())) {
            return rejected(normalizedTaskId, "QA_AGENT.status must be PASSED");
        }
        if (!"NONE".equalsIgnoreCase(qa.failureCategory())) {
            return rejected(normalizedTaskId, "QA_AGENT.failureCategory must be NONE");
        }
        if (!"NONE".equalsIgnoreCase(qa.retryRecommendation())) {
            return rejected(normalizedTaskId, "QA_AGENT.retryRecommendation must be NONE");
        }
        if (!RequirementPublicationEvidenceReference.isPersistentReference(
                qa.evidenceManifestArtifactId())) {
            return rejected(normalizedTaskId,
                    "QA_AGENT.evidenceManifestArtifactId must be a persistent non-loopback reference");
        }
        String browserError = browserError(qa.browserValidation());
        if (!browserError.isBlank()) {
            return rejected(normalizedTaskId, browserError);
        }
        if (qa.acceptanceResults().isEmpty()) {
            return rejected(normalizedTaskId, "QA_AGENT.acceptanceResults must not be empty");
        }
        boolean currentScopePresent = false;
        boolean regressionScopePresent = false;
        for (int index = 0; index < qa.acceptanceResults().size(); index++) {
            AcceptanceResult acceptance = qa.acceptanceResults().get(index);
            String prefix = "QA_AGENT.acceptanceResults[" + index + "]";
            String error = acceptanceError(prefix, acceptance);
            if (!error.isBlank()) {
                return rejected(normalizedTaskId, error);
            }
            currentScopePresent |= "CURRENT".equalsIgnoreCase(acceptance.scope());
            regressionScopePresent |= "REGRESSION".equalsIgnoreCase(acceptance.scope());
        }
        if (!currentScopePresent || !regressionScopePresent) {
            return rejected(normalizedTaskId,
                    "QA_AGENT.acceptanceResults must include CURRENT and REGRESSION scopes");
        }
        return RequirementDeliveryReviewResult.approved(normalizedTaskId);
    }

    /**
     * 兼容旧调用方；PR URL 不再参与复核，PR 发布发生在复核通过之后。
     */
    public RequirementDeliveryReviewResult review(String taskId, String pullRequestUrl, String deliveryResultJson) {
        return review(taskId, deliveryResultJson);
    }

    private String acceptanceError(String prefix, AcceptanceResult acceptance) {
        if (acceptance.criteria().isBlank()) return prefix + ".criteria must not be blank";
        if (!isOneOf(acceptance.scope(), "CURRENT", "REGRESSION")) {
            return prefix + ".scope must be CURRENT or REGRESSION";
        }
        if (acceptance.command().isBlank()) return prefix + ".command must not be blank";
        if (!"PASSED".equalsIgnoreCase(acceptance.status())) return prefix + ".status must be PASSED";
        if (acceptance.exitCode() == null || acceptance.exitCode() != 0L) {
            return prefix + ".exitCode must be 0";
        }
        if (acceptance.durationMillis() == null || acceptance.durationMillis() < 0L) {
            return prefix + ".durationMillis must be a non-negative integer";
        }
        if (!RequirementPublicationEvidenceReference.isPersistentReference(acceptance.logArtifactId())) {
            return prefix + ".logArtifactId must be a persistent non-loopback reference";
        }
        if (acceptance.evidenceArtifactIds().isEmpty()
                || acceptance.evidenceArtifactIds().stream().anyMatch(String::isBlank)) {
            return prefix + ".evidenceArtifactIds must contain non-empty references";
        }
        if (acceptance.evidenceArtifactIds().stream()
                .noneMatch(RequirementPublicationEvidenceReference::isPersistentReference)) {
            return prefix + ".evidenceArtifactIds must contain a persistent non-loopback reference";
        }
        return "";
    }

    private String browserError(BrowserValidation browser) {
        if (!browser.requiredReported()) return "QA_AGENT.browserValidation.required must be boolean";
        if (!browser.performedReported()) return "QA_AGENT.browserValidation.performed must be boolean";
        if (browser.decisionSource().isBlank()) return "QA_AGENT.browserValidation.decisionSource must not be blank";
        if (browser.browser().isBlank()) return "QA_AGENT.browserValidation.browser must not be blank";
        if (!browser.required()) return "";
        if (!browser.performed()) return "QA_AGENT.browserValidation.performed must be true when required";
        if (browser.baseUrl().isBlank()) return "QA_AGENT.browserValidation.baseUrl must not be blank when required";
        if (browser.viewports().isEmpty()) return "QA_AGENT.browserValidation.viewports must not be empty when required";
        return "";
    }

    private RequirementDeliveryReviewResult rejected(String taskId, String reason) {
        return RequirementDeliveryReviewResult.rejected(taskId, reason);
    }

    private boolean isOneOf(String value, String... allowed) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        for (String item : allowed) {
            if (item.equals(normalized)) return true;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
