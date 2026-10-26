package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class OptimizedApiContractTest {
    @Test public void optimizedReadsAndWritesUseBoundedAuthenticatedRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        List<JSONObject> writes = java.util.Collections.synchronizedList(new ArrayList<>());
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!request.isBlank()) writes.add(new JSONObject(request));
            String path = exchange.getRequestURI().getPath();
            JSONObject response = path.equals("/v1/balances")
                    ? new JSONObject().put("rows", new JSONArray().put(new JSONObject().put("customer_id", "1").put("balance", 75)))
                    : path.endsWith("/monthly") ? new JSONObject().put("added", 1)
                    : path.endsWith("/batch") ? new JSONObject().put("saved", true)
                    : new JSONObject().put("rows", new JSONArray()).put("total", 0).put("balance", 0);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            boolean authenticated = "Bearer synthetic-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(authenticated ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DatabaseManager database = new DatabaseManager("http://127.0.0.1:" + server.getAddress().getPort(),
                    "synthetic-token", 1);
            assertEquals(new BigDecimal("75.00"), database.getStatementBalances().get("1"));
            database.getStatementRecordPage("1", 50, 100);
            database.getVisitPage("1", 50, 0);
            String requestId = UUID.randomUUID().toString();
            assertEquals(1, database.addMonthlyStatements(requestId, "10/31/2026", "Service"));
            database.savePdfSettings(Map.of("companyName", "Synthetic", "showLogo", "false"));
            assertEquals(List.of("GET /v1/balances", "GET /v1/customers/1/statements?limit=50&offset=100",
                    "GET /v1/customers/1/visits?summary=1&limit=50&offset=0",
                    "POST /v1/statements/monthly", "PUT /v1/settings/batch"), calls);
            assertEquals(requestId, writes.get(0).getString("request_id"));
            assertEquals(2, writes.get(1).getJSONObject("settings").length());
        } finally {
            server.stop(0);
        }
    }
}
