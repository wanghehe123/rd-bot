package com.wish.rd.exec.repair.alert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetCurrencyConverterTest {

    @Test
    void shouldConvertProviderUsdToCnyWithFourDecimalPlaces() {
        BudgetCurrencyConverter converter = new BudgetCurrencyConverter(new BigDecimal("7.20"));

        assertEquals(new BigDecimal("0.8640"), converter.usdToCny(new BigDecimal("0.12")));
        assertEquals(new BigDecimal("0.0000"), converter.usdToCny(null));
    }
}
