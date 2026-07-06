package com.wish.rd.exec.repair.ticket;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.exec.repair.ticket.model.TicketUpdateCommand;
import com.wish.rd.exec.repair.ticket.model.TicketWriteBackResult;

class TicketWriteBackPortContractTest {

    @Test
    void successCommandShouldCarryPullRequestUrlAndBlankFailureReason() {
        TicketUpdateCommand command = TicketUpdateCommand.success(
                "FS-1001",
                "task-1001",
                "repair-1001",
                "修复已完成",
                "https://github.com/acme/order/pull/42",
                Map.of("provider", "mock")
        );

        assertEquals("FS-1001", command.ticketId());
        assertEquals("task-1001", command.taskId());
        assertEquals("repair-1001", command.repairRecordId());
        assertEquals("修复已完成", command.statusSummary());
        assertEquals("https://github.com/acme/order/pull/42", command.pullRequestUrl());
        assertEquals("", command.failureReason());
        assertFalse(command.needHumanAction());
        assertEquals("mock", command.metadata().get("provider"));
    }

    @Test
    void failureCommandShouldCarryFailureReasonAndRequireHumanAction() {
        TicketUpdateCommand command = TicketUpdateCommand.failure(
                "FS-1002",
                "task-1002",
                "repair-1002",
                "修复失败",
                "result.json validation failed",
                Map.of("stage", "validation")
        );

        assertEquals("", command.pullRequestUrl());
        assertEquals("result.json validation failed", command.failureReason());
        assertTrue(command.needHumanAction());
        assertEquals("validation", command.metadata().get("stage"));
    }

    @Test
    void commandShouldNormalizeOptionalNulls() {
        TicketUpdateCommand command = new TicketUpdateCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null
        );

        assertEquals("", command.ticketId());
        assertEquals("", command.taskId());
        assertEquals("", command.repairRecordId());
        assertEquals("", command.statusSummary());
        assertEquals("", command.pullRequestUrl());
        assertEquals("", command.failureReason());
        assertTrue(command.metadata().isEmpty());
    }

    @Test
    void resultShouldNormalizeNullsAndKeepProviderMetadata() {
        TicketWriteBackResult result = new TicketWriteBackResult(
                true,
                null,
                null,
                Map.of("provider", "mock")
        );

        assertTrue(result.success());
        assertEquals("", result.providerCode());
        assertEquals("", result.message());
        assertEquals("mock", result.metadata().get("provider"));
    }
}
