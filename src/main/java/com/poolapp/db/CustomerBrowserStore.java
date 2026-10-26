package com.poolapp.db;

import com.poolapp.model.Customer;
import com.poolapp.model.EmployeeProfile;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Customer browsing with optional paging; route reorder continues to use its complete day list. */
public final class CustomerBrowserStore {
    private final DatabaseManager database;
    public CustomerBrowserStore(DatabaseManager database) { this.database = database; }
    public record Page(List<Customer> rows, int total) { }

    public Page page(String query, String day, int offset, EmployeeProfile employee) {
        return load(query, day, offset, employee, false);
    }

    public Page all(String query, String day, EmployeeProfile employee) {
        return load(query, day, 0, employee, true);
    }

    public Page fullDay(String day, EmployeeProfile employee) {
        if (day == null || "All".equals(day)) throw new IllegalArgumentException("Choose a single route day");
        return load("", day, 0, employee, true);
    }

    private Page load(String query, String day, int offset, EmployeeProfile employee, boolean fullDay) {
        if (offset < 0) throw new IllegalArgumentException("Invalid customer page");
        if (database.isRemote()) {
            Object response = database.getApiClient().get("/v1/customers?"
                    + (fullDay ? "" : "limit=50&offset=" + offset + "&") + "q=" + encode(query)
                    + ("All".equals(day) ? "" : "&service_day=" + encode(day)));
            JSONObject result = fullDay ? null : CompManagerApiClient.object(response);
            List<Customer> rows = new ArrayList<>();
            var json = fullDay ? CompManagerApiClient.array(response) : result.getJSONArray("rows");
            for (int i = 0; i < json.length(); i++) rows.add(customer(json.getJSONObject(i)));
            return new Page(rows, fullDay ? rows.size() : result.getInt("total"));
        }
        List<String> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!query.isBlank()) {
            where.append(" AND (");
            String[] columns = {"customer_id", "first_name", "last_name", "address", "city", "state", "zip", "phone", "email",
                    "first_name || ' ' || last_name"};
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) where.append(" OR ");
                where.append("lower(").append(columns[i]).append(") LIKE ?");
                parameters.add("%" + query.toLowerCase(java.util.Locale.ROOT) + "%");
            }
            where.append(")");
        }
        if (!"All".equals(day)) { where.append(" AND service_day=?"); parameters.add(day); }
        if (employee != null && (!employee.getAllowedCustomerIds().isEmpty() || !employee.getAllowedDays().isEmpty())) {
            where.append(" AND (1=0");
            for (String id : employee.getAllowedCustomerIds()) { where.append(" OR customer_id=?"); parameters.add(id); }
            for (String allowedDay : employee.getAllowedDays()) { where.append(" OR service_day=?"); parameters.add(allowedDay); }
            where.append(")");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getDatabasePath())) {
            int total;
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM customers" + where)) {
                bind(count, parameters);
                try (ResultSet rs = count.executeQuery()) { rs.next(); total = rs.getInt(1); }
            }
            List<Customer> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT * FROM customers" + where
                    + " ORDER BY service_day,CASE WHEN route_order>0 THEN 0 ELSE 1 END,route_order,last_name,first_name,customer_id LIMIT "
                    + (fullDay ? "-1" : "50") + " OFFSET ?")) {
                bind(select, parameters); select.setInt(parameters.size() + 1, offset);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        ResultSetMetaData metadata = rs.getMetaData();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnName(i), rs.getObject(i));
                        rows.add(customer(row));
                    }
                }
            }
            return new Page(rows, total);
        } catch (SQLException e) { throw new IllegalStateException("Unable to load customer page.", e); }
    }

    private static void bind(PreparedStatement statement, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Customer customer(JSONObject row) {
        Customer customer = new Customer(row.getString("customer_id"), row.optString("first_name"), row.optString("last_name"),
                row.optString("address"), row.optString("city"), row.optString("state"), row.optString("zip"),
                row.optString("phone"), row.optString("email"), row.optString("service_day"),
                new BigDecimal(row.opt("amount_charged") == null ? "0" : row.get("amount_charged").toString()), row.optString("notes"));
        customer.setStartingDate(row.optString("starting_date"));
        customer.setStatus(row.optString("status", "Active"));
        customer.setRouteOrder(row.optInt("route_order"));
        return customer;
    }
}
