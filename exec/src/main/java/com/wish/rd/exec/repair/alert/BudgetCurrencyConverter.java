package com.wish.rd.exec.repair.alert;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Converts provider-reported USD costs into the CNY budget domain. */
public final class BudgetCurrencyConverter {

    public static final BigDecimal DEFAULT_CNY_PER_USD = new BigDecimal("7.20");

    private final BigDecimal cnyPerUsd;

    public BudgetCurrencyConverter(BigDecimal cnyPerUsd) {
        if (cnyPerUsd == null || cnyPerUsd.signum() <= 0) {
            throw new IllegalArgumentException("cnyPerUsd must be positive");
        }
        this.cnyPerUsd = cnyPerUsd;
    }

    public BigDecimal usdToCny(BigDecimal usd) {
        BigDecimal normalizedUsd = usd == null ? BigDecimal.ZERO : usd;
        return normalizedUsd.multiply(cnyPerUsd).setScale(4, RoundingMode.HALF_UP);
    }
}
