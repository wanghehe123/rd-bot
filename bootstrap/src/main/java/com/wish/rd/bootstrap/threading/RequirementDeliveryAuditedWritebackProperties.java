package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.audit.AuditedWritebackGateMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound gate-mode for audited completion. Defaults stay {@code ENFORCE};
 * cloud first-run overlays may set {@code SHADOW}.
 */
@ConfigurationProperties(prefix = "rd.requirement-delivery.audited-writeback")
public class RequirementDeliveryAuditedWritebackProperties {

    private AuditedWritebackGateMode gateMode = AuditedWritebackGateMode.ENFORCE;

    public AuditedWritebackGateMode getGateMode() {
        return gateMode;
    }

    /**
     * Parses {@code ENFORCE} or {@code SHADOW}. Unknown tokens fail closed.
     *
     * @param gateMode raw configuration token
     */
    public void setGateMode(String gateMode) {
        this.gateMode = AuditedWritebackGateMode.parse(gateMode);
    }
}
