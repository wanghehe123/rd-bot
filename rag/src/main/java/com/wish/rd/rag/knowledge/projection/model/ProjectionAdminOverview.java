package com.wish.rd.rag.knowledge.projection.model;

import java.util.Map;

/**
 * 一个知识库的投影总览。计数只来自账本，不来自进程内计数器。
 *
 * @param ready                      远端是否可写
 * @param bindingCounts              按 projection_status 计数
 * @param outboxCounts               按 outbox status 计数
 * @param unconvergedCount           非终态 outbox 行数
 * @param oldestUnconvergedAgeMillis 最老未收敛行的年龄
 */
public record ProjectionAdminOverview(
        boolean ready,
        Map<String, Long> bindingCounts,
        Map<String, Long> outboxCounts,
        long unconvergedCount,
        long oldestUnconvergedAgeMillis
) {

    public ProjectionAdminOverview {
        bindingCounts = bindingCounts == null ? Map.of() : Map.copyOf(bindingCounts);
        outboxCounts = outboxCounts == null ? Map.of() : Map.copyOf(outboxCounts);
        unconvergedCount = Math.max(0L, unconvergedCount);
        oldestUnconvergedAgeMillis = Math.max(0L, oldestUnconvergedAgeMillis);
    }
}
