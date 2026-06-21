package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void returnsRejectEventWithoutWritingConversationWhenGlobalLimitIsFull() throws Exception {
        mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "系统繁忙时也只返回任务级拒绝"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(containsString("event: meta")))
                .andExpect(content().string(containsString("\"taskId\":\"")))
                .andExpect(content().string(containsString("event: reject")))
                .andExpect(content().string(containsString("系统繁忙，请稍后再试")))
                .andExpect(content().string(containsString("event: done")));
    }
}
