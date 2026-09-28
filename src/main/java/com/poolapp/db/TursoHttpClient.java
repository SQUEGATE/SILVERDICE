package com.poolapp.db;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for Turso's SQL-over-HTTP API (POST /v2/pipeline).
 * Not yet wired into DatabaseManager/MasterDatabaseManager; each call opens and
 * closes its own connection (simple, stateless), matching Turso's "Simple query" pattern.
 */
public class TursoHttpClient {
    private final String pipelineUrl;
    private final String authToken;
    private final HttpClient httpClient;

    public TursoHttpClient(String databaseUrl, String authToken) {
        String httpsUrl = databaseUrl.replaceFirst("^(libsql|turso)://", "https://");
        this.pipelineUrl = httpsUrl.endsWith("/") ? httpsUrl + "v2/pipeline" : httpsUrl + "/v2/pipeline";
        this.authToken = authToken;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Executes a single statement with positional '?' parameters and returns rows as column-name maps. */
    public List<Map<String, String>> execute(String sql, Object... args) throws IOException, InterruptedException {
        JSONObject stmt = new JSONObject();
        stmt.put("sql", sql);
        if (args.length > 0) {
            JSONArray jsonArgs = new JSONArray();
            for (Object arg : args) {
                jsonArgs.put(toArgObject(arg));
            }
            stmt.put("args", jsonArgs);
        }

        JSONObject executeRequest = new JSONObject();
        executeRequest.put("type", "execute");
        executeRequest.put("stmt", stmt);

        JSONObject closeRequest = new JSONObject();
        closeRequest.put("type", "close");

        JSONObject body = new JSONObject();
        body.put("requests", new JSONArray().put(executeRequest).put(closeRequest));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pipelineUrl))
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Turso request failed with status " + response.statusCode() + ": " + response.body());
        }

        return parseRows(new JSONObject(response.body()));
    }

    private JSONObject toArgObject(Object value) {
        JSONObject arg = new JSONObject();
        if (value == null) {
            arg.put("type", "null");
        } else if (value instanceof Integer || value instanceof Long) {
            arg.put("type", "integer");
            arg.put("value", String.valueOf(value));
        } else if (value instanceof BigDecimal || value instanceof Double || value instanceof Float) {
            arg.put("type", "float");
            arg.put("value", String.valueOf(value));
        } else {
            arg.put("type", "text");
            arg.put("value", String.valueOf(value));
        }
        return arg;
    }

    private List<Map<String, String>> parseRows(JSONObject responseBody) {
        List<Map<String, String>> rows = new ArrayList<>();
        JSONArray results = responseBody.optJSONArray("results");
        if (results == null || results.isEmpty()) {
            return rows;
        }

        JSONObject firstResult = results.getJSONObject(0);
        if (!"ok".equals(firstResult.optString("type"))) {
            return rows;
        }

        JSONObject result = firstResult.getJSONObject("response").optJSONObject("result");
        if (result == null) {
            return rows;
        }

        JSONArray cols = result.optJSONArray("cols");
        JSONArray dataRows = result.optJSONArray("rows");
        if (cols == null || dataRows == null) {
            return rows;
        }

        for (int rowIndex = 0; rowIndex < dataRows.length(); rowIndex++) {
            JSONArray rowValues = dataRows.getJSONArray(rowIndex);
            Map<String, String> row = new LinkedHashMap<>();
            for (int colIndex = 0; colIndex < cols.length(); colIndex++) {
                String columnName = cols.getJSONObject(colIndex).optString("name");
                JSONObject cell = rowValues.getJSONObject(colIndex);
                row.put(columnName, "null".equals(cell.optString("type")) ? null : cell.optString("value", null));
            }
            rows.add(row);
        }
        return rows;
    }
}
