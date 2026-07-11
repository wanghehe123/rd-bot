package com.wish.rd.bootstrap.financial;

import com.wish.rd.exec.repair.alert.BudgetCurrencyConverter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Runtime configuration for deterministic budget currency conversion. */
@Component
@ConfigurationProperties(prefix = "rd.financial")
public class FinancialProperties {

    private BigDecimal cnyPerUsd = BudgetCurrencyConverter.DEFAULT_CNY_PER_USD;

    public BigDecimal getCnyPerUsd() {
        return cnyPerUsd;
    }

    public void setCnyPerUsd(BigDecimal cnyPerUsd) {
        if (cnyPerUsd == null || cnyPerUsd.signum() <= 0) {
            throw new IllegalArgumentException("cnyPerUsd must be positive");
        }
        this.cnyPerUsd = cnyPerUsd;
    }

    public BudgetCurrencyConverter toBudgetCurrencyConverter() {
        return new BudgetCurrencyConverter(cnyPerUsd);
    }
}
