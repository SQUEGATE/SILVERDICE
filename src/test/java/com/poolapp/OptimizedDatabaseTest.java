package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.model.VisitRecord;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class OptimizedDatabaseTest {
    private Path directory;
    private DatabaseManager database;

    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("comp-optimized-db-");
        database = new DatabaseManager(directory.resolve("test.db"));
        database.addCustomer(new Customer("1", "Demo", "Avery", "", "", "", "Monday", new BigDecimal("100"), ""));
        database.addCustomer(new Customer("2", "Sample", "Blake", "", "", "", "Tuesday", new BigDecimal("150"), ""));
    }

    @After public void cleanup() throws Exception {
        if (directory != null) try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test public void groupedBalancesAndStatementPagesKeepFullRunningBalances() {
        database.addStatementRecord("1", "10/01/2026", "Service", new BigDecimal("100"));
        database.addStatementRecord("1", "10/02/2026", "Zelle Payment", new BigDecimal("-25"));
        database.addStatementRecord("1", "10/03/2026", "Service", new BigDecimal("20"));
        assertEquals(new BigDecimal("95.00"), database.getStatementBalances().get("1"));
        assertEquals(new BigDecimal("0.00"), database.getStatementBalances().get("2"));
        JSONObject first = database.getStatementRecordPage("1", 1, 0);
        assertEquals(3, first.getInt("total"));
        assertEquals(0, new BigDecimal("95").compareTo(first.getBigDecimal("balance")));
        assertEquals("10/03/2026", first.getJSONArray("rows").getJSONObject(0).getString("record_date"));
        assertEquals(0, new BigDecimal("95").compareTo(first.getJSONArray("rows").getJSONObject(0).getBigDecimal("run")));
        JSONObject second = database.getStatementRecordPage("1", 1, 1).getJSONArray("rows").getJSONObject(0);
        assertEquals(0, new BigDecimal("75").compareTo(second.getBigDecimal("run")));
        assertEquals(3, database.getStatementRecords("1").size());
        assertThrows(IllegalArgumentException.class, () -> database.getStatementRecordPage("1", 0, 0));
    }

    @Test public void monthlyBatchIsAtomicScopedAndRetrySafe() {
        String request = UUID.randomUUID().toString();
        assertEquals(1, database.addMonthlyStatements(request, "10/31/2026", "Service", List.of("1")));
        assertEquals(1, database.addMonthlyStatements(request, "10/31/2026", "Service", List.of("1")));
        assertEquals(1, database.getStatementRecords("1").size());
        assertTrue(database.getStatementRecords("2").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> database.addMonthlyStatements(request, "10/31/2026", "Other", List.of("1")));
        assertEquals(1, database.getStatementRecords("1").size());
        assertThrows(IllegalArgumentException.class,
                () -> database.addMonthlyStatements(UUID.randomUUID().toString(), "2/30/2026", "Service"));
    }

    @Test public void statementPagingPreservesLegacyUnpaddedDateOrderAndRunningBalance() {
        database.addStatementRecord("1", "9/8/2026", "Service", new BigDecimal("100"));
        database.addStatementRecord("1", "10/01/2026", "Zelle Payment", new BigDecimal("-25"));
        database.addStatementRecord("1", "9/12/2026", "Service", new BigDecimal("20"));
        var full = database.getStatementRecords("1");
        var page = database.getStatementRecordPage("1", 50, 0).getJSONArray("rows");
        for (int index = 0; index < full.size(); index++) {
            assertEquals(full.get(index).getDate(), page.getJSONObject(index).getString("record_date"));
        }
        assertEquals(0, new BigDecimal("95").compareTo(page.getJSONObject(0).getBigDecimal("run")));
        assertEquals(0, new BigDecimal("120").compareTo(page.getJSONObject(1).getBigDecimal("run")));
        assertEquals(0, new BigDecimal("100").compareTo(page.getJSONObject(2).getBigDecimal("run")));
    }

    @Test public void monthlyBatchRollsBackEveryChargeWhenOneFails() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.getDatabasePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_monthly BEFORE INSERT ON statement_records WHEN NEW.customer_id = '2'"
                    + " BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END");
        }
        String request = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> database.addMonthlyStatements(request, "10/31/2026", "Service"));
        assertTrue(database.getStatementRecords("1").isEmpty());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.getDatabasePath());
             var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_monthly");
        }
        assertEquals(2, database.addMonthlyStatements(request, "10/31/2026", "Service"));
    }

    @Test public void visitPagesOmitPhotoButDetailRoundTripsIt() {
        String photo = "data:image/jpeg;base64,SGVsbG8=";
        VisitRecord visit = database.saveVisit(new VisitRecord(0, "1", Instant.now(), "7.4", "2-4", "60",
                null, 300, "Synthetic notes", photo));
        JSONObject history = database.getVisitPage("1", 1, 0);
        assertEquals(1, history.getInt("total"));
        JSONObject row = history.getJSONArray("rows").getJSONObject(0);
        assertTrue(row.getBoolean("has_photo"));
        assertFalse(row.has("photo_data"));
        assertEquals(photo, database.getVisit("1", visit.id()).photoData());
        assertThrows(IllegalStateException.class, () -> database.getVisit("2", visit.id()));
        assertEquals(photo, database.getVisits("1").get(0).photoData());
    }

    @Test public void bulkSettingsCommitTogetherAndRejectIncompleteValuesWithoutPartialWrites() {
        database.savePdfSettings(java.util.Map.of("companyName", "Synthetic company", "showLogo", "true"));
        assertEquals("Synthetic company", database.getAllPdfSettings().get("companyName"));
        java.util.Map<String, String> invalid = new java.util.LinkedHashMap<>();
        invalid.put("companyName", "Should not commit");
        invalid.put("showLogo", null);
        assertThrows(IllegalArgumentException.class, () -> database.savePdfSettings(invalid));
        assertEquals("Synthetic company", database.getAllPdfSettings().get("companyName"));
        assertEquals("true", database.getAllPdfSettings().get("showLogo"));
    }
}
