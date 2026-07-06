package com.wish.rd.rag.intent.model;

import java.util.List;

public record IntentNodeCommand(
        String intentCode,
        String name,
        Integer level,
        String parentCode,
        String description,
        String kbId,
        List<String> examples,
        List<String> codeRepositoryIds,
        Integer enabled,
        Integer sortOrder
) {

    public IntentNodeCommand {
        intentCode = trimToNull(intentCode);
        name = trimToNull(name);
        parentCode = trimToNull(parentCode);
        description = description == null ? "" : description.strip();
        kbId = trimToNull(kbId);
        examples = examples == null ? List.of() : List.copyOf(examples);
        codeRepositoryIds = codeRepositoryIds == null ? List.of() : List.copyOf(codeRepositoryIds);
        enabled = enabled == null ? 1 : enabled;
        sortOrder = sortOrder == null ? 0 : sortOrder;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }
}
