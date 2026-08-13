package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.ReconcileFinding;

import java.util.List;

/**
 * 对账发现账本。同一身份重复写入只推进 {@code last_seen}，不另开一行。
 */
public interface KnowledgeReconcileFindingStore {

    /**
     * 按身份 upsert。已存在时保留 {@code first_seen} 与原 id，只更新最近见到时间与说明。
     *
     * @param finding 本轮看到的发现
     * @return 账本中的权威行
     */
    ReconcileFinding upsert(ReconcileFinding finding);

    List<ReconcileFinding> listByKnowledgeBase(String provider, String knowledgeBaseId);
}
