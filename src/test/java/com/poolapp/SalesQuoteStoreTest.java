package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.SalesQuoteStore;
import com.poolapp.model.Customer;
import com.poolapp.model.SalesQuote;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static com.poolapp.SalesQuoteTest.*;

public class SalesQuoteStoreTest {
    private Path directory;
    private Path path;
    private DatabaseManager database;
    private SalesQuoteStore store;

    @Before public void setUp() throws Exception {
        directory = Files.createDirectories(Path.of("quote-test-" + UUID.randomUUID()));
        path = directory.resolve("quotes.db");
        database = new DatabaseManager(path);
        database.addCustomer(new Customer("c1", "Mayra", "Paret", "9100 SW 30th Terrace",
                "", "", "Monday", BigDecimal.ZERO, ""));
        database.addCustomer(new Customer("c2", "Other", "Customer", "", "", "", "Tuesday", BigDecimal.ZERO, ""));
        store = new SalesQuoteStore(database);
    }

    @After public void cleanUp() throws Exception {
        if (directory != null) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test public void persistsSeparateQuotesEditsAndNeverReusesDeletedNumber() {
        SalesQuote first = store.save(draft("c1", "Original", SalesQuote.DEFAULT_TERMS, List.of(line("Bill", "Motor", "-120.25"))));
        SalesQuote second = store.save(draft("c1", "", "", List.of(line("Payment", "", "25"))));
        assertEquals("SQ-000001", first.quoteNumber());
        assertEquals("SQ-000002", second.quoteNumber());
        assertEquals(2, new SalesQuoteStore(new DatabaseManager(path)).list("c1").size());
        assertTrue(store.list("c2").isEmpty());
        assertTrue(database.getStatementRecords("c1").isEmpty());
        SalesQuote edited = store.save(new SalesQuote(first.quoteId(), first.quoteNumber(), first.customerId(),
                "10/27/2026", "Edited", first.terms(), first.lines(), null, first.createdAt(), first.updatedAt()));
        assertEquals(first.quoteId(), edited.quoteId()); assertEquals("Edited", edited.notes());
        assertEquals(first.createdAt(), edited.createdAt());
        assertThrows(IllegalStateException.class, () -> store.delete("c2", first.quoteId()));
        store.delete("c1", second.quoteId());
        store.delete("c1", first.quoteId());
        SalesQuote third = new SalesQuoteStore(new DatabaseManager(path)).save(
                draft("c1", "", "", List.of(line("Bill", "", "-1"))));
        assertEquals("SQ-000003", third.quoteNumber());
    }

    @Test public void convertsExactlyOnceAndLocksBothStaleAndConvertedDrafts() {
        SalesQuote saved = store.save(draft("c1", "", "", List.of(
                line("Bill", "Motor", "-120.25"), line("Payment", "", "20.00"))));
        SalesQuote converted = store.convert("c1", saved.quoteId());
        assertTrue(converted.converted());
        assertEquals(converted.convertedAt(), store.convert("c1", saved.quoteId()).convertedAt());
        var records = database.getStatementRecords("c1");
        assertEquals(2, records.size());
        assertEquals("Bill: Motor", records.get(0).getType());
        assertEquals(0, new BigDecimal("-120.25").compareTo(records.get(0).getAmount()));
        assertEquals(0, new BigDecimal("20").compareTo(records.get(1).getAmount()));
        assertThrows(IllegalStateException.class, () -> store.save(saved));
        assertThrows(IllegalStateException.class, () -> store.save(converted));
        assertThrows(IllegalStateException.class, () -> store.delete("c1", saved.quoteId()));
        assertThrows(IllegalStateException.class, () -> store.convert("c2", saved.quoteId()));
    }

    @Test public void conversionRollsBackEveryLineAndTimestampWhenStatementInsertFails() throws Exception {
        SalesQuote saved = store.save(draft("c1", "", "", List.of(line("Bill", "First", "-1"), line("Bill", "Fail", "-2"))));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER reject_quote_line BEFORE INSERT ON statement_records "
                    + "WHEN NEW.type='Bill: Fail' BEGIN SELECT RAISE(ABORT,'test failure'); END");
        }
        assertThrows(IllegalStateException.class, () -> store.convert("c1", saved.quoteId()));
        assertFalse(store.list("c1").get(0).converted());
        assertTrue(database.getStatementRecords("c1").isEmpty());
    }

    @Test public void concurrentConversionAndCreationKeepExactlyOnceAndUniqueNumbers() throws Exception {
        SalesQuote saved = store.save(draft("c1", "", "", List.of(line("Bill", "First", "-1"))));
        var executor = Executors.newFixedThreadPool(4);
        try {
            var conversions = executor.invokeAll(java.util.Collections.nCopies(4,
                    (java.util.concurrent.Callable<SalesQuote>) () -> new SalesQuoteStore(database).convert("c1", saved.quoteId())));
            for (var result : conversions) assertTrue(result.get().converted());
            assertEquals(1, database.getStatementRecords("c1").size());
            var creations = executor.invokeAll(java.util.Collections.nCopies(4,
                    (java.util.concurrent.Callable<SalesQuote>) () -> new SalesQuoteStore(database).save(
                            draft("c1", "", "", List.of(line("Bill", "", "-1"))))));
            java.util.Set<String> numbers = new java.util.HashSet<>();
            for (var result : creations) assertTrue(numbers.add(result.get().quoteNumber()));
        } finally { executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
