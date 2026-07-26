package com.wish.rd.bootstrap.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Opt-in host-controlled H1 resource cache configuration. */
@Component
@ConfigurationProperties(prefix = "rd.executor.pi.resources")
public class PiAgentResourceProperties {

    private boolean enabled;
    private String approvedCacheRoot = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApprovedCacheRoot() {
        return approvedCacheRoot;
    }

    public void setApprovedCacheRoot(String approvedCacheRoot) {
        this.approvedCacheRoot = approvedCacheRoot == null ? "" : approvedCacheRoot.strip();
    }
}
