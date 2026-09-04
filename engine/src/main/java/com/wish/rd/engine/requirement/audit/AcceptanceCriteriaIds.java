package com.wish.rd.engine.requirement.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-owned acceptance-criterion identifiers {@code AC-%03d}, shared with Pi session TODOs.
 */
public final class AcceptanceCriteriaIds {

    public static final String ATTACHMENT_FILENAME = "acceptance-criteria-ids.json";
    public static final String EVIDENCE_SOURCE_TYPE = "FROZEN_ACCEPTANCE_CRITERIA_IDS";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AcceptanceCriteriaIds() {
    }

    /**
     * Returns one {@code AC-%03d} id per criterion, in list order, 1-based.
     *
     * @param criteria frozen acceptance-criteria texts
     * @return ids such as {@code AC-001}
     */
    public static List<String> of(List<String> criteria) {
        List<String> source = criteria == null ? List.of() : criteria;
        List<String> ids = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            String text = source.get(index) == null ? "" : source.get(index).strip();
            if (text.isBlank()) {
                throw new IllegalArgumentException("acceptance criterion must not be blank");
            }
            ids.add(idAt(index));
        }
        return List.copyOf(ids);
    }

    /**
     * Returns the id for a 0-based criterion index.
     *
     * @param index 0-based index
     * @return {@code AC-%03d}
     */
    public static String idAt(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("acceptance criterion index must be >= 0");
        }
        return "AC-%03d".formatted(index + 1);
    }

    /**
     * Parses frozen acceptance-criteria texts from task JSON, skipping blanks.
     *
     * @param acceptanceCriteriaJson task {@code acceptanceCriteriaJson}
     * @return non-blank criterion texts in order
     */
    public static List<String> texts(String acceptanceCriteriaJson) {
        try {
            JsonNode tree = MAPPER.readTree(
                    acceptanceCriteriaJson == null || acceptanceCriteriaJson.isBlank()
                            ? "[]"
                            : acceptanceCriteriaJson);
            if (tree == null || !tree.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode node : tree) {
                String value = node.asText("").strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    /**
     * Returns {@code AC-%03d} ids for the texts in {@code acceptanceCriteriaJson}.
     *
     * @param acceptanceCriteriaJson task {@code acceptanceCriteriaJson}
     * @return frozen ids such as {@code AC-001}
     */
    public static List<String> ofJson(String acceptanceCriteriaJson) {
        return of(texts(acceptanceCriteriaJson));
    }

    /**
     * Compact JSON array used as the read-only {@code /work/input} attachment.
     *
     * @param ids frozen {@code AC-%03d} ids
     * @return {@code ["AC-001","AC-002"]}
     */
    public static String canonicalJson(List<String> ids) {
        List<String> source = ids == null ? List.of() : ids;
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < source.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(source.get(index)).append('"');
        }
        return json.append(']').toString();
    }

    /**
     * Prompt clause listing the frozen CURRENT {@code criteriaId} set.
     *
     * @param ids frozen {@code AC-%03d} ids
     * @return two-line CURRENT/REGRESSION contract
     */
    public static String promptFrozenSetClause(List<String> ids) {
        String listed = ids == null || ids.isEmpty() ? "（空）" : String.join(", ", ids);
        return """
                - CURRENT 项必须带 criteriaId，且必须属于冻结集合：%s
                - REGRESSION 项可不带 criteriaId
                """.formatted(listed).strip();
    }
}
