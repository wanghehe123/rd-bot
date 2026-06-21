package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.rate-limit.global.enabled=true",
        "rag.rate-limit.global.max-concurrent=0"
})
@AutoConfigureMockMvc
class RagV3ChatRateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsRejectEventAndRecordsConversationWhenGlobalLimitIsFull() throws Exception {
        String conversationId = "conversation-rate-limit-test";

        mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "系统繁忙时也要记录用户问题")
                        .param("conversationId", conversationId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(containsString("event: meta")))
                .andExpect(content().string(containsString("event: reject")))
                .andExpect(content().string(containsString("系统繁忙，请稍后再试")))
                .andExpect(content().string(containsString("event: done")));

        mockMvc.perform(get("/conversations/{conversationId}/messages", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("系统繁忙时也要记录用户问题"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("系统繁忙，请稍后再试"));
    }
}
