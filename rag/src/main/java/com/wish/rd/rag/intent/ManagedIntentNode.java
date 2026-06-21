package com.wish.rd.rag.intent;

import java.util.List;

public record ManagedIntentNode(
        String id,
        String intentCode,
        String name,
        Integer level,
        String parentCode,
        String description,
        String kbId,
        List<String> examples,
        List<String> codeRepositoryIds,
        Integer enabled,
        Integer sortOrder,
        List<ManagedIntentNode> children
) {

    public ManagedIntentNode {
        description = description == null ? "" : description;
        examples = examples == null ? List.of() : List.copyOf(examples);
        codeRepositoryIds = codeRepositoryIds == null ? List.of() : List.copyOf(codeRepositoryIds);
        children = children == null ? List.of() : List.copyOf(children);
        enabled = enabled == null ? 1 : enabled;
        sortOrder = sortOrder == null ? 0 : sortOrder;
    }

    public ManagedIntentNode withoutChildren() {
        return new ManagedIntentNode(
                id,
                intentCode,
                name,
                level,
                parentCode,
                description,
                kbId,
                examples,
                codeRepositoryIds,
                enabled,
                sortOrder,
                List.of()
        );
    }
}
