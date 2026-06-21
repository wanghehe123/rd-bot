package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagTestChannelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesFullFlowTestChannel() throws Exception {
        mockMvc.perform(post("/test/rag/full-flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus", is("INDEXED")))
                .andExpect(jsonPath("$.guidanceAction", is("NONE")))
                .andExpect(jsonPath("$.nodeTypes", hasItems("PARSER", "CHUNKER", "INDEXER")))
                .andExpect(jsonPath("$.searchChannels", hasItems(
                        "IntentDirectedVectorSearch",
                        "KeywordBM25Search",
                        "LogCenterSearch",
                        "CodeRepositorySearch"
                )))
                .andExpect(jsonPath("$.retrievedKnowledgeTypes", hasItems(
                        "api",
                        "runtime-log",
                        "code-snippet"
                )));
    }
}
