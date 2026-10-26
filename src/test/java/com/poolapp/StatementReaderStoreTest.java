package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.StatementReaderStore;
import com.poolapp.model.Customer;
import com.poolapp.service.StatementPdfReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class StatementReaderStoreTest {
    private Path directory;
    private Path databasePath;
    private DatabaseManager database;
    private StatementReaderStore store;
    private Customer jane;

    @Before public void setUp() throws Exception {
        directory = Files.createDirectories(Path.of("statement-reader-test-" + UUID.randomUUID()));
        databasePath = directory.resolve("reader.db");
        database = new DatabaseManager(databasePath);
        jane = new Customer("c1", "Jane", "Paret-Swihl", "", "", "", "Monday", BigDecimal.ZERO, "");
        database.addCustomer(jane);
        database.addCustomer(new Customer("c2", "Other", "Customer", "", "", "", "Tuesday", BigDecimal.ZERO, ""));
        store = new StatementReaderStore(database, Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC));
    }

    @After public void cleanUp() throws Exception {
        if (directory != null) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test public void previewMatchesReversedAccentAndSeparatorInsensitiveNameWithoutWritingPaymentsOrAudit() throws Exception {
        List<StatementReaderStore.PreviewRow> preview = store.preview(hash('a'),
                List.of(source("1:4", 1, 4, "9/8 Zelle From ParetSwihl Jane", "9/8", "100.00", "deposit", "")),
                List.of(jane));
        assertEquals(1, preview.size());
        assertEquals("ready", preview.get(0).status());
        assertEquals("c1", preview.get(0).customerId());
        Customer accented = new Customer("accent", "Jos\u00e9", "Garc\u00eda", "", "", "", "Monday", BigDecimal.ZERO, "");
        StatementReaderStore.PreviewRow accentMatch = store.preview(hash('9'),
                List.of(source("1:5", 1, 5, "9/8 Zelle From Garcia Jose", "9/8", "10.00", "deposit", "")),
                List.of(accented)).get(0);
        assertEquals(accentMatch.toString(), "ready", accentMatch.status());
        assertEquals("accent", accentMatch.customerId());
        assertEquals("10/26/2026", StatementReaderStore.parseDate("10/26", 2026));
        assertEquals("", StatementReaderStore.parseDate("2/30/2026", 2026));
        assertEquals(new BigDecimal("100.00"), StatementReaderStore.parsePositiveAmount("100.00"));
        assertTrue(database.getStatementRecords("c1").isEmpty());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='reader_imports'")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    @Test public void zelleFilterPreservesDatabaseMatchesInBothNameOrders() {
        List<String> names = List.of("Zelle From Jane Paret-Swihl", "Zelle From Paret-Swihl Jane",
                "Zel From Jane ParetSwihl", "zEL from PARETSWIHL, JANE");
        List<StatementPdfReader.SourceRow> sources = new java.util.ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            sources.add(source("1:" + (i + 1), 1, i + 1, names.get(i), "9/8", (110 + i) + ".00", "deposit", ""));
        }
        List<StatementPdfReader.SourceRow> filtered = StatementPdfReader.zelleDepositRows(sources);
        assertEquals(4, filtered.size());
        List<StatementReaderStore.PreviewRow> preview = store.preview(hash('d'), filtered, List.of(jane));
        assertEquals(4, preview.size());
        for (StatementReaderStore.PreviewRow row : preview) {
            assertEquals("ready", row.status());
            assertEquals("c1", row.customerId());
        }
        assertTrue(database.getStatementRecords("c1").isEmpty());
    }

    @Test public void partialNamesRequireManualSelectionAndAmbiguousNamesStayReview() {
        List<StatementReaderStore.PreviewRow> partial = store.preview(hash('b'),
                List.of(source("1:1", 1, 1, "9/8 Zelle from Jane", "9/8", "20.00", "deposit", "")),
                List.of(jane));
        assertEquals("review", partial.get(0).status());
        assertEquals("", partial.get(0).customerId());

        Customer sameName = new Customer("c3", "Jane", "Paret Swihl", "", "", "", "Monday", BigDecimal.ZERO, "");
        List<StatementReaderStore.PreviewRow> ambiguous = store.preview(hash('c'),
                List.of(source("1:1", 1, 1, "9/8 Zelle From Jane Paret-Swihl", "9/8", "20.00", "deposit", "")),
                List.of(jane, sameName));
        assertEquals("review", ambiguous.get(0).status());
        assertEquals(2, ambiguous.get(0).candidateIds().size());
    }

    @Test public void importsNegativePaymentAndDurableAuditThenBlocksExactRetryAndRequiresFingerprintOverride() {
        String document = hash('d');
        StatementReaderStore.PreviewRow initial = store.preview(document,
                List.of(source("1:9", 1, 9, "9/8 Zelle From Jane Paret-Swihl Ref#ABC123", "9/8", "100.00", "deposit", "ABC123")),
                List.of(jane)).get(0);
        StatementReaderStore.ImportResult imported = store.importRows(document, List.of(initial), List.of(jane));
        assertEquals(1, imported.imported());
        assertEquals("imported", imported.rows().get(0).status());
        assertEquals(1, database.getStatementRecords("c1").size());
        assertEquals(StatementReaderStore.PAYMENT_TYPE, database.getStatementRecords("c1").get(0).getType());
        assertEquals(0, new BigDecimal("-100.00").compareTo(database.getStatementRecords("c1").get(0).getAmount()));

        StatementReaderStore.PreviewRow exactRetry = store.preview(document,
                List.of(source("1:9", 1, 9, "9/8 Zelle From Jane Paret-Swihl", "9/8", "100.00", "deposit", "ABC123")),
                List.of(jane)).get(0);
        assertEquals("imported", exactRetry.status());
        assertEquals(0, store.importRows(document, List.of(exactRetry), List.of(jane)).imported());
        assertEquals(1, database.getStatementRecords("c1").size());

        StatementReaderStore.PreviewRow duplicateFingerprint = store.preview(hash('e'),
                List.of(source("1:10", 1, 10, "9/8 Jane Paret-Swihl", "9/8", "100.00", "deposit", "")),
                List.of(jane)).get(0);
        assertEquals("duplicate", duplicateFingerprint.status());
        assertEquals(0, store.importRows(hash('e'), List.of(duplicateFingerprint), List.of(jane)).imported());
        StatementReaderStore.PreviewRow explicitOverride = new StatementReaderStore.PreviewRow(
                duplicateFingerprint.sourceId(), duplicateFingerprint.page(), duplicateFingerprint.line(),
                duplicateFingerprint.text(), duplicateFingerprint.date(), duplicateFingerprint.amount(),
                duplicateFingerprint.customerId(), duplicateFingerprint.candidateIds(), duplicateFingerprint.status(),
                duplicateFingerprint.reason(), duplicateFingerprint.reference(), true);
        assertEquals(1, store.importRows(hash('e'), List.of(explicitOverride), List.of(jane)).imported());
        assertEquals(2, database.getStatementRecords("c1").size());

        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT amount, reference, record_date, imported_at FROM reader_imports ORDER BY record_id LIMIT 1")) {
            assertTrue(result.next());
            assertTrue(result.getDouble("amount") < 0);
            assertEquals("ABC123", result.getString("reference"));
            assertEquals("09/08/2026", result.getString("record_date"));
            assertTrue(result.getString("imported_at").endsWith("Z"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test public void inaccessibleCustomersCannotMatchOrImport() {
        StatementReaderStore.PreviewRow row = store.preview(hash('f'),
                List.of(source("1:1", 1, 1, "9/8 Jane Paret-Swihl", "9/8", "12.00", "deposit", "")),
                List.of()).get(0);
        assertEquals("review", row.status());
        StatementReaderStore.PreviewRow forged = new StatementReaderStore.PreviewRow(row.sourceId(), row.page(), row.line(),
                row.text(), "10/26/2026", new BigDecimal("12.00"), "c1", List.of("c1"), "ready", "", "", false);
        assertEquals("skipped", store.importRows(hash('f'), List.of(forged), List.of()).rows().get(0).status());
        assertTrue(database.getStatementRecords("c1").isEmpty());
    }

    @Test public void bankReferenceIsHardDuplicateAndAuditFailureRollsBackPayment() throws Exception {
        String firstDocument = hash('1');
        StatementReaderStore.PreviewRow first = store.preview(firstDocument,
                List.of(source("1:1", 1, 1, "9/8 Jane Paret-Swihl Ref#BANK987", "9/8", "25.00", "deposit", "BANK987")),
                List.of(jane)).get(0);
        assertEquals(1, store.importRows(firstDocument, List.of(first), List.of(jane)).imported());

        StatementReaderStore.PreviewRow referenceRepeat = store.preview(hash('2'),
                List.of(source("1:2", 1, 2, "9/8 Jane Paret-Swihl Ref#bank987", "9/8", "50.00", "deposit", "bank987")),
                List.of(jane)).get(0);
        assertEquals("imported", referenceRepeat.status());
        assertEquals(0, store.importRows(hash('2'), List.of(referenceRepeat), List.of(jane)).imported());
        assertEquals(1, database.getStatementRecords("c1").size());

        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_reader_audit BEFORE INSERT ON reader_imports "
                    + "BEGIN SELECT RAISE(ABORT, 'test audit failure'); END");
        }
        StatementReaderStore.PreviewRow rollbackRow = store.preview(hash('3'),
                List.of(source("1:3", 1, 3, "9/9 Jane Paret-Swihl Ref#BANK988", "9/9", "30.00", "deposit", "BANK988")),
                List.of(jane)).get(0);
        assertThrows(IllegalStateException.class, () -> store.importRows(hash('3'), List.of(rollbackRow), List.of(jane)));
        assertEquals(1, database.getStatementRecords("c1").size());
    }

    @Test public void repeatedDocumentFingerprintNeedsASeparateExplicitOverride() {
        String document = hash('7');
        List<StatementReaderStore.PreviewRow> rows = store.preview(document, List.of(
                        source("1:20", 1, 20, "9/8 Jane Paret-Swihl", "9/8", "15.00", "deposit", ""),
                        source("1:21", 1, 21, "9/8 Jane Paret-Swihl", "9/8", "15.00", "deposit", "")),
                List.of(jane));
        assertEquals("ready", rows.get(0).status());
        assertEquals("duplicate", rows.get(1).status());
        StatementReaderStore.ImportResult result = store.importRows(document, rows, List.of(jane));
        assertEquals(1, result.imported());
        assertEquals(1, result.skipped());
        assertEquals(1, database.getStatementRecords("c1").size());
        StatementReaderStore.PreviewRow override = new StatementReaderStore.PreviewRow(
                rows.get(1).sourceId(), rows.get(1).page(), rows.get(1).line(), rows.get(1).text(),
                rows.get(1).date(), rows.get(1).amount(), rows.get(1).customerId(), rows.get(1).candidateIds(),
                rows.get(1).status(), rows.get(1).reason(), rows.get(1).reference(), true);
        assertEquals(1, store.importRows(document, List.of(override), List.of(jane)).imported());
    }

    private static StatementPdfReader.SourceRow source(String id, int page, int line, String text,
                                                       String date, String amount, String direction, String reference) {
        return new StatementPdfReader.SourceRow(id, page, line, text, date, amount, direction, reference);
    }

    private static String hash(char character) { return String.valueOf(character).repeat(64); }
}
