package com.poolapp;

import com.poolapp.db.ExpenseScanner;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ExpenseScannerTest {
    private final ExpenseScanner scanner = new ExpenseScanner();

    @Test public void normalizesKeywordsInOrderAndKeepsFirstSpelling() {
        assertEquals(List.of("Fuel surcharge", "C++"), scanner.normalizeKeywords(
                List.of("  Fuel   surcharge ", "fuel SURCHARGE", "", "  ", "C++")));
    }

    @Test public void rejectsKeywordsOverLengthAndMoreThanOneHundred() {
        try {
            scanner.normalizeKeywords(List.of("x".repeat(81)));
            fail("Expected a clear maximum-length rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("80 characters"));
        }
        List<String> tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "key" + i).toList();
        try {
            scanner.normalizeKeywords(tooMany);
            fail("Expected the keyword count limit");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("100"));
        }
    }

    @Test public void matchingUsesWholePhrasesLiteralCharactersAndFirstKeywordOrderOncePerLine() {
        List<ExpenseScanner.Match> matches = scanner.scan(List.of(
                        new ExpenseScanner.SourceLine(1, "ACME card charge $12.30 plus C++ $8.00"),
                        new ExpenseScanner.SourceLine(1, "XACME card $4.00"),
                        new ExpenseScanner.SourceLine(2, "ACME   CARD $5.00"),
                        new ExpenseScanner.SourceLine(2, "C++ purchase ($6.25)")),
                List.of("acme card", "ACME", "C++"));
        assertEquals(3, matches.size());
        assertEquals("acme card", matches.get(0).keyword());
        assertEquals(Long.valueOf(1230), matches.get(0).amountCents());
        assertEquals("acme card", matches.get(1).keyword());
        assertEquals("C++", matches.get(2).keyword());
        assertEquals(Long.valueOf(625), matches.get(2).amountCents());
    }

    @Test public void firstMoneyMatchSupportsGroupingSignAndParentheses() {
        assertEquals(Long.valueOf(234567), ExpenseScanner.firstAmountCents("Shop $2,345.67 then $40.00"));
        assertEquals(Long.valueOf(1234), ExpenseScanner.firstAmountCents("Shop (12.34)"));
        assertEquals(Long.valueOf(605), ExpenseScanner.firstAmountCents("Shop -6.05"));
        assertNull(ExpenseScanner.firstAmountCents("Shop 12.3 and 7.999"));
    }

    @Test public void totalsExcludeNoAmountAndCheckboxPredicateUpdatesTotalsAndCounts() {
        List<ExpenseScanner.Match> matches = scanner.scan(List.of(
                        new ExpenseScanner.SourceLine(1, "Grocery market $50.00"),
                        new ExpenseScanner.SourceLine(1, "Grocery market statement only"),
                        new ExpenseScanner.SourceLine(2, "Grocery market ($5.25)")),
                List.of("Grocery market"));
        assertEquals(3, matches.size());
        assertNull(matches.get(1).amountCents());
        ExpenseScanner.Summary summary = scanner.summarize(matches,
                match -> !Long.valueOf(5000).equals(match.amountCents()), List.of("Grocery market", "Travel"));
        assertEquals(2, summary.includedLineCount());
        assertEquals(525, summary.grandTotalCents());
        assertEquals(2, summary.byKeyword().get("Grocery market").count());
        assertEquals(525, summary.byKeyword().get("Grocery market").totalCents());
        assertEquals(0, summary.byKeyword().get("Travel").count());
        assertEquals(0, summary.byKeyword().get("Travel").totalCents());
    }
}
