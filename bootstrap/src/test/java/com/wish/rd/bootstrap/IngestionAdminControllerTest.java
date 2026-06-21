package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rd.storage.mode=memory")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class IngestionAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managesPipelinesAndExecutesIngestionTasksIntoKnowledgeWorkspace() throws Exception {
        String createdBase = mockMvc.perform(post("/knowledge-base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"支付系统","description":"支付 API 文档"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(blankOrNullString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String knowledgeBaseId = createdBase.replaceFirst(".*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        String createdPipeline = mockMvc.perform(post("/ingestion/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "支付文档摄取",
                                  "description": "默认四节点摄取管道",
                                  "nodes": [
                                    {"nodeId":"fetcher","nodeType":"FETCHER","nextNodeId":"parser"},
                                    {"nodeId":"parser","nodeType":"PARSER","nextNodeId":"chunker"},
                                    {"nodeId":"chunker","nodeType":"CHUNKER","nextNodeId":"indexer"},
                                    {"nodeId":"indexer","nodeType":"INDEXER"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(blankOrNullString())))
                .andExpect(jsonPath("$.nodes", hasSize(4)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String pipelineId = createdPipeline.replaceFirst(".*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/ingestion/pipelines")
                        .param("keyword", "支付"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.records[*].name").value(org.hamcrest.Matchers.hasItem("支付文档摄取")));

        mockMvc.perform(put("/ingestion/pipelines/" + pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"支付文档摄取 V2","description":"更新后的管道","nodes":[
                                  {"nodeId":"fetcher","nodeType":"FETCHER","nextNodeId":"parser"},
                                  {"nodeId":"parser","nodeType":"PARSER","nextNodeId":"chunker"},
                                  {"nodeId":"chunker","nodeType":"CHUNKER","nextNodeId":"indexer"},
                                  {"nodeId":"indexer","nodeType":"INDEXER"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("支付文档摄取 V2")));

        String createdTask = mockMvc.perform(post("/ingestion/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pipelineId": "%s",
                                  "knowledgeBaseId": "%s",
                                  "knowledgeType": "api",
                                  "mimeType": "text/markdown",
                                  "source": {
                                    "type": "inline",
                                    "location": "inline://payment-api.md",
                                    "fileName": "payment-api.md",
                                    "content": "# 支付 API\\n\\nOrderService.create 必须校验 orders.amount。"
                                  },
                                  "chunkingMode": "STRUCTURE_AWARE",
                                  "chunkSize": 72,
                                  "overlapSize": 8,
                                  "metadata": {"operator":"mvc"}
                                }
                                """.formatted(pipelineId, knowledgeBaseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(blankOrNullString())))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.documentId", not(blankOrNullString())))
                .andExpect(jsonPath("$.chunkCount", greaterThan(0)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = createdTask.replaceFirst(".*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        String documentId = createdTask.replaceFirst(".*?\\\"documentId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/ingestion/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceFileName", is("payment-api.md")))
                .andExpect(jsonPath("$.metadata.operator", is("mvc")));

        mockMvc.perform(get("/ingestion/tasks/" + taskId + "/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].nodeType", is("FETCHER")))
                .andExpect(jsonPath("$[3].status", is("SUCCESS")));

        mockMvc.perform(get("/knowledge-base/docs/" + documentId + "/preview"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("OrderService.create")));

        mockMvc.perform(get("/ingestion/tasks")
                        .param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)));

        mockMvc.perform(delete("/ingestion/pipelines/" + pipelineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));
    }

    @Test
    void uploadsFileToObjectStorageAndRunsDocumentChunkingTask() throws Exception {
        String createdBase = mockMvc.perform(post("/knowledge-base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"上传知识库","description":"文件上传验收"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String knowledgeBaseId = createdBase.replaceFirst(".*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "payment-upload.md",
                "text/markdown",
                """
                # 上传文档

                RustFS 上传后的文档也要进入 Parser、Chunker 和 Indexer。
                OrderService.create 校验 orders.amount。
                """.getBytes(StandardCharsets.UTF_8)
        );

        String createdTask = mockMvc.perform(multipart("/ingestion/tasks/upload")
                        .file(file)
                        .param("pipelineId", "default-document-pipeline")
                        .param("knowledgeBaseId", knowledgeBaseId)
                        .param("knowledgeType", "api")
                        .param("chunkingMode", "STRUCTURE_AWARE")
                        .param("chunkSize", "72")
                        .param("overlapSize", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.sourceType", is("s3")))
                .andExpect(jsonPath("$.sourceLocation", startsWith("s3://biz/")))
                .andExpect(jsonPath("$.metadata.storageUrl", startsWith("s3://biz/")))
                .andExpect(jsonPath("$.chunkCount", greaterThan(0)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String documentId = createdTask.replaceFirst(".*?\\\"documentId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/knowledge-base/docs/" + documentId + "/preview"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RustFS 上传后的文档")));

        mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "RustFS 上传后的文档 Parser Chunker Indexer")
                        .param("conversationId", "conversation-upload-rag-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event: meta")))
                .andExpect(content().string(containsString("知识库证据")));
    }
}
