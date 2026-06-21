package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesKnowledgeDocumentChunkPreviewToggleAndAdminOverviewEndpoints() throws Exception {
        mockMvc.perform(post("/knowledge-base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "支付系统",
                                  "description": "支付接口、订单异常和修复经验"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("kb-1")))
                .andExpect(jsonPath("$.name", is("支付系统")));

        mockMvc.perform(get("/knowledge-base")
                        .param("current", "1")
                        .param("size", "10")
                        .param("name", "支付"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].id", is("kb-1")))
                .andExpect(jsonPath("$.records[0].name", is("支付系统")))
                .andExpect(jsonPath("$.records[0].documentCount", is(0)))
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.current", is(1)))
                .andExpect(jsonPath("$.pages", is(1)));

        mockMvc.perform(get("/knowledge-base/kb-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("kb-1")))
                .andExpect(jsonPath("$.name", is("支付系统")));

        mockMvc.perform(post("/knowledge-base/kb-1/docs/write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceName": "payment-api.md",
                                  "knowledgeType": "api",
                                  "mimeType": "text/markdown",
                                  "content": "# 支付系统 API\\n\\nOrderService.create 处理下单接口 500。",
                                  "chunkingMode": "STRUCTURE_AWARE",
                                  "chunkSize": 72,
                                  "overlapSize": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("doc-1")))
                .andExpect(jsonPath("$.status", is("INDEXED")))
                .andExpect(jsonPath("$.knowledgeBaseId", is("kb-1")));

        mockMvc.perform(get("/knowledge-base/kb-1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceName", is("payment-api.md")));

        mockMvc.perform(get("/knowledge-base/docs/doc-1/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].enabled", is(true)));

        mockMvc.perform(get("/knowledge-base/docs/doc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("doc-1")))
                .andExpect(jsonPath("$.sourceName", is("payment-api.md")));

        mockMvc.perform(get("/knowledge-base/docs/search")
                        .param("keyword", "OrderService")
                        .param("limit", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is("doc-1")));

        mockMvc.perform(get("/knowledge-base/docs/doc-1/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", containsString("OrderService.create")));

        mockMvc.perform(get("/knowledge-base/docs/doc-1/chunk-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].nodeType", is("FETCHER")))
                .andExpect(jsonPath("$[1].nodeType", is("PARSER")))
                .andExpect(jsonPath("$[2].nodeType", is("CHUNKER")))
                .andExpect(jsonPath("$[3].nodeType", is("INDEXER")));

        mockMvc.perform(patch("/knowledge-base/docs/chunks/doc-1-0/enabled")
                        .param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)));

        mockMvc.perform(post("/knowledge-base/docs/doc-1/chunks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chunkId":"manual-rest","index":99,"content":"手工新增 Chunk：orders.amount 校验"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("manual-rest")))
                .andExpect(jsonPath("$.content", containsString("手工新增")));

        mockMvc.perform(put("/knowledge-base/docs/doc-1/chunks/manual-rest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"手工更新 Chunk：金额为空返回参数错误"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", containsString("参数错误")));

        mockMvc.perform(patch("/knowledge-base/docs/doc-1/chunks/batch-enable")
                        .param("value", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chunkIds":["manual-rest"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(1)));

        mockMvc.perform(get("/knowledge-base/docs/doc-1/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'manual-rest')].enabled").value(hasSize(1)))
                .andExpect(jsonPath("$[?(@.id == 'manual-rest')].enabled").value(hasItem(false)));

        mockMvc.perform(delete("/knowledge-base/docs/doc-1/chunks/manual-rest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));

        mockMvc.perform(put("/knowledge-base/docs/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docName":"payment-api-v2.md","knowledgeType":"api-v2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceName", is("payment-api-v2.md")))
                .andExpect(jsonPath("$.knowledgeType", is("api-v2")));

        mockMvc.perform(get("/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledgeBaseCount", is(1)))
                .andExpect(jsonPath("$.documentCount", is(1)))
                .andExpect(jsonPath("$.indexedDocumentCount", is(1)))
                .andExpect(jsonPath("$.chunkCount", greaterThan(0)));

        mockMvc.perform(delete("/knowledge-base/docs/doc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));

        mockMvc.perform(get("/knowledge-base/kb-1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(put("/knowledge-base/kb-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"支付知识库"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("支付知识库")));

        mockMvc.perform(delete("/knowledge-base/kb-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));

        mockMvc.perform(get("/knowledge-base")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(0)))
                .andExpect(jsonPath("$.total", is(0)));
    }
}
