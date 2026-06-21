package com.wish.rd.bootstrap.controller.testchannel;

import com.wish.rd.engine.testflow.RagFullFlowTestEngine;
import com.wish.rd.engine.testflow.RagFullFlowTestResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 全流程测试通道控制器。
 *
 * <p>对外暴露 /test/rag/full-flow 接口，触发端到端全流程冒烟测试，
 * 委托 {@link RagFullFlowTestEngine} 执行并返回结果。
 */
@RestController
public class RagTestChannelController {

    @PostMapping("/test/rag/full-flow")
    public RagFullFlowTestResult fullFlow() {
        return new RagFullFlowTestEngine().run();
    }
}
