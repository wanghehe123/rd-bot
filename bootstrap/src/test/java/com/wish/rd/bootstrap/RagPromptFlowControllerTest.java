package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagPromptFlowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesRagPromptPlanTestChannel() throws Exception {
        mockMvc.perform(post("/test/rag/prompt-flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus", is("INDEXED")))
                .andExpect(jsonPath("$.promptScene", is("REPAIR_MIXED")))
                .andExpect(jsonPath("$.rewrittenQuestion", containsString("POST /api/orders")))
                .andExpect(jsonPath("$.subQuestions.length()", is(2)))
                .andExpect(jsonPath("$.promptSections", hasItems("知识库证据", "运行日志", "代码证据", "拆分问题")))
                .andExpect(jsonPath("$.userPrompt", containsString("OrderService.create")))
                .andExpect(jsonPath("$.evidenceChunkIds", hasItems("payment-api.md#0")))
                .andExpect(jsonPath("$.traceId", not(blankOrNullString())));

        mockMvc.perform(get("/rag/traces/runs/trace-ticket-prompt-flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.traceId", is("trace-ticket-prompt-flow")))
                .andExpect(jsonPath("$.run.status", is("SUCCESS")))
                .andExpect(jsonPath("$.run.question", containsString("下单接口 500")))
                .andExpect(jsonPath("$.nodes[*].nodeType", hasItems(
                        "INGESTION",
                        "RETRIEVAL",
                        "REWRITE",
                        "PROMPT"
                )));

        mockMvc.perform(get("/rag/traces/runs/trace-ticket-prompt-flow/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nodeName", hasItems(
                        "document ingestion",
                        "repair context retrieval",
                        "query rewrite",
                        "prompt plan"
                )));
    }
}
