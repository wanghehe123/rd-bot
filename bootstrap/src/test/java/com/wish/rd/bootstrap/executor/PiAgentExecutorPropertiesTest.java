package com.wish.rd.bootstrap.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldDefaultCredentialRelayDisabledAndPassThroughSetter() {
        PiAgentExecutorProperties properties = new PiAgentExecutorProperties();

        assertFalse(properties.isCredentialRelayEnabled());
        assertFalse(properties.toExecutorConfiguration().credentialRelayEnabled());

        properties.setCredentialRelayEnabled(true);
        assertTrue(properties.isCredentialRelayEnabled());
        assertTrue(properties.toExecutorConfiguration().credentialRelayEnabled());
    }

    @Test
    void shouldExposeTheConfiguredCredentialRelayUrlWithoutChangingTheDisabledDefault() {
        PiAgentExecutorProperties properties = new PiAgentExecutorProperties();

        assertEquals(PiAgentExecutorProperties.DEFAULT_CREDENTIAL_RELAY_URL, properties.getCredentialRelayUrl());
        assertEquals(
                PiAgentExecutorProperties.DEFAULT_CREDENTIAL_RELAY_URL,
                properties.toExecutorConfiguration().credentialRelayUrl()
        );

        properties.setCredentialRelayUrl("http://relay.internal/redeem");
        assertEquals("http://relay.internal/redeem", properties.getCredentialRelayUrl());
        assertEquals("http://relay.internal/redeem", properties.toExecutorConfiguration().credentialRelayUrl());
        assertFalse(properties.isCredentialRelayEnabled());
    }
}
