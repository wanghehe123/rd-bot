package com.wish.rd.rag.project.template.model;

import java.util.List;

/** Editable project task template fields. */
public record RdProjectTaskTemplateCommand(
        String name,
        String actualBehavior,
        String expectedBehavior,
        String reproductionSteps,
        String affectedScope,
        List<String> acceptanceCriteria,
        String requirementBody,
        String expectedResult
) {
    public RdProjectTaskTemplateCommand {
        name = safe(name);
        actualBehavior = safe(actualBehavior);
        expectedBehavior = safe(expectedBehavior);
        reproductionSteps = safe(reproductionSteps);
        affectedScope = safe(affectedScope);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : acceptanceCriteria.stream()
                .filter(java.util.Objects::nonNull).map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
        requirementBody = safe(requirementBody);
        expectedResult = safe(expectedResult);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
