package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.SalesQuoteStore;
import com.poolapp.model.SalesQuote;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SalesQuoteApiTest {
    @Test public void summaryPagesAndFullDetailsUseSeparateAuthenticatedRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        JSONObject full = SalesQuoteTest.draft("c1", "Full notes", SalesQuote.DEFAULT_TERMS,
                List.of(SalesQuoteTest.line("Bill", "Item", "-10"))).body()
                .put("quote_id", 52).put("quote_number", "SQ-000052").put("customer_id", "c1")
                .put("converted_at", JSONObject.NULL).put("created_at", "").put("updated_at", "");
        JSONObject summary = new JSONObject(full.toString());
        summary.remove("lines"); summary.remove("notes"); summary.remove("terms");
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            if (!"Bearer synthetic-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            Object result = exchange.getRequestURI().getQuery() == null ? full
                    : new JSONObject().put("rows", new JSONArray().put(summary)).put("total", 53);
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            SalesQuoteStore store = new SalesQuoteStore(new DatabaseManager(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-token", 1));
            var page = store.page("c1", 50, 50);
            assertEquals(53, page.total());
            assertEquals(52, page.rows().get(0).id());
            assertEquals("Full notes", store.get("c1", 52).notes());
            assertEquals(List.of("/v1/customers/c1/quotes?summary=1&limit=50&offset=50",
                    "/v1/customers/c1/quotes/52"), requests);
        } finally { server.stop(0); }
    }

    @Test public void remoteCrudAndConversionFollowAuthenticatedContract() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        List<JSONObject> bodies = java.util.Collections.synchronizedList(new ArrayList<>());
        SalesQuote draft = SalesQuoteTest.draft("c1", "Separate notes", SalesQuote.DEFAULT_TERMS,
                List.of(SalesQuoteTest.line("Bill", "Motor", "-120.25")));
        JSONObject stored = draft.body().put("quote_id", 7).put("quote_number", "SQ-000007").put("customer_id", "c1")
                .put("converted_at", JSONObject.NULL).put("created_at", "2026-10-26T15:00:00Z")
                .put("updated_at", "2026-10-26T15:00:00Z");
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
            requests.add(method + " " + path);
            if (!"Bearer synthetic-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) bodies.add(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            JSONObject response = new JSONObject(stored.toString());
            if (path.endsWith("/convert")) response.put("converted_at", "2026-10-26T16:00:00Z");
            String json = method.equals("GET") ? new JSONArray().put(response).toString()
                    : method.equals("DELETE") ? "{}" : response.toString();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            DatabaseManager database = new DatabaseManager("http://127.0.0.1:" + server.getAddress().getPort(),
                    "synthetic-token", 1);
            assertNotNull(database.getApiClient());
            SalesQuoteStore store = new SalesQuoteStore(database);
            SalesQuote listed = store.list("c1").get(0);
            assertFalse(listed.converted());
            assertEquals("-120.25", listed.lines().get(0).amount().toPlainString());
            SalesQuote created = store.save(draft);
            assertEquals("SQ-000007", created.quoteNumber());
            store.save(created); store.delete("c1", created.quoteId());
            SalesQuote converted = store.convert("c1", 7);
            assertTrue(converted.converted());
            assertThrows(IllegalStateException.class, () -> store.save(converted));
            assertEquals(List.of("GET /v1/customers/c1/quotes", "POST /v1/customers/c1/quotes",
                    "PUT /v1/customers/c1/quotes/7", "DELETE /v1/customers/c1/quotes/7",
                    "POST /v1/customers/c1/quotes/7/convert"), requests);
            assertEquals(draft.body().toMap(), bodies.get(0).toMap());
            assertTrue(bodies.get(0).getJSONArray("lines").getJSONObject(0).get("amount") instanceof Number);
        } finally { server.stop(0); }
    }
}
