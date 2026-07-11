package com.wish.rd.engine.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.draft.model.TaskDraftRequest;
import com.wish.rd.engine.draft.model.TaskDraftResult;

import java.util.ArrayList;
import java.util.List;

/** Validates model-produced form suggestions and never mutates task state. */
public final class TaskDraftEngine {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final TaskDraftModelPort modelPort;

    public TaskDraftEngine(TaskDraftModelPort modelPort) {
        this.modelPort = modelPort == null
                ? TaskDraftModelPort.unavailable("task draft model is not configured")
                : modelPort;
    }

    public TaskDraftResult complete(TaskDraftRequest request) {
        TaskDraftModelPort.ModelResponse response = modelPort.complete(request);
        if (response == null || !response.available()) {
            return unavailable(response == null ? "task draft model returned no response" : response.reason());
        }
        try {
            JsonNode json = OBJECT_MAPPER.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.rawJson());
            if (json == null || !json.isObject()) {
                return unavailable("invalid model JSON: root must be an object");
            }
            validateStructure(request, json);
            return new TaskDraftResult(
                    true, "", text(json, "actualBehavior"), text(json, "expectedBehavior"),
                    text(json, "reproductionSteps"), text(json, "affectedScope"),
                    text(json, "requirementBody"), text(json, "expectedResult"),
                    strings(json, "acceptanceCriteria"), strings(json, "missingFields"),
                    strings(json, "evidence"), json.path("confidence").asDouble(0), true
            );
        } catch (Exception exception) {
            return unavailable("invalid model JSON: " + exception.getClass().getSimpleName());
        }
    }

    private static void validateStructure(TaskDraftRequest request, JsonNode json) {
        requireText(json, request.taskType().equals("BUG_FIX") ? "actualBehavior" : "requirementBody");
        requireText(json, request.taskType().equals("BUG_FIX") ? "expectedBehavior" : "expectedResult");
        if (request.taskType().equals("BUG_FIX")) {
            requireText(json, "reproductionSteps");
            requireText(json, "affectedScope");
        }
        requireStringArray(json, "acceptanceCriteria", true);
        requireStringArray(json, "missingFields", false);
        requireStringArray(json, "evidence", false);
        if (!json.path("confidence").isNumber()) {
            throw new IllegalArgumentException("confidence must be a number");
        }
        double confidence = json.path("confidence").asDouble();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    private static void requireText(JsonNode json, String field) {
        if (!json.path(field).isTextual() || json.path(field).asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
    }

    private static void requireStringArray(JsonNode json, String field, boolean requireItem) {
        JsonNode values = json.path(field);
        if (!values.isArray() || (requireItem && values.isEmpty())) {
            throw new IllegalArgumentException(field + " must be a string array");
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-blank strings");
            }
        }
    }

    private static TaskDraftResult unavailable(String reason) {
        return new TaskDraftResult(false, reason, "", "", "", "", "", "",
                List.of(), List.of(), List.of(), 0, false);
    }

    private static String text(JsonNode json, String field) {
        return json.path(field).isTextual() ? json.path(field).asText().strip() : "";
    }

    private static List<String> strings(JsonNode json, String field) {
        JsonNode values = json.path(field);
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().strip()); });
        return List.copyOf(result);
    }
}
