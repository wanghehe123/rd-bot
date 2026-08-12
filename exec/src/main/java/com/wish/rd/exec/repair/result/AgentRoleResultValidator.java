package com.wish.rd.exec.repair.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.result.model.StructuredResultValidation;
import com.wish.rd.rag.project.agent.model.ContextProtocolVersion;
import com.wish.rd.rag.project.agent.model.FactFreshnessEvaluator;
import com.wish.rd.rag.project.agent.model.RoleExecutionFactsValidator;

/**
 * 多角色 Agent 产物协议校验器。
 *
 * <p>用于交付复核前检查需求评审、方案、编码和 QA 输出是否具备可审计字段；
 * 编码角色复用现有 {@link StructuredResultValidator}。
 */
public final class AgentRoleResultValidator {

    private static final Set<String> REVIEW_DECISIONS = Set.of("APPROVED", "NEED_INFO", "REJECTED");
    private static final Set<String> FEASIBILITY_VALUES = Set.of("CAN_DO", "NEED_INFO", "UNSAFE");
    private static final Set<String> BUDGET_CONFIDENCE_VALUES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> QA_STATUSES = Set.of("PASSED", "FAILED", "SKIPPED");
    private static final Set<String> QA_FAILURE_CATEGORIES = Set.of(
            "NONE",
            "PRODUCT_DEFECT",
            "REGRESSION",
            "ENVIRONMENT",
            "AUTHENTICATION",
            "QA_INFRASTRUCTURE",
            "REQUIREMENT_AMBIGUITY",
            "FLAKY"
    );
    private static final Set<String> QA_RETRY_RECOMMENDATIONS = Set.of("NONE", "CODING_AGENT", "HUMAN");
    private static final Set<String> QA_SCOPES = Set.of("CURRENT", "REGRESSION");
    private static final Set<String> QA_DECISION_SOURCES = Set.of(
            "TASK_OVERRIDE",
            "PROJECT_PROFILE",
            "REPOSITORY_CONFIG",
            "AUTO_DETECTION",
            "NOT_APPLICABLE",
            "DOCS_ONLY"
    );
    private static final Set<String> HOST_ASSERTION_RESULT_FIELDS = Set.of(
            "scope",
            "contentHash",
            "evidenceArtifactIds"
    );
    private static final Pattern SHA256_CONTENT_HASH = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");

    private final ObjectMapper objectMapper;
    private final StructuredResultValidator structuredResultValidator;

    /**
     * 使用默认 JSON 解析器创建校验器。
     */
    public AgentRoleResultValidator() {
        this(new ObjectMapper(), new StructuredResultValidator());
    }

