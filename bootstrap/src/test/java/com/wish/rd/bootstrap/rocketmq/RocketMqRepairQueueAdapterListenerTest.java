package com.wish.rd.bootstrap.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RocketMqRepairQueueAdapterListenerTest {

    @Test
    void shouldUseRocketMqReconsumeTimesAsAttemptWhenBodyIsOriginal() throws Exception {
        RocketMqRepairQueueAdapter.RepairMessageListener listener =
                new RocketMqRepairQueueAdapter.RepairMessageListener(message -> true, new ObjectMapper());
        MessageExt message = new MessageExt();
        message.setBody("""
                {
                  "ticketId": "FS-9001",
                  "priority": "P1",
                  "traceId": "trace-9001",
                  "attempt": 1,
                  "source": "feishu",
                  "eventId": "evt-9001",
                  "eventType": "helpdesk.ticket.created_v1",
                  "createdAt": "2026-06-21T00:00:00Z"
                }
                """.getBytes(StandardCharsets.UTF_8));
        message.setReconsumeTimes(3);

        RepairTicketMessage restored = listener.fromMqMessage(message);

        assertEquals(4, restored.attempt());
        assertEquals("trace-9001", restored.traceId());
    }
}
