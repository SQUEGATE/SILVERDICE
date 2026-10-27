package com.poolapp;

import com.poolapp.db.DatabaseManager;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class ExpenseKeywordsPersistenceTest {
    private Path directory;
    private Path databasePath;
    private DatabaseManager database;

    @Before public void setUp() throws Exception {
        directory = Files.createDirectories(Path.of("expense-keyword-test-" + UUID.randomUUID()));
        databasePath = directory.resolve("company.db");
        database = new DatabaseManager(databasePath);
    }

    @After public void cleanUp() throws Exception {
        if (directory != null) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test public void savesNormalizedOrderedKeywordsInPdfSettingsAndReloadsThem() throws Exception {
        List<String> saved = database.saveExpenseKeywords(List.of("  Fuel   surcharge ", "fuel SURCHARGE", "Office supplies"));
        assertEquals(List.of("Fuel surcharge", "Office supplies"), saved);
        assertEquals(saved, database.getExpenseKeywords());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.prepareStatement("SELECT setting_value FROM pdf_settings WHERE setting_key = ?")) {
            statement.setString(1, "expenseKeywords");
            try (var rows = statement.executeQuery()) {
                org.junit.Assert.assertTrue(rows.next());
                assertEquals(new JSONArray(List.of("Fuel surcharge", "Office supplies")).toString(), rows.getString(1));
            }
        }
    }
}
