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

public class StatementReaderPermissionTest {
    @Test
    public void readerAccessDefaultsOffAndDoesNotGrantStatementEditing() throws Exception {
        EmployeeProfile employee = new EmployeeProfile();
        assertFalse(employee.isCanAccessStatementReader());
        employee.setCompanyId(1);
        employee.setUsername("synthetic-reader");
        employee.setFirstName("Test");
        employee.setLastName("Reader");
        employee.setCanAccessStatementReader(true);
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/admin/companies/1/employees", exchange -> {
            Object response;
            if ("POST".equals(exchange.getRequestMethod())) {
                saved.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                response = new JSONObject().put("employee_id", 12);
            } else {
                response = new JSONArray().put(new JSONObject(saved.get().toString()).put("employee_id", 12).put("company_id", 1));
            }
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        String previous = System.getProperty("comp.manager.api.url");
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            System.setProperty("comp.manager.api.url", base);
            MasterDatabaseManager manager = new MasterDatabaseManager(base, "synthetic-session");
            manager.saveEmployee(employee);
            var read = manager.getEmployeesForCompany(1).get(0);
            assertTrue(read.isCanAccessStatementReader());
            assertFalse(read.isCanEditStatements());
            employee.setCanAccessStatementReader(false);
            employee.setCanEditStatements(true);
            manager.saveEmployee(employee);
            read = manager.getEmployeesForCompany(1).get(0);
            assertFalse(read.isCanAccessStatementReader());
            assertTrue(read.isCanEditStatements());
        } finally {
            if (previous == null) System.clearProperty("comp.manager.api.url");
            else System.setProperty("comp.manager.api.url", previous);
            server.stop(0);
        }
    }
}
