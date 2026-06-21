package com.wish.rd.rag.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record IntentNode(
        String id,
        String name,
        String description,
        IntentLevel level,
        String systemId,
        List<String> codeRepositoryIds,
        List<String> knowledgeBaseIds,
        List<String> examples,
        List<IntentNode> children
) {

    public IntentNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        description = description == null ? "" : description;
        level = level == null ? IntentLevel.CAPABILITY : level;
        systemId = systemId == null || systemId.isBlank() ? id : systemId;
        codeRepositoryIds = codeRepositoryIds == null ? List.of() : List.copyOf(codeRepositoryIds);
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        examples = examples == null ? List.of() : List.copyOf(examples);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String profileText() {
        return String.join("\n", name, description, String.join("\n", examples));
    }

    public static final class Builder {
        private String id;
        private String name;
        private String description;
        private IntentLevel level;
        private String systemId;
        private List<String> codeRepositoryIds = List.of();
        private List<String> knowledgeBaseIds = List.of();
        private List<String> examples = List.of();
        private List<IntentNode> children = List.of();

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder level(IntentLevel level) {
            this.level = level;
            return this;
        }

        public Builder systemId(String systemId) {
            this.systemId = systemId;
            return this;
        }

        public Builder codeRepositoryIds(List<String> codeRepositoryIds) {
            this.codeRepositoryIds = codeRepositoryIds;
            return this;
        }

        public Builder knowledgeBaseIds(List<String> knowledgeBaseIds) {
            this.knowledgeBaseIds = knowledgeBaseIds;
            return this;
        }

        public Builder examples(List<String> examples) {
            this.examples = examples;
            return this;
        }

        public Builder children(List<IntentNode> children) {
            this.children = children;
            return this;
        }

        public IntentNode build() {
            List<String> resolvedKnowledgeBaseIds = new ArrayList<>(
                    knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() ? List.of(id) : knowledgeBaseIds
            );
            return new IntentNode(id, name, description, level, systemId, codeRepositoryIds,
                    resolvedKnowledgeBaseIds, examples, children);
        }
    }
}
