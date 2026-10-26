package com.poolapp;

import com.poolapp.model.SalesQuote;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SalesQuoteTest {
    static SalesQuote draft(String customer, String notes, String terms, List<SalesQuote.Line> lines) {
        return new SalesQuote(0, "", customer, "10/26/2026", notes, terms, lines, null, "", "");
    }

    static SalesQuote.Line line(String type, String description, String amount) {
        return new SalesQuote.Line("10/26/2026", type, description, new BigDecimal(amount));
    }

    @Test public void validatesDatesAmountsAndBounds() {
        for (String date : List.of("02/29/2025", "2/03/2026", "13/01/2026", "not a date"))
            assertThrows(IllegalArgumentException.class, () -> SalesQuote.validateDate(date));
        SalesQuote.validateDate("02/29/2024");
        for (String value : List.of("0", "1.001", "1000000000.00"))
            assertThrows(IllegalArgumentException.class, () -> line("Bill", "", value));
        line("Payment", "", "999999999.99");
        assertThrows(IllegalArgumentException.class, () -> draft("c1", "", "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft("c1", "x".repeat(16001), "", List.of(line("Bill", "", "-1"))));
        assertThrows(IllegalArgumentException.class, () -> draft("c1", "", "",
                java.util.Collections.nCopies(201, line("Bill", "", "-1"))));
    }

    @Test public void usesActualStatementSignsAndConfiguredCreditKeywords() {
        assertEquals(new BigDecimal("-25"), SalesQuote.signedAmount("Service", new BigDecimal("25"), Map.of()));
        assertEquals(new BigDecimal("25"), SalesQuote.signedAmount("Check payment", new BigDecimal("25"), Map.of()));
        Map<String, String> custom = Map.of("recordTypeCreditKeywords", "settled, rebate");
        assertEquals(new BigDecimal("25"), SalesQuote.signedAmount("Settled by client", new BigDecimal("25"), custom));
        assertEquals(new BigDecimal("-25"), SalesQuote.signedAmount("Payment", new BigDecimal("25"), custom));
        assertEquals("Bill: Motor", line("Bill", "Motor", "-1").statementType());
        assertEquals("Bill", line("Bill", "Bill", "-1").statementType());
        assertEquals("Bill", line("Bill", "", "-1").statementType());
    }
}
