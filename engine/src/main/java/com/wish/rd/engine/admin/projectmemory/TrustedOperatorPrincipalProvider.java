package com.wish.rd.engine.admin.projectmemory;

import java.util.Optional;

/** Host authentication boundary for governance mutations. */
public interface TrustedOperatorPrincipalProvider {
    Optional<TrustedOperatorPrincipal> current();
}
