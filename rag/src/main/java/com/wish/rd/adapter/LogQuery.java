package com.wish.rd.adapter;

import java.time.Instant;
import java.util.List;

/**
 * 日志检索查询条件，作为 {@link LogCenterPort#searchLogs(LogQuery)} 的入参。
 *
 * @param systemId 目标系统 ID
 * @param keywords 关键词列表（为空则不限）
 * @param from     起始时间（可空表示不限）
 * @param to       截止时间（可空表示不限）
 * @param limit    返回上限，<=0 时取默认 100
 */
public record LogQuery(
        String systemId,
        List<String> keywords,
        Instant from,
        Instant to,
        int limit
) {

    public LogQuery {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        limit = limit <= 0 ? 100 : limit;
    }
}
