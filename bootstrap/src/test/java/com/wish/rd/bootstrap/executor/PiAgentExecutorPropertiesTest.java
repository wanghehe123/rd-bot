package com.wish.rd.bootstrap.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiAgentExecutorPropertiesTest {

    @Test
    void shouldPassTheConfiguredRawEventLimitToThePiExecutor() {
        PiAgentExecutorProperties properties = new PiAgentExecutorProperties();
        properties.setRawEventMaxBytes(4096L);

        assertEquals(4096L, properties.toExecutorConfiguration().rawEventMaxBytes());

        properties.setRawEventMaxBytes(0L);
        assertEquals(1L, properties.toExecutorConfiguration().rawEventMaxBytes());
    }
}
