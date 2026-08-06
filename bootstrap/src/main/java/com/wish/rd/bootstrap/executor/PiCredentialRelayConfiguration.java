package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.impl.InMemoryPiCredentialLeaseIssuer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Host-side Pi credential lease issuer for relay mode.
 * Raw provider secrets stay in the issuer; containers receive opaque leases only.
 */
@Configuration(proxyBeanMethods = false)
public class PiCredentialRelayConfiguration {

    @Bean
    @ConditionalOnMissingBean(PiCredentialLeaseIssuer.class)
    public PiCredentialLeaseIssuer piCredentialLeaseIssuer() {
        return new InMemoryPiCredentialLeaseIssuer();
    }
}
