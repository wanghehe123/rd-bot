package com.wish.rd.bootstrap.controller.testchannel;

import com.wish.rd.engine.testflow.RagPromptFlowTestEngine;
import com.wish.rd.engine.testflow.model.RagPromptFlowTestResult;
import com.wish.rd.rag.trace.RagTraceStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG Prompt 流程测试通道控制器。
 *
 * <p>对外暴露 /test/rag/prompt-flow 接口，触发 Prompt 构造流程冒烟测试，
 * 委托 {@link RagPromptFlowTestEngine} 执行，并向其注入 {@link RagTraceStore}
 * 用于追踪记录。
 */
@RestController
public class RagPromptFlowController {

    private final RagTraceStore traceStore;

    public RagPromptFlowController(RagTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @PostMapping("/test/rag/prompt-flow")
    public RagPromptFlowTestResult promptFlow() {
        return new RagPromptFlowTestEngine(traceStore).run();
    }
}
