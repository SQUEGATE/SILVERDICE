package com.poolapp;

import com.poolapp.db.CustomerBrowserStore;
import com.poolapp.db.DatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.model.EmployeeProfile;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CustomerBrowserStoreTest {
    @Test public void localPagesAreBoundedFilteredScopedAndLeaveFullRouteAndArrowsAvailable() throws Exception {
        Path directory = Files.createDirectories(Path.of("customer-browser-test-" + UUID.randomUUID()));
        try {
            DatabaseManager database = new DatabaseManager(directory.resolve("customers.db"));
            for (int i = 0; i < 55; i++) {
                Customer customer = new Customer("c" + i, "First", String.format("%03d", i), "",
                        "5550000000", "", "Monday", BigDecimal.ZERO, "");
                customer.setRouteOrder(i + 1);
                database.addCustomer(customer);
            }
            database.addCustomer(new Customer("hidden", "Hidden", "Customer", "", "", "", "Tuesday", BigDecimal.ZERO, ""));
            CustomerBrowserStore store = new CustomerBrowserStore(database);
            assertEquals(50, store.page("", "Monday", 0, null).rows().size());
            var second = store.page("", "Monday", 50, null);
            assertEquals(55, second.total());
            assertEquals(5, second.rows().size());
            assertEquals(55, store.fullDay("Monday", null).rows().size());
            assertThrows(IllegalArgumentException.class, () -> store.fullDay("All", null));
            assertEquals("c50", second.rows().get(0).getId());
            assertEquals(55, store.page("5550000000", "All", 0, null).total());
            EmployeeProfile employee = new EmployeeProfile();
            employee.setAllowedCustomerIds(List.of("c54"));
            employee.setAllowedDays(List.of("Tuesday"));
            assertEquals(2, store.page("", "All", 0, employee).total());
            assertEquals(1, store.page("", "Monday", 0, employee).total());
            assertEquals(56, database.getAllCustomersOrderedById().size());
            List<String> completeDay = database.getAllCustomers().stream().filter(c -> c.getServiceDay().equals("Monday"))
                    .map(Customer::getId).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            java.util.Collections.reverse(completeDay);
            database.reorderCustomers("Monday", completeDay);
            assertEquals(55, store.page("", "Monday", 0, null).total());
            assertEquals("c54", store.page("", "Monday", 0, null).rows().get(0).getId());
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test public void remoteBrowsingUsesOptInPaginationAndServerFiltering() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = new JSONObject().put("total", 101).put("rows", new JSONArray().put(new JSONObject()
                    .put("customer_id", "c51").put("first_name", "First").put("service_day", "Monday")
                    .put("amount_charged", "25.00").put("route_order", 51))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var page = new CustomerBrowserStore(new DatabaseManager(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", 1)).page("First Last", "Monday", 50, null);
            assertEquals("/v1/customers?limit=50&offset=50&q=First+Last&service_day=Monday", uri.get());
            assertEquals("Bearer synthetic", auth.get());
            assertEquals(101, page.total());
            assertEquals(51, page.rows().get(0).getRouteOrder());
        } finally { server.stop(0); }
    }
}
