package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.StatementReaderStore;
import com.poolapp.service.StatementPdfReader;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class StatementReaderApiContractTest {
    @Test public void remotePreviewAndImportUseOnlyExactBackendFieldsAndExplicitOverrideBoolean() throws Exception {
        AtomicReference<String> previewBody = new AtomicReference<>();
        AtomicReference<String> importBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/statement-reader/preview", exchange -> {
            previewBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            String result = "{\"year\":2026,\"rows\":[{\"source_id\":\"1:2\",\"page\":1,\"line\":2,"
                    + "\"text\":\"9/8 Zelle from Jane Doe\",\"date\":\"09/08/2026\",\"amount\":10.00,"
                    + "\"customer_id\":\"c1\",\"candidate_ids\":[\"c1\"],\"status\":\"ready\","
                    + "\"reason\":\"Unique full name\",\"reference\":\"ABC123\"}]}";
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v1/statement-reader/import", exchange -> {
            importBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String result = "{\"imported\":1,\"skipped\":0,\"rows\":[{\"source_id\":\"1:2\","
                    + "\"status\":\"imported\",\"reason\":\"Payment imported\",\"record_id\":42}]}";
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DatabaseManager database = new DatabaseManager("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", 1);
            StatementReaderStore store = new StatementReaderStore(database);
            StatementPdfReader.SourceRow source = new StatementPdfReader.SourceRow("1:2", 1, 2,
                    "9/8 Zelle from Jane Doe", "9/8", "10.00", "deposit", "ABC123");
            StatementReaderStore.PreviewRow preview = store.preview("a".repeat(64), List.of(source), List.of()).get(0);
            assertEquals("ready", preview.status());
            assertEquals("09/08/2026", preview.date());
            assertEquals("ABC123", preview.reference());

            JSONObject previewRequest = new JSONObject(previewBody.get());
            assertEquals(Set.of("document_hash", "rows"), previewRequest.keySet());
            JSONArray previewRows = previewRequest.getJSONArray("rows");
            assertEquals(1, previewRows.length());
            assertEquals(Set.of("source_id", "page", "line", "text", "date_text", "deposit_text", "direction", "reference"),
                    previewRows.getJSONObject(0).keySet());

            StatementReaderStore.ImportResult imported = store.importRows("a".repeat(64), List.of(preview), List.of());
            assertEquals(1, imported.imported());
            assertEquals(Long.valueOf(42), imported.rows().get(0).recordId());
            JSONObject importRequest = new JSONObject(importBody.get());
            assertEquals(Set.of("document_hash", "rows"), importRequest.keySet());
            JSONObject importRow = importRequest.getJSONArray("rows").getJSONObject(0);
            assertEquals(Set.of("source_id", "page", "line", "text", "date", "amount", "customer_id", "reference", "override_duplicate"),
                    importRow.keySet());
            assertTrue(importRow.has("override_duplicate"));
            assertFalse(importRow.getBoolean("override_duplicate"));

            StatementReaderStore.PreviewRow overridden = new StatementReaderStore.PreviewRow(preview.sourceId(),
                    preview.page(), preview.line(), preview.text(), preview.date(), new BigDecimal("10.00"),
                    preview.customerId(), preview.candidateIds(), "duplicate", "Duplicate", preview.reference(), true);
            store.importRows("a".repeat(64), List.of(overridden), List.of());
            assertTrue(new JSONObject(importBody.get()).getJSONArray("rows").getJSONObject(0).getBoolean("override_duplicate"));
        } finally {
            server.stop(0);
        }
    }
}
