package com.poolapp.db;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class CompManagerApiClient {
    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;

    public CompManagerApiClient(String baseUrl) {
        this(baseUrl, null);
    }

    public CompManagerApiClient(String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("API base URL is required");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Object get(String path) {
        return request("GET", path, null);
    }

    public Object post(String path, JSONObject body) {
        return request("POST", path, body);
    }

    public Object put(String path, JSONObject body) {
        return request("PUT", path, body);
    }

    public Object delete(String path) {
        return request("DELETE", path, null);
    }

    private Object request(String method, String path, JSONObject body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(120))
                    .header("Accept", "application/json");
            if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Object parsed = response.body() == null || response.body().isBlank() ? JSONObject.NULL : new JSONTokener(response.body()).nextValue();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = parsed instanceof JSONObject object ? object.optString("error", "Request failed") : "Request failed";
                throw new IllegalStateException("API " + response.statusCode() + ": " + message);
            }
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("API request interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Unable to reach Comp Manager API: " + e.getMessage(), e);
        }
    }

    public static JSONObject object(Object value) {
        if (value instanceof JSONObject object) return object;
        throw new IllegalStateException("Expected a JSON object from Comp Manager API");
    }

    public static JSONArray array(Object value) {
        if (value instanceof JSONArray array) return array;
        throw new IllegalStateException("Expected a JSON array from Comp Manager API");
    }
}
