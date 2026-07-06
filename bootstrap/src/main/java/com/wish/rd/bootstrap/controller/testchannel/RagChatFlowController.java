package com.wish.rd.bootstrap.controller.testchannel;

import com.wish.rd.engine.testflow.RagChatFlowTestEngine;
import com.wish.rd.engine.testflow.model.RagChatFlowTestResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 聊天流程测试通道控制器。
 *
 * <p>对外暴露 /test/rag/chat-flow 接口，触发聊天流程冒烟测试，
 * 委托 {@link RagChatFlowTestEngine} 执行并返回结果。
 */
@RestController
public class RagChatFlowController {

    @PostMapping("/test/rag/chat-flow")
    public RagChatFlowTestResult chatFlow() {
        return new RagChatFlowTestEngine().run();
    }
}