    /**
     * 使用指定依赖创建校验器。
     *
     * @param objectMapper              JSON 解析器
     * @param structuredResultValidator 编码结果校验器
     */
    public AgentRoleResultValidator(
            ObjectMapper objectMapper,
            StructuredResultValidator structuredResultValidator
    ) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
        this.structuredResultValidator = structuredResultValidator == null
                ? new StructuredResultValidator()
                : structuredResultValidator;
    }

    /**
     * 校验指定角色的 JSON 产物。
     *
     * @param role Agent 角色
     * @param json 产物 JSON
     * @return 校验结果
     */
    public AgentRoleResultValidation validate(String role, String json) {
        return validate(role, json, ContextProtocolVersion.LEGACY_ENVIRONMENT_NOTES.name(), null);
    }

    /**
     * 校验指定角色的 JSON 产物，并按冻结 context protocol 校验 facts。
     */
    public AgentRoleResultValidation validate(
            String role,
            String json,
            String contextProtocolVersion,
            FactFreshnessEvaluator.FreshnessContext freshnessContext
    ) {
        String normalizedRole = normalizeRole(role);
        if ("CODING_AGENT".equals(normalizedRole)) {
            StructuredResultValidation validation = structuredResultValidator.validate(
                    json,
                    contextProtocolVersion,
                    freshnessContext
            );
            return new AgentRoleResultValidation(validation.valid(), validation.errors());
        }
        JsonNode root = parseObject(json);
        if (root == null) {
            return new AgentRoleResultValidation(false, List.of("result json root must be an object"));
        }
        List<String> errors = new ArrayList<>(switch (normalizedRole) {
            case "REQUIREMENT_REVIEWER" -> validateRequirementReview(root);
            case "SOLUTION_ARCHITECT" -> validateSolutionPlan(root);
            case "QA_AGENT" -> validateQaReport(root);
            default -> List.of("unsupported agent role: " + normalizedRole);
        });
        errors.addAll(RoleExecutionFactsValidator.validateFactsProtocol(
                root,
                ContextProtocolVersion.parse(contextProtocolVersion),
                freshnessContext
        ));
        return new AgentRoleResultValidation(errors.isEmpty(), List.copyOf(errors));
    }

    private JsonNode parseObject(String json) {
        try {
            JsonNode root = objectMapper.readTree(json == null ? "" : json);
            if (root == null || !root.isObject()) {
                return null;
            }
            return root;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private List<String> validateRequirementReview(JsonNode root) {
        List<String> errors = new ArrayList<>();
        validateEnum(root, "decision", REVIEW_DECISIONS, errors);
        validateEnum(root, "feasibility", FEASIBILITY_VALUES, errors);
        validateArray(root, "missingInformation", false, errors);
        validateArray(root, "risks", false, errors);
        validateArray(root, "acceptanceCoverage", true, errors);
        validateBudgetEstimate(root, errors);
        return List.copyOf(errors);
    }

    private static void validateBudgetEstimate(JsonNode root, List<String> errors) {
        JsonNode budget = root.get("budgetEstimate");
        if (budget == null || !budget.isObject()) {
            errors.add("budgetEstimate must be an object");
            return;
        }
        validateNonNegativeLong(budget, "initialTokens", "budgetEstimate.initialTokens", errors);
        validateNonNegativeLong(budget, "retryReserveTokens", "budgetEstimate.retryReserveTokens", errors);
        validateNonNegativeLong(budget, "estimatedTotalTokens", "budgetEstimate.estimatedTotalTokens", errors);
        validateEnum(
                budget,
                "confidence",
                "budgetEstimate.confidence",
                BUDGET_CONFIDENCE_VALUES,
                errors
        );
        validateString(budget, "basis", "budgetEstimate.basis", errors);
        validateArray(budget, "historicalSamples", false, errors);
        long initialTokens = nonNegativeLong(budget.get("initialTokens"));
        long retryReserveTokens = nonNegativeLong(budget.get("retryReserveTokens"));
        long estimatedTotalTokens = nonNegativeLong(budget.get("estimatedTotalTokens"));
        if (estimatedTotalTokens < initialTokens || estimatedTotalTokens < retryReserveTokens
                || estimatedTotalTokens < safeAdd(initialTokens, retryReserveTokens)) {
            errors.add("budgetEstimate.estimatedTotalTokens must cover initialTokens and retryReserveTokens");
        }
    }

    private List<String> validateSolutionPlan(JsonNode root) {
        List<String> errors = new ArrayList<>();
        validateString(root, "summary", errors);
        validateArray(root, "affectedFiles", true, errors);
        validateArray(root, "implementationSteps", true, errors);
        validateArray(root, "acceptanceMapping", true, errors);
        validateArray(root, "testPlan", true, errors);
        return List.copyOf(errors);
    }

    private List<String> validateQaReport(JsonNode root) {
        List<String> errors = new ArrayList<>();
        validateEnum(root, "status", QA_STATUSES, errors);
        validateString(root, "summary", errors);
        validateEnum(root, "failureCategory", QA_FAILURE_CATEGORIES, errors);
        validateEnum(root, "retryRecommendation", QA_RETRY_RECOMMENDATIONS, errors);
        validateQaBrowserValidation(root, errors);
        validateString(root, "evidenceManifestArtifactId", errors);
        validateHostAssertionResults(root, errors);
        JsonNode acceptanceResults = root.get("acceptanceResults");
        if (acceptanceResults == null || !acceptanceResults.isArray() || acceptanceResults.isEmpty()) {
            errors.add("acceptanceResults must be a non-empty array");
            return List.copyOf(errors);
        }
        String overallStatus = root.path("status").asText("").strip().toUpperCase(Locale.ROOT);
        String failureCategory = root.path("failureCategory").asText("").strip().toUpperCase(Locale.ROOT);
        String retryRecommendation = root.path("retryRecommendation").asText("").strip().toUpperCase(Locale.ROOT);
        int failedResultCount = 0;
        int nonPassedResultCount = 0;
        boolean currentScopePresent = false;
        boolean regressionScopePresent = false;
        int index = 0;
        for (JsonNode result : acceptanceResults) {
            if (!result.isObject()) {
                errors.add("acceptanceResults[" + index + "] must be an object");
            } else {
                validateString(result, "criteria", "acceptanceResults[" + index + "].criteria", errors);
                validateEnum(result, "scope", "acceptanceResults[" + index + "].scope", QA_SCOPES, errors);
                validateString(result, "command", "acceptanceResults[" + index + "].command", errors);
                validateEnum(result, "status", "acceptanceResults[" + index + "].status", QA_STATUSES, errors);
                validateInteger(result, "exitCode", "acceptanceResults[" + index + "].exitCode", errors);
                validateNonNegativeLong(
                        result,
                        "durationMillis",
                        "acceptanceResults[" + index + "].durationMillis",
                        errors
                );
                validateString(result, "logArtifactId", "acceptanceResults[" + index + "].logArtifactId", errors);
                validateStringArray(
                        result,
                        "evidenceArtifactIds",
                        "acceptanceResults[" + index + "].evidenceArtifactIds",
                        true,
                        errors
                );
                String resultStatus = result.path("status").asText("").strip().toUpperCase(Locale.ROOT);
                String scope = result.path("scope").asText("").strip().toUpperCase(Locale.ROOT);
                if ("CURRENT".equals(scope)) {
                    currentScopePresent = true;
                }
                if ("REGRESSION".equals(scope)) {
                    regressionScopePresent = true;
                }
                if ("PASSED".equals(resultStatus)
                        && result.path("exitCode").isIntegralNumber()
                        && result.path("exitCode").longValue() != 0L) {
                    errors.add("acceptanceResults[" + index + "].status PASSED requires exitCode 0");
                }
                if ("FAILED".equals(resultStatus)) {
                    failedResultCount++;
                }
                if (!"PASSED".equals(resultStatus)) {
                    nonPassedResultCount++;
                }
            }
            index++;
        }
        if (!currentScopePresent) {
            errors.add("acceptanceResults must include CURRENT scope evidence");
        }
        if (!regressionScopePresent) {
            errors.add("acceptanceResults must include REGRESSION scope evidence");
        }
        if ("PASSED".equals(overallStatus) && nonPassedResultCount > 0) {
            errors.add("status PASSED requires all acceptanceResults to be PASSED");
        }
        if ("FAILED".equals(overallStatus) && failedResultCount == 0) {
            errors.add("status FAILED requires at least one FAILED acceptanceResults item");
        }
        if ("PASSED".equals(overallStatus) && !"NONE".equals(failureCategory)) {
            errors.add("status PASSED requires failureCategory NONE");
        }
        if ("PASSED".equals(overallStatus) && !"NONE".equals(retryRecommendation)) {
            errors.add("status PASSED requires retryRecommendation NONE");
        }
        if ("FAILED".equals(overallStatus) && "NONE".equals(failureCategory)) {
            errors.add("status FAILED requires a non-NONE failureCategory");
        }
        if ("FAILED".equals(overallStatus) && "NONE".equals(retryRecommendation)) {
            errors.add("status FAILED requires a retryRecommendation");
        }
        return List.copyOf(errors);
    }

    private static void validateHostAssertionResults(JsonNode root, List<String> errors) {
        for (String field : List.of(
                "hostAssertionBundle",
                "hostAssertionWorkspace",
                "hostAssertionBaseUrl",
                "hostAssertionContext"
        )) {
            if (root.has(field)) {
                errors.add(field + " is not accepted; agents must not control Host assertion execution");
            }
        }
        JsonNode results = root.get("hostAssertionResults");
        if (results == null) {
            return;
        }
        if (!results.isArray() || results.isEmpty()) {
            errors.add("hostAssertionResults must be a non-empty array when supplied");
            return;
        }
        Set<String> scopes = new HashSet<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode result = results.get(index);
            String prefix = "hostAssertionResults[" + index + "]";
            if (result == null || !result.isObject()) {
                errors.add(prefix + " must be an object");
                continue;
            }
            result.fieldNames().forEachRemaining(field -> {
                if (!HOST_ASSERTION_RESULT_FIELDS.contains(field)) {
                    errors.add(prefix + " may contain only scope, contentHash, and evidenceArtifactIds");
                }
            });
            validateEnum(result, "scope", prefix + ".scope", QA_SCOPES, errors);
            String scope = result.path("scope").asText("").strip().toUpperCase(Locale.ROOT);
            if (QA_SCOPES.contains(scope) && !scopes.add(scope)) {
                errors.add("hostAssertionResults contains duplicate " + scope + " scope");
            }
            JsonNode contentHash = result.get("contentHash");
            if (contentHash == null || !contentHash.isTextual()
                    || !SHA256_CONTENT_HASH.matcher(contentHash.asText("").strip()).matches()) {
                errors.add(prefix + ".contentHash must be a sha256: hash");
            }
            validateStringArray(
                    result,
                    "evidenceArtifactIds",
                    prefix + ".evidenceArtifactIds",
                    true,
                    errors
            );
        }
    }

    private static void validateQaBrowserValidation(JsonNode root, List<String> errors) {
        JsonNode browserValidation = root.get("browserValidation");
        if (browserValidation == null || !browserValidation.isObject()) {
            errors.add("browserValidation must be an object");
            return;
        }
        validateBoolean(browserValidation, "required", "browserValidation.required", errors);
        validateBoolean(browserValidation, "performed", "browserValidation.performed", errors);
        validateEnum(
                browserValidation,
                "decisionSource",
                "browserValidation.decisionSource",
                QA_DECISION_SOURCES,
                errors
        );
        validateString(browserValidation, "browser", "browserValidation.browser", errors);
        validateStringArray(browserValidation, "viewports", "browserValidation.viewports", false, errors);

        boolean required = browserValidation.path("required").asBoolean(false);
        boolean performed = browserValidation.path("performed").asBoolean(false);
        String overallStatus = root.path("status").asText("").strip().toUpperCase(Locale.ROOT);
        if (required && !"chromium".equalsIgnoreCase(
                browserValidation.path("browser").asText("").strip())) {
            errors.add("browserValidation.browser must be chromium");
        }
        if (required && !performed && "PASSED".equals(overallStatus)) {
            errors.add("browserValidation.required requires browserValidation.performed true");
        }
        if (required && performed) {
            validateString(browserValidation, "baseUrl", "browserValidation.baseUrl", errors);
            JsonNode viewports = browserValidation.get("viewports");
            if (viewports != null && viewports.isArray() && viewports.isEmpty()) {
                errors.add("browserValidation.viewports must be a non-empty array when browser validation is required");
            }
            if (!stringArrayContains(viewports, "desktop-1440x900")) {
                errors.add("browserValidation.viewports must include desktop-1440x900");
            }
            if (!stringArrayContains(viewports, "mobile-390x844")) {
                errors.add("browserValidation.viewports must include mobile-390x844");
            }
        }
    }

    private static boolean stringArrayContains(JsonNode array, String expected) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (expected.equals(item.asText("").strip())) {
                return true;
            }
        }
        return false;
    }

    private static void validateString(JsonNode root, String fieldName, List<String> errors) {
        validateString(root, fieldName, fieldName, errors);
    }

    private static void validateString(JsonNode root, String fieldName, String displayName, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText("").isBlank()) {
            errors.add(displayName + " must not be blank");
        }
    }

    private static void validateEnum(
            JsonNode root,
            String fieldName,
            Set<String> allowedValues,
            List<String> errors
    ) {
        validateEnum(root, fieldName, fieldName, allowedValues, errors);
    }

    private static void validateEnum(
            JsonNode root,
            String fieldName,
            String displayName,
            Set<String> allowedValues,
            List<String> errors
    ) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText("").isBlank()) {
            errors.add(displayName + " must not be blank");
            return;
        }
        if (!allowedValues.contains(value.asText("").strip().toUpperCase(Locale.ROOT))) {
            errors.add(displayName + " must be one of " + String.join(", ", allowedValues));
        }
    }

    private static void validateArray(JsonNode root, String fieldName, boolean nonEmpty, List<String> errors) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isArray()) {
            errors.add(fieldName + (nonEmpty ? " must be a non-empty array" : " must be an array"));
            return;
        }
        if (nonEmpty && value.isEmpty()) {
            errors.add(fieldName + " must be a non-empty array");
        }
    }

    private static void validateStringArray(
            JsonNode root,
            String fieldName,
            String displayName,
            boolean nonEmpty,
            List<String> errors
    ) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isArray() || nonEmpty && value.isEmpty()) {
            errors.add(displayName + (nonEmpty ? " must be a non-empty array" : " must be an array"));
            return;
        }
        int index = 0;
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText("").isBlank()) {
                errors.add(displayName + "[" + index + "] must not be blank");
            }
            index++;
        }
    }

    private static void validateBoolean(
            JsonNode root,
            String fieldName,
            String displayName,
            List<String> errors
    ) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isBoolean()) {
            errors.add(displayName + " must be a boolean");
        }
    }

    private static void validateInteger(
            JsonNode root,
            String fieldName,
            String displayName,
            List<String> errors
    ) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isIntegralNumber()) {
            errors.add(displayName + " must be an integer");
        }
    }

    private static void validateNonNegativeLong(
            JsonNode root,
            String fieldName,
            String displayName,
            List<String> errors
    ) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0L) {
            errors.add(displayName + " must be a non-negative integer");
        }
    }

    private static long nonNegativeLong(JsonNode value) {
        return value != null && value.canConvertToLong() && value.longValue() >= 0L ? value.longValue() : 0L;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }
}
