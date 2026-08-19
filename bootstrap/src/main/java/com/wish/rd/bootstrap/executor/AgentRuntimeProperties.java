package com.wish.rd.bootstrap.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feature switch for the requirement-delivery runtime router.
 *
 * <p>Defaults to enabled in code, not only in {@code application.yaml}: test contexts
 * load their own {@code application.yaml}, which shadows the main one entirely, so a
 * default that lives only in YAML would leave the routed path unexercised while
 * production used it.
 */
@Component
@ConfigurationProperties(prefix = "rd.executor.agent-runtime")
public class AgentRuntimeProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
