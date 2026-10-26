package com.poolapp;

import com.poolapp.db.MasterDatabaseManager;
import com.poolapp.model.EmployeeProfile;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SalesQuotePermissionTest {
    @Test
    public void quotePermissionDefaultsOffAndRoundTripsThroughEmployeeApi() throws Exception {
        assertFalse(new EmployeeProfile().isCanAccessSalesQuote());
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/admin/companies/1/employees", exchange -> {
            byte[] response;
            if ("POST".equals(exchange.getRequestMethod())) {
                saved.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                response = new JSONObject().put("employee_id", 9).toString().getBytes(StandardCharsets.UTF_8);
            } else {
                JSONObject employee = new JSONObject(saved.get().toString()).put("employee_id", 9).put("company_id", 1);
                response = new JSONArray().put(employee).toString().getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        String previousUrl = System.getProperty("comp.manager.api.url");
        try {
            System.setProperty("comp.manager.api.url", "http://127.0.0.1:" + server.getAddress().getPort());
            MasterDatabaseManager manager = new MasterDatabaseManager(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-session");
            EmployeeProfile employee = new EmployeeProfile();
            employee.setCompanyId(1);
            employee.setUsername("synthetic-quoter");
            employee.setFirstName("Test");
            employee.setLastName("Quoter");
            employee.setCanAccessSalesQuote(true);
            manager.saveEmployee(employee);
            assertTrue(saved.get().getJSONObject("permissions").getBoolean("canAccessSalesQuote"));
            assertTrue(manager.getEmployeesForCompany(1).get(0).isCanAccessSalesQuote());
            employee.setCanAccessSalesQuote(false);
            manager.saveEmployee(employee);
            assertFalse(manager.getEmployeesForCompany(1).get(0).isCanAccessSalesQuote());
        } finally {
            if (previousUrl == null) System.clearProperty("comp.manager.api.url");
            else System.setProperty("comp.manager.api.url", previousUrl);
            server.stop(0);
        }
    }
}
