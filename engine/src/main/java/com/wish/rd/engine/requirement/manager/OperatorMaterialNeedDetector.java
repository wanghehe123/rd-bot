package com.wish.rd.engine.requirement.manager;

import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.util.List;
import java.util.Locale;

/**
 * Host-side detector for missing operator material that should force Manager {@code ASK}.
 *
 * <p>Production historically only recognized {@code BLOCKED} audited records whose
 * {@code blockedReason} contains {@code NEED_USER_INPUT}, but no writer produced that shape.
 * This detector closes the gap for explicit incomplete-material probes (and operator text that
 * already carries the same marker) without inventing a synthetic admin state edit.
 */
public final class OperatorMaterialNeedDetector {

    static final String NEED_USER_INPUT_TOKEN = "NEED_USER_INPUT";
    static final String W2_ASK_MARKER = "P3-W2-ASK-MARKER";
        static final String INTENTIONAL_OMISSION = "故意省略";
        static final String INTENTIONAL_UNPROVIDED = "故意未提供";

    private OperatorMaterialNeedDetector() {
    }

    /**
     * Returns whether Manager should ask for operator material before continuing role work.
     *
     * @param materials task materials (preview/title)
     * @param expectedResult expected result text
     * @param acceptanceCriteria acceptance criteria texts
     * @return true when operator input is required
     */
    public static boolean requiresOperatorInput(
            List<TaskMaterial> materials,
            String expectedResult,
            List<String> acceptanceCriteria
    ) {
        StringBuilder haystack = new StringBuilder();
        if (materials != null) {
            for (TaskMaterial material : materials) {
                if (material == null) {
                    continue;
                }
                haystack.append(' ').append(material.title()).append(' ').append(material.contentPreview());
            }
        }
        if (expectedResult != null) {
            haystack.append(' ').append(expectedResult);
        }
        if (acceptanceCriteria != null) {
            for (String criterion : acceptanceCriteria) {
                if (criterion != null) {
                    haystack.append(' ').append(criterion);
                }
            }
        }
        String normalized = haystack.toString().toUpperCase(Locale.ROOT);
        String raw = haystack.toString();
        return normalized.contains(NEED_USER_INPUT_TOKEN)
                || normalized.contains(W2_ASK_MARKER)
                || raw.contains(INTENTIONAL_OMISSION)
                || raw.contains(INTENTIONAL_UNPROVIDED);
    }

    /**
     * Task-shaped convenience wrapper.
     *
     * @param task requirement task
     * @param materials materials for the task
     * @return true when operator input is required
     */
    public static boolean requiresOperatorInput(RdRequirementTask task, List<TaskMaterial> materials) {
        if (task == null) {
            return false;
        }
        return requiresOperatorInput(materials, task.expectedResult(), parseCriteria(task.acceptanceCriteriaJson()));
    }

    private static List<String> parseCriteria(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode tree =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (tree == null || !tree.isArray()) {
                return List.of(json);
            }
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : tree) {
                values.add(node.isTextual() ? node.textValue() : node.toString());
            }
            return List.copyOf(values);
        } catch (Exception ignored) {
            return List.of(json);
        }
    }
}
