package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;
import java.util.Objects;

/**
 * 重复身份收敛在同一事务内提交的包。任一 loser CAS 失败则整笔回滚。
 */
public record KnowledgeProjectionSupersedeBundle(
        String survivorDocumentId,
        List<KnowledgeProjectionSupersedeLoser> losers,
        long nowEpochMillis
) {

    public KnowledgeProjectionSupersedeBundle {
        Objects.requireNonNull(survivorDocumentId, "survivorDocumentId must not be null");
        losers = losers == null ? List.of() : List.copyOf(losers);
        nowEpochMillis = Math.max(0L, nowEpochMillis);
    }
}
