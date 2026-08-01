package com.wish.rd.bootstrap.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiAgentExecutorPropertiesTest {

    @Test
    void shouldDefaultPiExecutionsToABoundedWallClockTimeout() {
        PiAgentExecutorProperties properties = new PiAgentExecutorProperties();

        assertEquals(3_600_000L, properties.toExecutorConfiguration().executionTimeoutMillis());
        assertEquals(900_000L, properties.toExecutorConfiguration().bashCommandTimeoutMillis());

        properties.setBashCommandTimeoutMillis(120_000L);
        assertEquals(120_000L, properties.toExecutorConfiguration().bashCommandTimeoutMillis());
    }

    @Test
    void shouldPassTheConfiguredRawEventLimitToThePiExecutor() {
        PiAgentExecutorProperties properties = new PiAgentExecutorProperties();
        properties.setRawEventMaxBytes(4096L);

        assertEquals(4096L, properties.toExecutorConfiguration().rawEventMaxBytes());

        properties.setRawEventMaxBytes(0L);
        assertEquals(1L, properties.toExecutorConfiguration().rawEventMaxBytes());
    }

    @Test
    void shouldDefaultRequestProtocolVersionToV1AndAcceptSetter() {
        PiAgentExecutorProperties properties = new PiAgentExecutorProperties();

        assertEquals("v1", properties.getRequestProtocolVersion());
        assertEquals("v1", properties.toExecutorConfiguration().requestProtocolVersion());

        properties.setRequestProtocolVersion("V2");
        assertEquals("v2", properties.getRequestProtocolVersion());
        assertEquals("v2", properties.toExecutorConfiguration().requestProtocolVersion());
    }
}
