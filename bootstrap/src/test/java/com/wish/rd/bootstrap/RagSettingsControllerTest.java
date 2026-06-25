package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.default.collection-name=rd_bot_default",
        "rag.default.dimension=768",
        "rag.default.metric-type=COSINE",
        "rag.query-rewrite.enabled=false",
        "rag.rate-limit.global.enabled=true",
        "rag.rate-limit.global.max-concurrent=8",
        "rag.memory.history-keep-turns=6",
        "rd.ai.provider.name=deepseek",
        "rd.ai.provider.base-url=https://api.deepseek.com",
        "rd.ai.provider.api-key=abcdef1234567890"
})
@AutoConfigureMockMvc
class RagSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesRagentStyleRuntimeSettings() throws Exception {
        mockMvc.perform(get("/rag/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.maxFileSize").value(52428800))
                .andExpect(jsonPath("$.upload.maxRequestSize").value(104857600))
                .andExpect(jsonPath("$.rag.default.collectionName").value("rd_bot_default"))
                .andExpect(jsonPath("$.rag.default.dimension").value(768))
                .andExpect(jsonPath("$.rag.default.metricType").value("COSINE"))
                .andExpect(jsonPath("$.rag.queryRewrite.enabled").value(false))
                .andExpect(jsonPath("$.rag.rateLimit.global.enabled").value(true))
                .andExpect(jsonPath("$.rag.rateLimit.global.maxConcurrent").value(8))
                .andExpect(jsonPath("$.rag.memory.historyKeepTurns").value(6))
                .andExpect(jsonPath("$.ai.chat.defaultModel", not(blankOrNullString())))
                .andExpect(jsonPath("$.ai.providers.deepseek.apiKey").value("abcdef***7890"))
                .andExpect(jsonPath("$.ai.chat.candidates[0].provider").value("deepseek"));
    }
}
