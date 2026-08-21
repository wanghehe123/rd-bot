package com.wish.rd.bootstrap.feishu.im;

import com.wish.rd.bootstrap.feishu.im.impl.FeishuImLocalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证本地 lark-cli 事件流可以复用现有飞书 IM 需求解析逻辑。
 */
class FeishuImLocalEventListenerTest {

    @Test
    void shouldConvertFlatLarkCliMessageEventIntoARequirementTask() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(false);
        properties.getLocalListener().setEnabled(true);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(
                objectMapper, properties, requirementController(objectMapper, properties, registry));

        listener.handleEventLine("""
                {
                  "type": "im.message.receive_v1",
                  "event_id": "evt-local",
                  "message_id": "om_local",
                  "chat_id": "oc_local",
                  "chat_type": "group",
                  "message_type": "text",
                  "sender_id": "ou_local",
                  "timestamp": "1782298004000",
                  "content": "做需求\\n标题: 增加订单催单功能\\n仓库: https://github.com/example/waimai.git\\n分支: main\\n优先级: P1\\n需求: 用户可以在订单详情页点击催单。\\n预期结果: 订单详情页可以催单"
                }
                """);

        assertEquals(1, registry.listTasks().size());
        RdRequirementTask created = registry.getRequirementTask(registry.listTasks().getFirst().taskId());
        assertEquals("FEISHU_IM", created.sourceType());
        assertEquals("om_local", created.sourceId());
    }

    @Test
    void shouldConvertFlatLarkCliPostMessageEventIntoARequirementTask() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(false);
        properties.getLocalListener().setEnabled(true);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(
                objectMapper, properties, requirementController(objectMapper, properties, registry));

        listener.handleEventLine("""
                {
                  "type": "im.message.receive_v1",
                  "event_id": "evt-local-post",
                  "message_id": "om_local_post",
                  "chat_id": "oc_local",
                  "chat_type": "p2p",
                  "message_type": "post",
                  "sender_id": "ou_local",
                  "timestamp": "1782298004000",
                  "content": "做需求\\n标题: 支持 ESM 启动\\n仓库: https://github.com/example/waimai.git\\n分支: main\\n优先级: P0\\n需求: 后端需要在 Node ESM 环境下启动。\\n预期结果: 服务在 ESM 下正常启动"
                }
                """);

        assertEquals(1, registry.listTasks().size());
        assertEquals("om_local_post",
                registry.getRequirementTask(registry.listTasks().getFirst().taskId()).sourceId());
    }

    @Test
    void shouldIgnoreFlatLarkCliMessageThatIsNotARequirement() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(false);
        properties.getLocalListener().setEnabled(true);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(
                objectMapper, properties, requirementController(objectMapper, properties, registry));

        listener.handleEventLine("""
                {
                  "type": "im.message.receive_v1",
                  "event_id": "evt-local-chatter",
                  "message_id": "om_local_chatter",
                  "chat_id": "oc_local",
                  "chat_type": "group",
                  "message_type": "text",
                  "sender_id": "ou_local",
                  "timestamp": "1782298004000",
                  "content": "问题: 下单接口 500\\n日志: NPE"
                }
                """);

        assertTrue(registry.listTasks().isEmpty(), "非需求消息不得再落成任何任务");
    }

    @Test
    void shouldBuildHttpEnvelopeFromLarkCliFlatEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(
                objectMapper, properties, inertController(objectMapper, properties));

        String envelope = listener.toHttpEnvelope("""
                {"type":"im.message.receive_v1","event_id":"evt-1","message_id":"om_1","chat_id":"oc_1","chat_type":"p2p","message_type":"text","sender_id":"ou_1","content":"hello"}
                """);

        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(envelope);
        assertEquals("im.message.receive_v1", node.path("header").path("event_type").asText());
        assertEquals("om_1", node.path("event").path("message").path("message_id").asText());
        assertEquals("oc_1", node.path("event").path("message").path("chat_id").asText());
        assertEquals("{\"text\":\"hello\"}", node.path("event").path("message").path("content").asText());
    }

    @Test
    void shouldUseConfiguredLarkCliProfileWhenConsumingEvents() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        properties.getLocalListener().setProfile("cli_test_app_id");
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(
                objectMapper, properties, inertController(objectMapper, properties));

        assertEquals(List.of(
                "lark-cli",
                "--profile",
                "cli_test_app_id",
                "event",
                "consume",
                "im.message.receive_v1",
                "--as",
                "bot"
        ), listener.command());
    }

    @Test
    void shouldStopRetryingWhenRemoteEventBusAlreadyOwnsTheApp() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(
                objectMapper, properties, inertController(objectMapper, properties));

        assertTrue(listener.shouldStopAfterExit(2, List.of(
                "{",
                "  \"ok\": false,",
                "  \"error\": {",
                "    \"type\": \"validation\",",
                "    \"subtype\": \"failed_precondition\",",
                "    \"message\": \"another event bus is already connected to this app (1 remote event connection(s) detected via API); only one bus should run globally\"",
                "  }",
                "}"
        )));
    }

    /** Accepts requirements into {@code registry} without running the delivery pipeline. */
    private static FeishuImMessageController requirementController(
            ObjectMapper objectMapper,
            FeishuImProperties properties,
            RagStreamTaskRegistry registry
    ) {
        RequirementDeliveryDispatchService dispatchService = mock(RequirementDeliveryDispatchService.class);
        when(dispatchService.submit(anyString())).thenAnswer(invocation -> CompletableFuture.completedFuture(null));
        return new FeishuImMessageController(
                objectMapper,
                properties,
                registry,
                new InMemoryTaskMaterialStore(),
                mock(RequirementDeliveryEngine.class),
                dispatchService,
                generator()
        );
    }

    /** For cases that only exercise listener plumbing and never reach requirement handling. */
    private static FeishuImMessageController inertController(
            ObjectMapper objectMapper,
            FeishuImProperties properties
    ) {
        return new FeishuImMessageController(objectMapper, properties, null, null, null, null, generator());
    }

    private static SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
