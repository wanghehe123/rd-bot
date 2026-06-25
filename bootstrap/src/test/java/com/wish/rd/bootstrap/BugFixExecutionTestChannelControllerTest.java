package com.wish.rd.bootstrap;

import com.wish.rd.engine.bugfix.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.BugFixExecutor;
import com.wish.rd.engine.bugfix.BugFixPromptBuilder;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.acceptance.RagEvidenceAcceptancePlanGenerator;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BugFixExecutionTestChannelControllerTest {

    @Test
    void runBugFixEndpointInvokesRdBotFixEngineAndReturnsExecutionResult() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new com.wish.rd.bootstrap.controller.testchannel.BugFixExecutionTestChannelController(
                        engine()
                ))
                .build();

        mockMvc.perform(post("/test/repair/bugfix/run")
                        .contentType("application/json")
                        .content("""
                                {
                                  "ticketId": "FS-EXEC-1",
                                  "title": "支付系统下单接口 500",
                                  "description": "金额为空时 OrderService.create 写入订单失败",
                                  "labels": ["payment", "orders.amount"],
                                  "logs": ["ERROR orders.amount is null at OrderService.create"],
                                  "priority": "P1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.rejected").value(false))
                .andExpect(jsonPath("$.rag.ticketId").value("FS-EXEC-1"))
                .andExpect(jsonPath("$.rag.retrievedChunkCount", greaterThan(0)))
                .andExpect(jsonPath("$.rag.contextSummary").exists())
                .andExpect(jsonPath("$.acceptancePlan.status").value("READY"))
                .andExpect(jsonPath("$.acceptancePlan.source").value("rd-bot-rag-evidence"))
                .andExpect(jsonPath("$.acceptancePlan.planJson").exists())
                .andExpect(jsonPath("$.execution.pullRequestUrl").value("https://github.example/rd/pr/exec-1"))
                .andExpect(jsonPath("$.execution.solution").value("executor invoked"));
    }

    private RdBotFixEngine engine() {
        RagStreamTaskRegistry registry = registry();
        return new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                new RagBugFixEngine(
                        QueryTermMappingRegistry.withDefaults(),
                        IntentTreeRegistry.withDefaults(),
                        null,
                        registry,
                        ChatQueueLimiter.passThrough()
                ),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor(),
                new RagEvidenceAcceptancePlanGenerator()
        );
    }

    private BugFixExecutor executor() {
        return request -> new BugFixExecutionResult(
                request.taskId(),
                "bug identified",
                "executor invoked",
                "https://github.example/rd/pr/exec-1",
                "{\"status\":\"SUCCESS\"}"
        );
    }

    private RagStreamTaskRegistry registry() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement)
        );
    }
}
