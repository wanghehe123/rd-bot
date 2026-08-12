package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.util.Map;
import java.util.Objects;

/** Host-side browser probe SPI used by semantic DOM assertions. */
@FunctionalInterface
public interface HostBrowserProbe {

    /** Inspects one Host-selected selector/route target in a Host-owned browser session. */
    BrowserAssertionSnapshot inspect(String target, AssertionEvaluationContext context) throws Exception;

    /**
     * Inspects a typed Host assertion request.
     *
     * <p>The default preserves the original two-argument SPI for existing in-memory probes.
     * Concrete probes override it to keep route navigation, ARIA attribute reads, and timeout
     * limits under Host control.
     */
    default BrowserAssertionSnapshot inspect(BrowserProbeRequest request, AssertionEvaluationContext context)
            throws Exception {
        Objects.requireNonNull(request, "request must not be null");
        return inspect(request.target(), context);
    }

    /** Immutable Host-selected browser operation; no executable browser script is carried here. */
    record BrowserProbeRequest(
            AssertionType assertionType,
            String target,
            String ariaAttribute,
            long timeoutMillis
    ) {
        public BrowserProbeRequest {
            if (assertionType == null) {
                throw new IllegalArgumentException("assertionType must not be null");
            }
            target = target == null ? "" : target.strip();
            ariaAttribute = ariaAttribute == null ? "" : ariaAttribute.strip();
            timeoutMillis = Math.max(0L, timeoutMillis);
        }
    }

    /** Immutable browser state used by DOM, ARIA, visibility, and route assertions. */
    record BrowserAssertionSnapshot(
            boolean exists,
            boolean visible,
            String route,
            Map<String, String> ariaAttributes
    ) {
        public BrowserAssertionSnapshot {
            route = route == null ? "" : route.strip();
            ariaAttributes = ariaAttributes == null || ariaAttributes.isEmpty() ? Map.of() : Map.copyOf(ariaAttributes);
        }

        public String aria(String name) {
            String key = name == null ? "" : name.strip();
            return ariaAttributes.getOrDefault(key, "");
        }
    }

    /** Fail-closed probe used until a real Host browser implementation is registered. */
    static HostBrowserProbe unavailable() {
        return (target, context) -> {
            throw new IllegalStateException("Host browser probe is unavailable");
        };
    }
}
