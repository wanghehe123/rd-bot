package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;
import java.util.Objects;

/**
 * 同一知识库同一来源身份的可见重复组，含确定性提出的存活文档。
 */
public record DuplicateIdentityGroup(
        String knowledgeBaseId,
        String identityKey,
        String proposedSurvivorDocumentId,
        List<DuplicateIdentityMember> members
) {

    public DuplicateIdentityGroup {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        identityKey = identityKey == null ? "" : identityKey;
        proposedSurvivorDocumentId = proposedSurvivorDocumentId == null ? "" : proposedSurvivorDocumentId;
        members = members == null ? List.of() : List.copyOf(members);
    }
}
