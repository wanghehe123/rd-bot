package com.wish.rd.engine;

import com.wish.rd.engine.audit.model.RepairAuditEvent;
import com.wish.rd.engine.audit.model.RepairAuditEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RepairAuditEventTest {

    @Test
    void shouldRedactSecretMetadataValues() {
        RepairAuditEvent event = RepairAuditEvent.now(
                "repair-1001",
                "task-1001",
                "FS-1001",
                RepairAuditEventType.EXECUTION_FINISHED,
                "Docker",
                "finished",
                Map.of(
                        "apiKey", "sk-live-secret",
                        "message", "authorization=Bearer abc123 token=raw-token"
                )
        );

        assertEquals("<redacted>", event.metadata().get("apiKey"));
        assertFalse(event.metadata().get("message").contains("abc123"));
        assertFalse(event.metadata().get("message").contains("raw-token"));
    }
}
