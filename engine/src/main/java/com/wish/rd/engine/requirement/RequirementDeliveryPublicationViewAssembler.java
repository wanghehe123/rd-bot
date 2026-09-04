package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.AcceptanceResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.BrowserValidation;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.CodingDelivery;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.DeliveryReview;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.QaDelivery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把持久化的多角色结果组装为审核与发布共享的单一事实视图。
 */
@Component
public class RequirementDeliveryPublicationViewAssembler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CODING_AGENT = "CODING_AGENT";
    private static final String QA_AGENT = "QA_AGENT";
    private static final Set<String> REQUIRED_DELIVERY_ROLES = AgentRole.requirementDeliveryOrder().stream()
            .map(AgentRole::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 解析并校验交付结果。
     *
     * @param deliveryResultJson 持久化交付结果 JSON
     * @return 统一发布视图
     * @throws IllegalArgumentException JSON、角色结果或关键 Coding 字段无效时抛出
     */
    public RequirementDeliveryPublicationView assemble(String deliveryResultJson) {
        JsonNode root = parseObject(deliveryResultJson, "deliveryResultJson");
        JsonNode aggregateStatus = root.get("multiAgentStatus");
        if (aggregateStatus != null && !aggregateStatus.isNull()) {
            if (!aggregateStatus.isTextual()
                    || !"SUCCESS".equalsIgnoreCase(aggregateStatus.textValue().strip())) {
                throw invalid("multiAgentStatus", "must be SUCCESS when present");
            }
        }
        Map<String, List<Candidate>> candidates = new LinkedHashMap<>();
        candidates.put(CODING_AGENT, new ArrayList<>());
        candidates.put(QA_AGENT, new ArrayList<>());
        Set<String> successfulRoles = new LinkedHashSet<>();

        collectStageContainer(root.path("multiAgentStages"), "multiAgentStages", candidates, successfulRoles);
        collectStageContainer(root.path("stageResults"), "stageResults", candidates, successfulRoles);
        collectRoleResults(root.path("roleResults"), candidates, successfulRoles);
        if (hasCodingSignal(root)) {
            candidates.get(CODING_AGENT).add(new Candidate("root", root));
            successfulRoles.add(CODING_AGENT);
        }

        JsonNode codingNode = select(CODING_AGENT, candidates.get(CODING_AGENT));
        CodingDelivery coding = coding(codingNode);
        JsonNode qaNode = select(QA_AGENT, candidates.get(QA_AGENT));
        QaDelivery qa = qaNode == null ? QaDelivery.empty() : qa(qaNode);
        DeliveryReview review = review(root.path("deliveryReview"));

        List<String> evidence = new ArrayList<>();
        addEvidence(evidence, qa.evidenceManifestArtifactId());
        qa.acceptanceResults().forEach(result -> {
            addEvidence(evidence, result.logArtifactId());
            result.evidenceArtifactIds().forEach(value -> addEvidence(evidence, value));
        });
        String summary = firstNonBlank(text(root.path("summary")), text(codingNode.path("summary")), qa.summary());
        String narrative = firstNonBlank(text(codingNode.path("prBody")), text(codingNode.path("summary")));
        return new RequirementDeliveryPublicationView(
                RequirementDeliveryPublicationView.CURRENT_SCHEMA_VERSION,
                summary,
                narrative,
                coding,
                qa,
                review,
                evidence,
                List.copyOf(successfulRoles)
        );
    }

    private void collectStageContainer(
            JsonNode container,
            String source,
            Map<String, List<Candidate>> candidates,
            Set<String> successfulRoles
    ) {
        if (container == null || container.isMissingNode() || container.isNull()) {
            return;
        }
        if (!container.isArray()) {
            throw invalid(source, "must be an array");
        }
        Set<String> seenSuccessful = new LinkedHashSet<>();
        Set<String> seenTracked = new LinkedHashSet<>();
        Set<String> higherPrioritySuccess = new LinkedHashSet<>(successfulRoles);
        for (JsonNode stage : container) {
            if (!stage.isObject()) {
                throw invalid(source, "entries must be objects");
            }
            String role = text(stage.path("role")).toUpperCase(Locale.ROOT);
            if (REQUIRED_DELIVERY_ROLES.contains(role)) seenTracked.add(role);
            boolean successful = stage.path("success").asBoolean(
                    "SUCCEEDED".equalsIgnoreCase(text(stage.path("status")))
            );
            if (!successful || role.isBlank()) {
                continue;
            }
            if (!seenSuccessful.add(role)) {
                throw invalid(source + "." + role, "duplicate successful role result");
            }
            successfulRoles.add(role);
            if (!text(stage.path("pullRequestUrl")).isBlank()) {
                throw invalid(source + "." + role + ".pullRequestUrl", "must be blank before delivery review");
            }
            if (candidates.containsKey(role)) {
                String resultField = stage.has("result") ? "result" : "resultJson";
                JsonNode result = unwrapResult(
                        stage.path(resultField), source + "." + role + "." + resultField);
                candidates.get(role).add(new Candidate(source, result));
            }
        }
        for (String role : seenTracked) {
            if (!seenSuccessful.contains(role) && !higherPrioritySuccess.contains(role)) {
                throw invalid(source + "." + role, "current role result is not successful");
            }
        }
    }

    private void collectRoleResults(
            JsonNode roleResults,
            Map<String, List<Candidate>> candidates,
            Set<String> successfulRoles
    ) {
        if (roleResults == null || roleResults.isMissingNode() || roleResults.isNull()) {
            return;
        }
        if (!roleResults.isObject()) {
            throw invalid("roleResults", "must be an object");
        }
        for (String role : List.of(CODING_AGENT, QA_AGENT)) {
            JsonNode value = roleResults.path(role);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            JsonNode result = value.has("resultJson")
                    ? unwrapResult(value.path("resultJson"), "roleResults." + role + ".resultJson")
                    : unwrapResult(value, "roleResults." + role);
            candidates.get(role).add(new Candidate("roleResults", result));
            successfulRoles.add(role);
        }
    }

    private JsonNode select(String role, List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            if (CODING_AGENT.equals(role)) {
                throw invalid("CODING_AGENT", "result is missing");
            }
            return null;
        }
        Candidate selected = candidates.getFirst();
        for (int index = 1; index < candidates.size(); index++) {
            assertNoConflict(role, selected, candidates.get(index));
        }
        return selected.value();
    }

    private void assertNoConflict(String role, Candidate selected, Candidate other) {
        List<String> fields = CODING_AGENT.equals(role)
                ? List.of("changedFiles", "testCommands", "testStatus", "riskLevel")
                : List.of("status", "failureCategory", "retryRecommendation", "acceptanceResults",
                "browserValidation", "evidenceManifestArtifactId");
        for (String field : fields) {
            JsonNode left = selected.value().path(field);
            JsonNode right = other.value().path(field);
            if (!isEmpty(left) && !isEmpty(right)
                    && !canonical(field, left).equals(canonical(field, right))) {
                throw invalid(role + "." + field,
                        "conflict between " + selected.source() + " and " + other.source());
            }
        }
    }

    private CodingDelivery coding(JsonNode node) {
        List<String> changedFiles = stringList(node.path("changedFiles"), "CODING_AGENT.changedFiles", true, true);
        List<String> testCommands = stringList(node.path("testCommands"), "CODING_AGENT.testCommands", true, false);
        String testStatus = requiredText(node.path("testStatus"), "CODING_AGENT.testStatus");
        return new CodingDelivery(changedFiles, testCommands, testStatus, text(node.path("riskLevel")));
    }

    private QaDelivery qa(JsonNode node) {
        List<AcceptanceResult> results = new ArrayList<>();
        JsonNode acceptance = node.path("acceptanceResults");
        if (!acceptance.isMissingNode() && !acceptance.isNull()) {
            if (!acceptance.isArray()) {
                throw invalid("QA_AGENT.acceptanceResults", "must be an array");
            }
            int index = 0;
            for (JsonNode result : acceptance) {
                if (!result.isObject()) {
                    throw invalid("QA_AGENT.acceptanceResults[" + index + "]", "must be an object");
                }
                results.add(new AcceptanceResult(
                        text(result.path("criteria")), text(result.path("scope")),
                        text(result.path("command")), text(result.path("status")),
                        longValue(result.path("exitCode")), longValue(result.path("durationMillis")),
                        text(result.path("logArtifactId")),
                        stringList(result.path("evidenceArtifactIds"),
                                "QA_AGENT.acceptanceResults[" + index + "].evidenceArtifactIds", false, false)
                ));
                index++;
            }
        }
        JsonNode browser = node.path("browserValidation");
        BrowserValidation browserValidation = browser.isObject()
                ? new BrowserValidation(
                browser.path("required").asBoolean(false), browser.path("performed").asBoolean(false),
                browser.path("required").isBoolean(), browser.path("performed").isBoolean(),
                text(browser.path("decisionSource")), text(browser.path("baseUrl")),
                text(browser.path("browser")),
                stringList(browser.path("viewports"), "QA_AGENT.browserValidation.viewports", false, false))
                : BrowserValidation.empty();
        return new QaDelivery(
                text(node.path("status")), text(node.path("summary")),
                text(node.path("failureCategory")), text(node.path("retryRecommendation")),
                browserValidation, results, text(node.path("evidenceManifestArtifactId"))
        );
    }

    private DeliveryReview review(JsonNode node) {
        if (node == null || !node.isObject()) {
            return DeliveryReview.empty();
        }
        return new DeliveryReview(
                text(node.path("taskId")), text(node.path("reviewer")),
                node.path("approved").asBoolean(false), text(node.path("reason")),
                text(node.path("factsHash"))
        );
    }

    private JsonNode unwrapResult(JsonNode value, String path) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            throw invalid(path, "must be an object or JSON object string");
        }
        if (value.isObject()) {
            return value;
        }
        if (value.isTextual()) {
            return parseObject(value.asText(), path);
        }
        throw invalid(path, "must be an object or JSON object string");
    }

    private JsonNode parseObject(String raw, String path) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(raw == null ? "" : raw);
            if (parsed == null || !parsed.isObject()) {
                throw invalid(path, "must be a JSON object");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw invalid(path, "must be valid JSON: " + exception.getOriginalMessage());
        }
    }

    private List<String> stringList(JsonNode node, String path, boolean required, boolean allowScalar) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            if (required) {
                throw invalid(path, "must be a non-empty string array");
            }
            return List.of();
        }
        if (allowScalar && node.isTextual()) {
            String value = text(node);
            if (!value.isBlank()) {
                return List.of(value);
            }
        }
        if (!node.isArray()) {
            throw invalid(path, "must be a string array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = text(item);
            if (value.isBlank()) {
                throw invalid(path, "must contain only non-empty strings");
            }
            values.add(value);
        }
        if (required && values.isEmpty()) {
            throw invalid(path, "must not be empty");
        }
        return List.copyOf(values);
    }

    private String requiredText(JsonNode node, String path) {
        String value = text(node);
        if (value.isBlank()) {
            throw invalid(path, "must be a non-empty string");
        }
        return value;
    }

    private Long longValue(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong()
                ? node.longValue()
                : null;
    }

    private boolean hasCodingSignal(JsonNode root) {
        return root.has("changedFiles") || root.has("testCommands") || root.has("testStatus");
    }

    private String canonical(String field, JsonNode value) {
        if ("changedFiles".equals(field) || "testCommands".equals(field)) {
            java.util.TreeSet<String> items = new java.util.TreeSet<>();
            if (value.isTextual()) {
                items.add(text(value));
            } else if (value.isArray()) {
                value.forEach(item -> items.add(text(item)));
            }
            return canonicalTextCollection(new ArrayList<>(items));
        }
        return canonicalNode(value, "browserValidation".equals(field));
    }

    private String canonicalNode(JsonNode value, boolean unorderedArrays) {
        if (value == null || value.isMissingNode() || value.isNull()) return "null";
        if (value.isTextual()) return "s:" + lengthPrefixed(text(value));
        if (value.isNumber() || value.isBoolean()) return "v:" + value;
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(item -> items.add(canonicalNode(item, unorderedArrays)));
            List<String> normalized = unorderedArrays
                    ? new ArrayList<>(new java.util.TreeSet<>(items))
                    : items;
            return "a:" + canonicalTextCollection(normalized);
        }
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            StringBuilder canonical = new StringBuilder("o:");
            for (String name : names) {
                canonical.append(lengthPrefixed(name));
                canonical.append(lengthPrefixed(canonicalNode(value.path(name), unorderedArrays)));
            }
            return canonical.toString();
        }
        return "v:" + value;
    }

    private String canonicalTextCollection(List<String> values) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(values.size()).append(':');
        values.forEach(value -> canonical.append(lengthPrefixed(value)));
        return canonical.toString();
    }

    private String lengthPrefixed(String value) {
        String normalized = safe(value);
        return normalized.length() + ":" + normalized;
    }

    private boolean isEmpty(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || (value.isTextual() && text(value).isBlank())
                || (value.isArray() && value.isEmpty());
    }

    private void addEvidence(List<String> target, String value) {
        String normalized = safe(value);
        if (!normalized.isBlank() && !target.contains(normalized)) {
            target.add(normalized);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!safe(value).isBlank()) {
                return safe(value);
            }
        }
        return "";
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? safe(node.asText()) : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(path + ": " + reason);
    }

    private record Candidate(String source, JsonNode value) {
    }
}
