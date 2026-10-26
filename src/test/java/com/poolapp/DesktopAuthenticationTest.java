package com.poolapp;

import com.poolapp.db.CompManagerApiClient;
import com.poolapp.db.MasterDatabaseManager;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DesktopAuthenticationTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicReference<JSONObject> account = new AtomicReference<>();
    private int responseStatus;

    @Before public void start() throws Exception {
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v1/auth/login", exchange -> {
            attempts.incrementAndGet();
            JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("worker", body.getString("username"));
            assertEquals("password", body.getString("password"));
            String result = responseStatus == 200
                    ? new JSONObject().put("token", "signed-token").put("account", account.get()).toString()
                    : new JSONObject().put("error", "Sign-in unavailable").toString();
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    @After public void stop() { if (server != null) server.stop(0); }

    private MasterDatabaseManager manager() { return new MasterDatabaseManager(baseUrl, "old-token"); }

    @Test public void companyPasswordIsVerifiedOnlyOnce() {
        account.set(new JSONObject().put("role", "COMPANY").put("companyId", 7)
                .put("companyName", "Test").put("username", "worker"));
        MasterDatabaseManager manager = manager();
        var result = manager.authenticate("worker", "password");
        assertEquals("COMPANY", result.role());
        assertEquals(Long.valueOf(7), result.company().getId());
        assertNull(result.employee());
        assertEquals("signed-token", manager.getSessionToken());
        assertEquals(baseUrl, manager.getApiBaseUrl());
        assertEquals(1, attempts.get());
    }

    @Test public void employeePermissionsAndScopeSurviveSingleAuthentication() {
        account.set(new JSONObject().put("role", "EMPLOYEE").put("employeeId", 4).put("companyId", 7)
                .put("firstName", "Test").put("allowedCustomerIds", new org.json.JSONArray().put("c1"))
                .put("allowedDays", new org.json.JSONArray().put("Monday"))
                .put("permissions", new JSONObject().put("canAccessVisits", true).put("canEditStatements", false)));
        var result = manager().authenticate("worker", "password");
        assertEquals("EMPLOYEE", result.role());
        assertEquals(Long.valueOf(4), result.employee().getEmployeeId());
        assertTrue(result.employee().isCanAccessVisits());
        assertFalse(result.employee().isCanEditStatements());
        assertEquals(java.util.List.of("c1"), result.employee().getAllowedCustomerIds());
        assertEquals(1, attempts.get());
    }

    @Test public void masterPasswordIsVerifiedOnlyOnce() {
        account.set(new JSONObject().put("role", "MASTER"));
        assertEquals("MASTER", manager().authenticate("worker", "password").role());
        assertEquals(1, attempts.get());
    }

    @Test public void invalidCredentialsClearOldSessionWithoutFallbackRequests() {
        responseStatus = 401;
        MasterDatabaseManager manager = manager();
        assertNull(manager.authenticate("worker", "password"));
        assertNull(manager.getSessionToken());
        assertEquals(1, attempts.get());
    }

    @Test public void serverErrorsAreVisibleAndNotRetriedAsDifferentRoles() {
        responseStatus = 503;
        var error = assertThrows(IllegalStateException.class, () -> manager().authenticate("worker", "password"));
        assertTrue(error.getMessage().contains("503"));
        assertEquals(1, attempts.get());
    }

    @Test public void apiClientsReuseTheSameHttpConnectionPool() throws Exception {
        var field = CompManagerApiClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        assertSame(field.get(new CompManagerApiClient(baseUrl)), field.get(new CompManagerApiClient(baseUrl, "token")));
    }
}
