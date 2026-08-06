package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.controller.internal.PiCredentialRelayController;
import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.impl.InMemoryPiCredentialLeaseIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiCredentialLeaseIssuerWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PiCredentialRelayConfiguration.class);

    @Test
    void shouldExposeInMemoryLeaseIssuerByDefault() {
        contextRunner.run(context -> {
            PiCredentialLeaseIssuer issuer = context.getBean(PiCredentialLeaseIssuer.class);
            assertNotNull(issuer);
            assertInstanceOf(InMemoryPiCredentialLeaseIssuer.class, issuer);

            var lease = issuer.issue("task", "stage", "provider", "secret", Duration.ofMinutes(5), 1);
            assertTrue(lease.token().startsWith("pcl_"));
            assertTrue(issuer.redeem(lease.token()).isPresent());
        });
    }

    @Test
    void shouldKeepTheHttpRelayDisabledUntilTheFlagIsEnabled() {
        ApplicationContextRunner relayContext = contextRunner
                .withUserConfiguration(PiCredentialRelayService.class, PiCredentialRelayController.class);

        relayContext.run(context -> assertTrue(context.getBeansOfType(PiCredentialRelayController.class).isEmpty()));
        relayContext.withPropertyValues("rd.executor.pi.credential-relay-enabled=true")
                .run(context -> assertNotNull(context.getBean(PiCredentialRelayController.class)));
    }
}
