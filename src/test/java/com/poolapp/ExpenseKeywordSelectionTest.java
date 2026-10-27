package com.poolapp;

import com.poolapp.ui.ExpenseKeywordSelection;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExpenseKeywordSelectionTest {
    private final Preferences node = Preferences.userRoot().node("poolapp-test/expense-selection-" + UUID.randomUUID());
    private final ExpenseKeywordSelection store = new ExpenseKeywordSelection(node);

    @After
    public void cleanup() throws Exception { node.removeNode(); }

    @Test
    public void nothingIsCheckedUntilTheUserChoosesKeywords() {
        assertTrue(store.load("emp:a@Co", List.of("Home Depot", "Pool Supplies")).isEmpty());
    }

    @Test
    public void remembersCheckedKeywordsPerUserAndIgnoresCaseSpacingAndDeletedKeywords() {
        store.save("emp:a@Co", List.of("Home Depot", "Gone"));
        assertEquals(Set.of("home  depot"), store.load("emp:a@Co", List.of("home  depot", "Pool Supplies")));
        assertTrue(store.load("emp:b@Co", List.of("Home Depot")).isEmpty());
        store.save("emp:a@Co", List.of());
        assertTrue(store.load("emp:a@Co", List.of("Home Depot")).isEmpty());
    }

    @Test
    public void oneHundredMaximumLengthKeywordsFitInPreferences() {
        List<String> many = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> String.format("%02d", i) + "x".repeat(78)).toList();
        store.save("co:Co", many);
        assertEquals(100, store.load("co:Co", many).size());
    }
}
