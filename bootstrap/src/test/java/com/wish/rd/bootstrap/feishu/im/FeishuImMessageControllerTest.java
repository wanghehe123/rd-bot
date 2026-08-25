package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证飞书 IM 事件入口对 URL 校验、@ 过滤和需求建单的处理。
 *
 * <p>工单链已下线，非需求格式的消息只被忽略，不再有第二个消费者。
 */
class FeishuImMessageControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(true);
        FeishuImMessageController controller = new FeishuImMessageController(
                new ObjectMapper(),
                properties,
                null,
                null,
                null,
                null,
                generator()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturnChallengeForUrlVerification() throws Exception {
        mockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content("{\"type\":\"url_verification\",\"challenge\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("c1"));
    }

    @Test
    void shouldIgnoreGroupTextWithoutMentionWhenMentionRequired() throws Exception {
        mockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content(eventJson("evt-no-mention", "om-no-mention", "", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.ignored").value(true))
                .andExpect(jsonPath("$.reason").value("bot not mentioned"));
    }

    @Test
    void shouldIgnoreMentionedTextThatIsNotARequirement() throws Exception {
        mockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content(eventJson("evt-1", "om_123", "@_user_1 ", List.of("@_user_1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.ignored").value(true))
                .andExpect(jsonPath("$.reason").value("not a requirement message"))
                .andExpect(jsonPath("$.messageId").value("om_123"));
    }

    @Test
    void shouldCreateRequirementTaskFromMentionedText() throws Exception {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(true);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RequirementDeliveryEngine deliveryEngine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "需求评审通过",
                                "",
                                """
                                        {
                                          "decision":"APPROVED",
                                          "feasibility":"CAN_DO",
                                          "missingInformation":[],
                                          "risks":[],
                                          "acceptanceCoverage":["前端构建通过"],
                                          "budgetEstimate":{
                                            "initialTokens":1000,
                                            "retryReserveTokens":500,
                                            "estimatedTotalTokens":1500,
                                            "confidence":"LOW",
                                            "basis":"controller fixture",
                                            "historicalSamples":[]
                                          }
                                        }
                                        """
                        );
                    }
                    if (request.role() == AgentRole.SOLUTION_ARCHITECT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "方案完成",
                                "",
                                """
                                        {
                                          "summary":"新增订单催单按钮",
                                          "affectedFiles":["client/src/pages/OrderDetail.tsx"],
                                          "implementationSteps":["实现按钮"],
                                          "acceptanceMapping":[{"criteria":"前端构建通过","validation":"npm run build"}],
                                          "testPlan":[{"criteria":"前端构建通过","command":"npm run build"}]
                                        }
                                        """
                        );
                    }
                    if (request.role() == AgentRole.QA_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "QA 验收通过",
                                "",
                                """
                                        {
                                          "status": "PASSED",
                                          "summary": "QA 验收通过",
                                          "failureCategory": "NONE",
                                          "retryRecommendation": "NONE",
                                          "browserValidation": {
                                            "required": false,
                                            "performed": false,
                                            "decisionSource": "NOT_APPLICABLE",
                                            "baseUrl": "",
                                            "browser": "chromium",
                                            "viewports": []
                                          },
                                          "acceptanceResults": [
                                            {"criteria":"前端构建通过","scope":"CURRENT","command":"npm run build","status":"PASSED","exitCode":0,"durationMillis":100,"logArtifactId":"qa-evidence/commands/current-build.log","evidenceArtifactIds":["qa-evidence/commands/current-build.log"]},
                                            {"criteria":"既有功能回归","scope":"REGRESSION","command":"npm test","status":"PASSED","exitCode":0,"durationMillis":120,"logArtifactId":"qa-evidence/commands/regression.log","evidenceArtifactIds":["qa-evidence/commands/regression.log"]}
                                          ],
                                          "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            "实现完成",
                            "",
                            """
                                    {
                                      "status": "SUCCESS",
                                      "summary": "实现完成",
                                      "prBody": "## 改动介绍\\n- 新增订单催单按钮",
                                      "changedFiles": ["client/src/pages/OrderDetail.tsx"],
                                      "testCommands": ["npm run build"],
                                      "testStatus": "PASSED",
                                      "riskLevel": "LOW"
                                    }
                                    """
                    );
                },
                command -> RequirementPullRequestPublication.success(
                        command.taskId(),
                        "https://github.com/example/waimai/pull/22",
                        "22",
                        "{\"provider\":\"feishu-im-test\"}"
                )
        );
        RequirementDeliveryDispatchService dispatchService = mock(RequirementDeliveryDispatchService.class);
        when(dispatchService.submit(anyString())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                deliveryEngine.submit(invocation.getArgument(0))));
        FeishuImMessageController controller = new FeishuImMessageController(
                new ObjectMapper(),
                properties,
                registry,
                materialStore,
                deliveryEngine,
                dispatchService,
                generator()
        );
        MockMvc localMockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String text = """
                @_user_1 做需求
                标题: 增加订单催单功能
                仓库: https://github.com/example/waimai.git
                分支: main
                优先级: P1
                需求: 用户可以在订单详情页点击催单。
                预期结果: 订单详情页可以催单
                验收: 前端构建通过
                """;

        String response = localMockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content(eventJsonWithText("evt-req", "om_req_1", text, List.of("@_user_1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.taskType").value("REQUIREMENT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.pullRequestUrl").value("https://github.com/example/waimai/pull/22"))
                .andExpect(jsonPath("$.dispatched").value(true))
                .andReturn().getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(response, "$.taskId");

        assertEquals(RdTaskStatus.COMPLETED, registry.getTask(taskId).status());
        assertEquals(List.of(
                RdTaskStatus.CREATED.name(),
                RdTaskStatus.MATERIAL_COLLECTING.name(),
                RdTaskStatus.MATERIAL_READY.name(),
                RdTaskStatus.CONTEXT_BUILDING.name(),
                RdTaskStatus.CONTEXT_READY.name(),
                RdTaskStatus.PLAN_GENERATING.name(),
                RdTaskStatus.PLAN_GENERATED.name(),
                RdTaskStatus.WAITING_POLICY.name(),
                RdTaskStatus.EXECUTING.name(),
                RdTaskStatus.VALIDATING.name(),
                RdTaskStatus.PR_CREATING.name(),
                RdTaskStatus.COMMITTED.name(),
                RdTaskStatus.REPORTING.name(),
                RdTaskStatus.COMPLETED.name()
        ), registry.timeline(taskId).stream().map(event -> event.status()).toList());
        RdRequirementTask saved = registry.getRequirementTask(taskId);
        assertEquals("FEISHU_IM", saved.sourceType());
        assertEquals("om_req_1", saved.sourceId());
        assertEquals(1, materialStore.listByTask(taskId).size());
    }

    @Test
    void shouldFailClosedWhenRequirementDispatchServiceIsUnavailable() throws Exception {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(true);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RequirementDeliveryEngine deliveryEngine = mock(RequirementDeliveryEngine.class);
        FeishuImMessageController controller = new FeishuImMessageController(
                new ObjectMapper(),
                properties,
                registry,
                materialStore,
                deliveryEngine,
                null,
                generator()
        );
        MockMvc localMockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String text = """
                @_user_1 做需求
                标题: 增加订单催单功能
                仓库: https://github.com/example/waimai.git
                分支: main
                优先级: P1
                需求: 用户可以在订单详情页点击催单。
                预期结果: 订单详情页可以催单
                验收: 前端构建通过
                """;

        localMockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content(eventJsonWithText("evt-req-no-dispatch", "om_req_no_dispatch", text,
                                List.of("@_user_1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("requirement delivery is unavailable"));

        org.mockito.Mockito.verify(deliveryEngine, org.mockito.Mockito.never()).submit(anyString());
        assertTrue(registry.listTasks().isEmpty());
    }

    @Test
    void shouldAskForMissingRequirementFieldsWithoutCreatingTask() throws Exception {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(true);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RequirementDeliveryEngine deliveryEngine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    throw new AssertionError("missing fields must not trigger requirement execution");
                }
        );
        FeishuImMessageController controller = new FeishuImMessageController(
                new ObjectMapper(),
                properties,
                registry,
                materialStore,
                deliveryEngine,
                null,
                generator()
        );
        MockMvc localMockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String text = """
                @_user_1 做需求
                标题: 增加订单催单功能
                分支: main
                需求: 用户可以在订单详情页点击催单。
                验收: 前端构建通过
                """;

        localMockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content(eventJsonWithText("evt-req-missing", "om_req_missing", text, List.of("@_user_1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.taskType").value("REQUIREMENT"))
                .andExpect(jsonPath("$.status").value("NEED_INFO"))
                .andExpect(jsonPath("$.missingFields[0]").value("repositoryUrl"))
                .andExpect(jsonPath("$.missingFields[1]").value("expectedResult"));

        assertEquals(0, registry.queryTasks(new com.wish.rd.rag.runtime.model.RdTaskQuery(
                "REQUIREMENT",
                "",
                "",
                "",
                "",
                1,
                20
        )).total());
    }

    private static String eventJson(String eventId, String messageId, String prefix, List<String> mentionKeys) {
        String mentions = mentionKeys.stream()
                .map(key -> "{\"key\":\"" + key + "\",\"id\":{\"open_id\":\"ou-bot\"}}")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String text = prefix + "系统: waimai\\n仓库: github.com/example/waimai\\n分支: main\\n优先级: P1\\n问题: 下单接口 500\\n日志: NPE";
        String escapedTextJson = "{\\\"text\\\":\\\"" + text + "\\\"}";
        return """
                {
                  "schema": "2.0",
                  "header": {
                    "event_id": "%s",
                    "event_type": "im.message.receive_v1",
                    "create_time": "1782190000000"
                  },
                  "event": {
                    "sender": {"sender_id": {"open_id": "ou-user"}},
                    "message": {
                      "message_id": "%s",
                      "chat_id": "oc-chat",
                      "chat_type": "group",
                      "message_type": "text",
                      "content": "%s",
                      "mentions": [%s]
                    }
                  }
                }
                """.formatted(eventId, messageId, escapedTextJson, mentions);
    }

    private static String eventJsonWithText(String eventId, String messageId, String text, List<String> mentionKeys) {
        String mentions = mentionKeys.stream()
                .map(key -> "{\"key\":\"" + key + "\",\"id\":{\"open_id\":\"ou-bot\"}}")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String escapedText = text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        String escapedTextJson = "{\\\"text\\\":\\\"" + escapedText + "\\\"}";
        return """
                {
                  "schema": "2.0",
                  "header": {
                    "event_id": "%s",
                    "event_type": "im.message.receive_v1",
                    "create_time": "1782190000000"
                  },
                  "event": {
                    "sender": {"sender_id": {"open_id": "ou-user"}},
                    "message": {
                      "message_id": "%s",
                      "chat_id": "oc-chat",
                      "chat_type": "group",
                      "message_type": "text",
                      "content": "%s",
                      "mentions": [%s]
                    }
                  }
                }
                """.formatted(eventId, messageId, escapedTextJson, mentions);
    }

    private static SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
