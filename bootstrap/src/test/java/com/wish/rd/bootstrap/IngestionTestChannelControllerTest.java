package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IngestionTestChannelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesDefaultDocumentIngestionPipelineTestChannel() throws Exception {
        mockMvc.perform(post("/test/ingestion/default-pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is("task-test-ingestion")))
                .andExpect(jsonPath("$.pipelineId", is("default-document-pipeline")))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.nodeTypes", hasItems("FETCHER", "PARSER", "CHUNKER", "INDEXER")))
                .andExpect(jsonPath("$.chunkCount", not(0)))
                .andExpect(jsonPath("$.indexedChunkIds", not(empty())));
    }
}
