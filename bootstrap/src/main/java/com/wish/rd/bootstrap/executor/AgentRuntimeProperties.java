package com.wish.rd.bootstrap.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Feature switch for the requirement-delivery runtime router. */
@Component
@ConfigurationProperties(prefix = "rd.executor.agent-runtime")
public class AgentRuntimeProperties {

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
