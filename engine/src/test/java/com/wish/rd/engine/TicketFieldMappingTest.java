package com.wish.rd.engine;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.engine.ticket.TicketFieldMapping;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link TicketFieldMapping} 字段抽取与信息充足判定。
 */
class TicketFieldMappingTest {

    @Test
    void extractsStandardFieldsByConfiguredNames() {
        Map<String, String> customFieldNames = new LinkedHashMap<>();
        customFieldNames.put(TicketFieldMapping.KEY_PROBLEM_SYSTEM, "故障系统");
        customFieldNames.put(TicketFieldMapping.KEY_LOGS, "日志");
        TicketFieldMapping mapping = new TicketFieldMapping(customFieldNames);

        Map<String, String> custom = new LinkedHashMap<>();
        custom.put("故障系统", "payment-service");
        custom.put("日志", "ERROR orders.amount is null");
        TicketSnapshot ticket = snapshot(custom);

        Map<String, String> extracted = mapping.extract(ticket);
        assertAll(
                () -> assertEquals("payment-service", extracted.get(TicketFieldMapping.KEY_PROBLEM_SYSTEM)),
                () -> assertEquals("ERROR orders.amount is null", extracted.get(TicketFieldMapping.KEY_LOGS)),
                () -> assertEquals("P2", extracted.get(TicketFieldMapping.KEY_PRIORITY)),
                () -> assertEquals("", extracted.get(TicketFieldMapping.KEY_REPOSITORY))
        );
        assertEquals("ERROR orders.amount is null", mapping.extractLogs(ticket));
    }

    @Test
    void hasEnoughInfoWhenContextAndEvidencePresent() {
        TicketFieldMapping mapping = TicketFieldMapping.defaults();
        TicketSnapshot ticket = new TicketSnapshot(
                "T-1", "title", "金额为空时下单失败",
                java.util.List.of(), Instant.EPOCH, "P1", "processing", "", "feishu", "",
                Map.of(TicketFieldMapping.KEY_LOGS, "ERROR orders.amount is null"),
                Instant.EPOCH, Instant.EPOCH
        );
        assertTrue(mapping.hasEnoughInfo(ticket));
    }

    @Test
    void notEnoughInfoWhenOnlyDescriptionWithoutEvidence() {
        TicketFieldMapping mapping = TicketFieldMapping.defaults();
        TicketSnapshot ticket = new TicketSnapshot(
                "T-1", "title", "金额为空时下单失败",
                java.util.List.of(), Instant.EPOCH, "P1", "processing", "", "feishu", "",
                Map.of(),
                Instant.EPOCH, Instant.EPOCH
        );
        assertFalse(mapping.hasEnoughInfo(ticket));
    }

    @Test
    void enoughInfoWhenRepositoryProvidedEvenWithoutLogs() {
        TicketFieldMapping mapping = TicketFieldMapping.defaults();
        TicketSnapshot ticket = new TicketSnapshot(
                "T-1", "title", "下单失败",
                java.util.List.of(), Instant.EPOCH, "P0", "processing", "", "feishu", "",
                Map.of(TicketFieldMapping.KEY_REPOSITORY, "github.com/org/payment"),
                Instant.EPOCH, Instant.EPOCH
        );
        assertTrue(mapping.hasEnoughInfo(ticket));
    }

    @Test
    void nullTicketReturnsEmptyAndFalse() {
        TicketFieldMapping mapping = TicketFieldMapping.defaults();
        assertTrue(mapping.extract(null).isEmpty());
        assertEquals("", mapping.extractLogs(null));
        assertFalse(mapping.hasEnoughInfo(null));
    }

    private TicketSnapshot snapshot(Map<String, String> custom) {
        return new TicketSnapshot(
                "T-1", "title", "desc",
                java.util.List.of(), Instant.EPOCH, "P2", "processing", "", "feishu", "",
                custom, Instant.EPOCH, Instant.EPOCH
        );
    }
}
