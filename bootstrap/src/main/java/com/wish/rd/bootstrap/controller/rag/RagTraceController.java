package com.wish.rd.bootstrap.controller.rag;

import com.wish.rd.rag.trace.RagTraceDetail;
import com.wish.rd.rag.trace.RagTraceNodeView;
import com.wish.rd.rag.trace.RagTraceRunView;
import com.wish.rd.rag.trace.RagTraceStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 链路追踪查询控制器。
 *
 * <p>对外暴露 /rag/traces 系列接口，提供 run 列表、单个 run 详情、节点列表查询，
 * 直接委托内存追踪存储 {@link RagTraceStore}。
 */
@RestController
public class RagTraceController {

    private final RagTraceStore traceStore;

    public RagTraceController(RagTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/rag/traces/runs")
    public List<RagTraceRunView> runs() {
        return traceStore.listRuns();
    }

    @GetMapping("/rag/traces/runs/{traceId}")
    public RagTraceDetail detail(@PathVariable("traceId") String traceId) {
        return traceStore.detail(traceId);
    }

    @GetMapping("/rag/traces/runs/{traceId}/nodes")
    public List<RagTraceNodeView> nodes(@PathVariable("traceId") String traceId) {
        return traceStore.listNodes(traceId);
    }
}
