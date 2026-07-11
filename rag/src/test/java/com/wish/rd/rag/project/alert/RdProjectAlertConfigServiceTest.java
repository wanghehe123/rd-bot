package com.wish.rd.rag.project.alert;

import com.wish.rd.rag.project.alert.model.RdAlertRecipient;
import com.wish.rd.rag.project.alert.model.RdAlertRecipientType;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfigCommand;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RdProjectAlertConfigServiceTest {

    @Test
    void shouldNormalizeAndPersistProjectAlertConfig() {
        InMemoryStore store = new InMemoryStore();
        RdProjectAlertConfigService service = new RdProjectAlertConfigService(store);

        RdProjectAlertConfig saved = service.update("7482000000000000001", new RdProjectAlertConfigCommand(
                true,
                List.of(
                        new RdAlertRecipient(RdAlertRecipientType.CHAT_ID, " oc_chat_1 "),
                        new RdAlertRecipient(RdAlertRecipientType.CHAT_ID, "oc_chat_1"),
                        new RdAlertRecipient(RdAlertRecipientType.OPEN_ID, "ou_user_1")
                ),
                Set.of(RdProjectAlertEventType.TASK_COMPLETED, RdProjectAlertEventType.TASK_BLOCKED),
                new BigDecimal("36.00"),
                2
        ));

        assertEquals("7482000000000000001", saved.projectId());
        assertEquals(2, saved.recipients().size());
        assertEquals(new BigDecimal("36.00"), saved.budgetThresholdCny());
        assertEquals(2, saved.failureThreshold());
        assertEquals(saved, service.get(saved.projectId()));
    }

    @Test
    void shouldReturnDisabledDefaultWhenProjectHasNoConfig() {
        RdProjectAlertConfigService service = new RdProjectAlertConfigService(new InMemoryStore());

        RdProjectAlertConfig config = service.get("7482000000000000002");

        assertFalse(config.enabled());
        assertEquals(List.of(), config.recipients());
        assertEquals(Set.of(), config.eventTypes());
        assertEquals(1, config.failureThreshold());
    }

    @Test
    void shouldAllowEnabledProjectAlertWithNoRecipients() {
        RdProjectAlertConfigService service = new RdProjectAlertConfigService(new InMemoryStore());

        RdProjectAlertConfig saved = service.update("7482000000000000004", new RdProjectAlertConfigCommand(
                true,
                List.of(),
                Set.of(RdProjectAlertEventType.BUDGET_EXCEEDED),
                new BigDecimal("36.00"),
                2
        ));

        assertEquals(List.of(), saved.recipients());
        assertEquals(new BigDecimal("36.00"), saved.budgetThresholdCny());
    }

    @Test
    void shouldRejectInvalidFailureThreshold() {
        RdProjectAlertConfigService service = new RdProjectAlertConfigService(new InMemoryStore());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.update(
                "7482000000000000003",
                new RdProjectAlertConfigCommand(
                        true,
                        List.of(new RdAlertRecipient(RdAlertRecipientType.CHAT_ID, "oc_chat")),
                        Set.of(RdProjectAlertEventType.TASK_FAILED),
                        BigDecimal.ZERO,
                        0
                )
        ));

        assertEquals("failureThreshold must be positive", exception.getMessage());
    }

    private static final class InMemoryStore implements RdProjectAlertConfigStore {

        private final List<RdProjectAlertConfig> configs = new ArrayList<>();

        @Override
        public RdProjectAlertConfig save(RdProjectAlertConfig config) {
            configs.removeIf(existing -> existing.projectId().equals(config.projectId()));
            configs.add(config);
            return config;
        }

        @Override
        public Optional<RdProjectAlertConfig> findByProjectId(String projectId) {
            return configs.stream().filter(config -> config.projectId().equals(projectId)).findFirst();
        }
    }
}
