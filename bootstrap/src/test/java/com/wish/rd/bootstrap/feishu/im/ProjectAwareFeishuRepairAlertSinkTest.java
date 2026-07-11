package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.alert.RdAlertDeliveryStore;
import com.wish.rd.rag.project.alert.RdProjectAlertConfigService;
import com.wish.rd.rag.project.alert.RdProjectAlertConfigStore;
import com.wish.rd.rag.project.alert.model.RdAlertDelivery;
import com.wish.rd.rag.project.alert.model.RdAlertDeliveryStatus;
import com.wish.rd.rag.project.alert.model.RdAlertRecipient;
import com.wish.rd.rag.project.alert.model.RdAlertRecipientType;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfigCommand;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAwareFeishuRepairAlertSinkTest {

    @Test
    void shouldRouteToProjectRecipientsAuditAndDeduplicate() {
        String taskId = "7482000000000000301";
        String projectId = "7482000000000000302";
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveBugFixTask(new RdBugFixTask(
                taskId, "BUG_FIX", "ticket-1", "bug", "P1", RdTaskStatus.EXECUTING,
                "message-1", "bug", "prompt", "", "", "", projectId, "demo", "Demo project",
                "https://github.com/example/demo", "example", "demo", "main",
                1_783_200_000_000L, 1_783_200_000_000L, false
        ));
        InMemoryConfigStore configStore = new InMemoryConfigStore();
        RdProjectAlertConfigService configService = new RdProjectAlertConfigService(configStore);
        configService.update(projectId, new RdProjectAlertConfigCommand(
                true,
                List.of(
                        new RdAlertRecipient(RdAlertRecipientType.CHAT_ID, "oc-project"),
                        new RdAlertRecipient(RdAlertRecipientType.OPEN_ID, "ou-project")
                ),
                Set.of(RdProjectAlertEventType.QA_FAILED),
                new BigDecimal("5.00"),
                2
        ));
        InMemoryDeliveryStore deliveryStore = new InMemoryDeliveryStore();
        FeishuImProperties properties = properties();
        StubTransport transport = new StubTransport();
        transport.successfulMessage("om-chat");
        transport.successfulMessage("om-open");
        FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                new FeishuImClient(properties, new ObjectMapper(), transport),
                properties,
                taskStore,
                configService,
                deliveryStore,
                generator()
        );
        RepairAlert alert = new RepairAlert(
                "stage-qa-1", taskId, RepairAlertType.QA_FAILED,
                "QA failed token=secret", Map.of("stage", "QA", "nextAction", "人工复核"),
                1_783_200_002_000L
        );

        sink.publish(alert);
        sink.publish(alert);

        assertEquals(2, deliveryStore.listByTask(taskId).size());
        assertEquals(4, transport.requests.size());
        assertTrue(transport.requests.stream().anyMatch(request -> request.uri().toString().contains("receive_id_type=chat_id")));
        assertTrue(transport.requests.stream().anyMatch(request -> request.uri().toString().contains("receive_id_type=open_id")));
        assertTrue(transport.bodies.stream()
                .filter(body -> body.contains("receive_id"))
                .noneMatch(body -> body.contains("secret")));
        assertTrue(deliveryStore.listByTask(taskId).stream()
                .allMatch(delivery -> delivery.status() == RdAlertDeliveryStatus.SENT));
    }

    @Test
    void shouldSkipProviderCallWhenAuditReservationFails() {
        TestContext context = contextWithConfig(Set.of(RdProjectAlertEventType.TASK_COMPLETED), new BigDecimal("5"));
        RdAlertDeliveryStore failingStore = new RdAlertDeliveryStore() {
            @Override public RdAlertDelivery save(RdAlertDelivery delivery) { throw new IllegalStateException("db down"); }
            @Override public RdAlertDelivery updateOutcome(RdAlertDelivery delivery) { return delivery; }
            @Override public Optional<RdAlertDelivery> findByIdempotencyKey(String key) { return Optional.empty(); }
            @Override public List<RdAlertDelivery> listByTask(String taskId) { return List.of(); }
        };
        StubTransport transport = new StubTransport();
        FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                new FeishuImClient(context.properties(), new ObjectMapper(), transport), context.properties(),
                context.taskStore(), context.configService(), failingStore, generator());

        sink.publish(new RepairAlert(
                "record-audit-failure", context.taskId(), RepairAlertType.TASK_COMPLETED,
                "done", Map.of(), 1_783_200_002_000L));

        assertTrue(transport.requests.isEmpty());
        assertTrue(sink.deliveryAttempts().stream()
                .anyMatch(attempt -> attempt.code().equals("AUDIT_RESERVATION_FAILED")));
    }

    @Test
    void shouldUseProjectBudgetThresholdEvenWhenItExceedsGlobalThreshold() {
        TestContext context = contextWithConfig(Set.of(RdProjectAlertEventType.BUDGET_EXCEEDED), new BigDecimal("36.0000"));
        InMemoryDeliveryStore deliveryStore = new InMemoryDeliveryStore();
        StubTransport transport = new StubTransport();
        transport.successfulMessage("om-budget");
        FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                new FeishuImClient(context.properties(), new ObjectMapper(), transport), context.properties(),
                context.taskStore(), context.configService(), deliveryStore, generator());

        sink.observe("record-budget", context.taskId(), new BigDecimal("40.0000"), new BigDecimal("36.0000"),
                1_783_200_002_000L);

        assertEquals(2, transport.requests.size());
        assertEquals(1, deliveryStore.listByTask(context.taskId()).size());
        assertEquals(RdAlertDeliveryStatus.SENT, deliveryStore.listByTask(context.taskId()).get(0).status());
    }

    @Test
    void shouldRenderCnyBudgetValuesInProjectAlert() {
        TestContext context = contextWithConfig(Set.of(RdProjectAlertEventType.BUDGET_EXCEEDED), new BigDecimal("36.0000"));
        InMemoryDeliveryStore deliveryStore = new InMemoryDeliveryStore();
        StubTransport transport = new StubTransport();
        transport.successfulMessage("om-budget-cny");
        FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                new FeishuImClient(context.properties(), new ObjectMapper(), transport), context.properties(),
                context.taskStore(), context.configService(), deliveryStore, generator());

        sink.observe("record-budget-cny", context.taskId(), new BigDecimal("36.0000"), new BigDecimal("36.0000"),
                1_783_200_002_000L);

        assertTrue(transport.bodies.stream()
                .anyMatch(body -> body.contains("estimatedSpendCny: ¥36.0000")));
    }

    @Test
    void shouldRetryPendingAuditWithTheSameProviderUuid() {
        TestContext context = contextWithConfig(Set.of(RdProjectAlertEventType.TASK_COMPLETED), new BigDecimal("5"));
        InMemoryDeliveryStore deliveryStore = new InMemoryDeliveryStore(1);
        StubTransport transport = new StubTransport();
        transport.successfulMessage("om-first");
        transport.successfulMessage("om-retry");
        FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                new FeishuImClient(context.properties(), new ObjectMapper(), transport), context.properties(),
                context.taskStore(), context.configService(), deliveryStore, generator());
        RepairAlert alert = new RepairAlert(
                "record-pending", context.taskId(), RepairAlertType.TASK_COMPLETED,
                "done", Map.of(), 1_783_200_002_000L);

        sink.publish(alert);
        assertEquals(RdAlertDeliveryStatus.PENDING, deliveryStore.listByTask(context.taskId()).get(0).status());
        sink.publish(alert);

        assertEquals(RdAlertDeliveryStatus.SENT, deliveryStore.listByTask(context.taskId()).get(0).status());
        List<String> messageBodies = transport.bodies.stream().filter(body -> body.contains("\"msg_type\"")).toList();
        assertEquals(2, messageBodies.size());
        assertEquals(1, messageBodies.stream()
                .map(body -> body.replaceAll(".*\"uuid\":\"([^\"]+)\".*", "$1"))
                .distinct().count());
        assertTrue(sink.deliveryAttempts().stream()
                .anyMatch(attempt -> attempt.code().equals("AUDIT_OUTCOME_FAILED")));
        assertTrue(sink.deliveryAttempts().stream().noneMatch(attempt -> attempt.message().contains("secret")));
    }

    private static TestContext contextWithConfig(Set<RdProjectAlertEventType> events, BigDecimal budgetThreshold) {
        String taskId = "7482000000000000401";
        String projectId = "7482000000000000402";
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveBugFixTask(new RdBugFixTask(
                taskId, "BUG_FIX", "ticket-2", "bug", "P1", RdTaskStatus.EXECUTING,
                "message-2", "bug", "prompt", "", "", "", projectId, "demo", "Demo project",
                "https://github.com/example/demo", "example", "demo", "main",
                1_783_200_000_000L, 1_783_200_000_000L, false
        ));
        InMemoryConfigStore configStore = new InMemoryConfigStore();
        RdProjectAlertConfigService configService = new RdProjectAlertConfigService(configStore);
        configService.update(projectId, new RdProjectAlertConfigCommand(
                true, List.of(new RdAlertRecipient(RdAlertRecipientType.CHAT_ID, "oc-project")),
                events, budgetThreshold, 2));
        return new TestContext(taskId, taskStore, configService, properties());
    }

    private static FeishuImProperties properties() {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setAppId("cli-test");
        properties.setAppSecret("app-secret");
        properties.getAlert().setEnabled(true);
        properties.getAlert().setChatId("oc-fallback");
        return properties;
    }

    private static SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_200_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private static final class InMemoryConfigStore implements RdProjectAlertConfigStore {
        private RdProjectAlertConfig config;

        @Override
        public RdProjectAlertConfig save(RdProjectAlertConfig config) {
            this.config = config;
            return config;
        }

        @Override
        public Optional<RdProjectAlertConfig> findByProjectId(String projectId) {
            return config == null || !config.projectId().equals(projectId) ? Optional.empty() : Optional.of(config);
        }
    }

    private static final class InMemoryDeliveryStore implements RdAlertDeliveryStore {
        private final List<RdAlertDelivery> deliveries = new ArrayList<>();
        private int updateFailuresRemaining;

        private InMemoryDeliveryStore() {
            this(0);
        }

        private InMemoryDeliveryStore(int updateFailuresRemaining) {
            this.updateFailuresRemaining = updateFailuresRemaining;
        }

        @Override
        public synchronized RdAlertDelivery save(RdAlertDelivery delivery) {
            findByIdempotencyKey(delivery.idempotencyKey()).ifPresentOrElse(ignored -> { }, () -> deliveries.add(delivery));
            return findByIdempotencyKey(delivery.idempotencyKey()).orElseThrow();
        }

        @Override
        public synchronized RdAlertDelivery updateOutcome(RdAlertDelivery delivery) {
            if (updateFailuresRemaining > 0) {
                updateFailuresRemaining--;
                throw new IllegalStateException("db update token=secret");
            }
            deliveries.removeIf(item -> item.idempotencyKey().equals(delivery.idempotencyKey()));
            deliveries.add(delivery);
            return delivery;
        }

        @Override
        public Optional<RdAlertDelivery> findByIdempotencyKey(String idempotencyKey) {
            return deliveries.stream().filter(item -> item.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public List<RdAlertDelivery> listByTask(String taskId) {
            return deliveries.stream().filter(item -> item.taskId().equals(taskId)).toList();
        }
    }

    private record TestContext(
            String taskId,
            InMemoryRdTaskStore taskStore,
            RdProjectAlertConfigService configService,
            FeishuImProperties properties
    ) {
    }

    private static final class StubTransport implements FeishuImClient.HttpTransport {
        private final ArrayDeque<FeishuImClient.HttpExchange> responses = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();

        void successfulMessage(String messageId) {
            responses.add(new FeishuImClient.HttpExchange(200,
                    "{\"code\":0,\"tenant_access_token\":\"tenant-token\",\"expire\":7200}"));
            responses.add(new FeishuImClient.HttpExchange(200,
                    "{\"code\":0,\"data\":{\"message_id\":\"" + messageId + "\"}}"));
        }

        @Override
        public FeishuImClient.HttpExchange send(HttpRequest request, String body) {
            requests.add(request);
            bodies.add(body == null ? "" : body);
            return responses.removeFirst();
        }
    }
}
