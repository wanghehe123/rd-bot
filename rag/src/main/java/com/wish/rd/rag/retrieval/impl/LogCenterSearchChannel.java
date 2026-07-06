package com.wish.rd.rag.retrieval.impl;

import com.wish.rd.rag.retrieval.SearchChannel;

import com.wish.rd.adapter.LogCenterPort;
import com.wish.rd.adapter.model.LogQuery;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.text.TextAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;

/**
 * 日志中心检索通道。
 *
 * <p>仅当注入了日志端口、命中主意图且意图绑定了目标系统时启用。
 * 通过 {@link LogCenterPort} 拉取与查询相关的运行日志，包装成 {@code runtime-log}
 * 类型的证据块，供后续 Prompt 的"运行日志"段落使用。
 */
public final class LogCenterSearchChannel implements SearchChannel {

    private final LogCenterPort logCenterPort;

    public LogCenterSearchChannel(LogCenterPort logCenterPort) {
        this.logCenterPort = logCenterPort;
    }

    @Override
    public String name() {
        return "LogCenterSearch";
    }

    /** 启用条件：日志端口存在、命中意图且意图绑定了目标系统 ID。 */
    @Override
    public boolean isEnabled(RetrievalRequest request) {
        return logCenterPort != null && request.primaryIntent().isPresent() && !request.targetSystemId().isBlank();
    }

    @Override
    public ChannelSearchResult search(RetrievalRequest request) {
        // 以查询文本切出的关键词作为日志检索条件
        List<String> logs = logCenterPort.searchLogs(new LogQuery(
                request.targetSystemId(),
                List.copyOf(TextAnalyzer.terms(request.query())),
                null,
                null,
                request.topK()
        ));
        ArrayList<RetrievedChunk> chunks = new ArrayList<>();
        for (int index = 0; index < logs.size(); index++) {
            String logLine = logs.get(index);
            // 分数取查询与日志行的重叠分，下限 1.0 保证至少能进入结果集
            double score = Math.max(1.0d, TextAnalyzer.overlapScore(request.query(), logLine));
            chunks.add(new RetrievedChunk(
                    "log-center#" + request.targetSystemId() + "#" + index,
                    logLine,
                    request.targetSystemId(),
                    "runtime-log",
                    "log-center",
                    score,
                    Map.of("systemId", request.targetSystemId())
            ));
        }
        return new ChannelSearchResult(name(), chunks);
    }
}
