package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.ticket.impl.MockTicketWriteBackAdapter;
import com.wish.rd.exec.repair.ticket.model.TicketUpdateCommand;
import com.wish.rd.exec.repair.ticket.TicketWriteBackPort;
import com.wish.rd.exec.repair.ticket.model.TicketWriteBackResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockTicketWriteBackAdapterTest {

    @Test
    void shouldSelectMockWriteBackAdapterByDefault() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(TicketWriteBackContextConfiguration.class);

        contextRunner.run(context -> {
            assertEquals(1, context.getBeanNamesForType(TicketWriteBackPort.class).length);
            assertTrue(context.getBean(TicketWriteBackPort.class) instanceof MockTicketWriteBackAdapter);
        });
    }

    @Test
    void mockAdapterShouldRecordSuccessfulWriteBackCommand() {
        MockTicketWriteBackAdapter adapter = new MockTicketWriteBackAdapter();
        TicketUpdateCommand command = TicketUpdateCommand.success(
                "FS-1001",
                "task-1001",
                "repair-1001",
                "修复已完成",
                "https://github.com/acme/order/pull/42",
                Map.of("stage", "completed")
        );

        TicketWriteBackResult result = adapter.writeBack(command);

        assertTrue(result.success());
        assertEquals("mock", result.metadata().get("provider"));
        assertEquals(1, adapter.recordedCommands().size());
        assertEquals(command, adapter.recordedCommands().getFirst());
        assertEquals("https://github.com/acme/order/pull/42", adapter.recordedCommands().getFirst().pullRequestUrl());
        assertEquals("", adapter.recordedCommands().getFirst().failureReason());
    }

    @Test
    void mockAdapterShouldRecordFailureWriteBackCommand() {
        MockTicketWriteBackAdapter adapter = new MockTicketWriteBackAdapter();
        TicketUpdateCommand command = TicketUpdateCommand.failure(
                "FS-1002",
                "task-1002",
                "repair-1002",
                "修复失败",
                "validation failed",
                Map.of("stage", "validation")
        );

        TicketWriteBackResult result = adapter.writeBack(command);

        assertTrue(result.success());
        assertTrue(adapter.recordedCommands().getFirst().needHumanAction());
        assertEquals("validation failed", adapter.recordedCommands().getFirst().failureReason());
        assertEquals("", adapter.recordedCommands().getFirst().pullRequestUrl());
    }

    @Test
    void mockAdapterShouldRejectNullCommand() {
        MockTicketWriteBackAdapter adapter = new MockTicketWriteBackAdapter();

        assertThrows(IllegalArgumentException.class, () -> adapter.writeBack(null));
    }

    @Test
    void mockWriteBackSourceShouldRemainProviderNeutral() throws IOException {
        String adapterSource = java.nio.file.Files.readString(moduleRoot().resolve(
                "src/main/java/com/wish/rd/bootstrap/ticket/impl/MockTicketWriteBackAdapter.java"));
        String commandSource = java.nio.file.Files.readString(moduleRoot().getParent().resolve(
                "exec/src/main/java/com/wish/rd/exec/repair/ticket/model/TicketUpdateCommand.java"));

        String combined = (adapterSource + "\n" + commandSource).toLowerCase();
        assertFalse(combined.contains("help" + "desk"));
        assertFalse(combined.contains("ticket" + "status"));
        assertFalse(combined.contains("custom" + "_field"));
        assertFalse(combined.contains("custom" + "field"));
    }

    private static java.nio.file.Path moduleRoot() {
        java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"));
        return workingDirectory.getFileName().toString().equals("bootstrap")
                ? workingDirectory
                : workingDirectory.resolve("bootstrap");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MockTicketWriteBackAdapter.class)
    static class TicketWriteBackContextConfiguration {
    }
}
