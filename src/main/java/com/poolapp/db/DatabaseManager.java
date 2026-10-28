package com.poolapp.db;

import com.poolapp.model.Customer;
import com.poolapp.model.StatementRecord;
import com.poolapp.model.VisitRecord;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatabaseManager {
    private final Path databasePath;
    private final String dbUrl;
    private final CompManagerApiClient apiClient;
    private final long companyId;
    private final String apiSessionToken;

    public DatabaseManager() {
        this(getDefaultDatabasePath());
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.dbUrl = "jdbc:sqlite:" + this.databasePath;
        this.apiClient = null;
        this.companyId = 0;
        this.apiSessionToken = null;
        createDatabaseBackup();
        initializeDatabase();
    }

    public DatabaseManager(String apiBaseUrl, String apiSessionToken, long companyId) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank() || apiSessionToken == null || apiSessionToken.isBlank() || companyId < 1) {
            throw new IllegalArgumentException("Remote API URL, session token, and company ID are required");
        }
        this.databasePath = null;
        this.dbUrl = null;
        this.apiClient = new CompManagerApiClient(apiBaseUrl, apiSessionToken);
        this.companyId = companyId;
        this.apiSessionToken = apiSessionToken;
    }

    public static Path getDefaultDatabasePath() {
        return Paths.get(determineDatabasePath());
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public JSONObject exportBackup() {
        if (apiClient == null) throw new IllegalStateException("Cloud backup requires a cloud connection");
        return CompManagerApiClient.object(apiGet("/v1/backup"));
    }

    public void restoreBackup(JSONObject backup) {
        if (apiClient == null) throw new IllegalStateException("Cloud restore requires a cloud connection");
        apiPost("/v1/backup/restore", backup);
    }

    public boolean isRemote() {
        return apiClient != null;
    }

    public CompManagerApiClient getApiClient() {
        return apiClient;
    }

    public String getApiSessionToken() {
        return apiSessionToken;
    }

    private Object apiGet(String path) {
        return apiClient.get(path);
    }

    private Object apiPost(String path, JSONObject body) {
        return apiClient.post(path, body);
    }

    private Object apiPut(String path, JSONObject body) {
        return apiClient.put(path, body);
    }

    private Object apiDelete(String path) {
        return apiClient.delete(path);
    }

    private static String determineDatabasePath() {
        Path projectRoot = determineProjectRoot();
        return projectRoot.resolve("customer.db").toAbsolutePath().toString();
    }

    private static Path determineProjectRoot() {
        try {
            Path codeLocation = Paths.get(DatabaseManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeLocation)) {
                codeLocation = codeLocation.getParent();
            }
            Path current = codeLocation;
            while (current != null) {
                if (Files.exists(current.resolve("build.gradle")) || Files.exists(current.resolve("settings.gradle"))) {
                    return current;
                }
                current = current.getParent();
            }
        } catch (URISyntaxException ignored) {
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private void createDatabaseBackup() {
        if (Files.exists(databasePath)) {
            Path backupPath = databasePath.resolveSibling(databasePath.getFileName().toString() + ".bak");
            try {
                if (!Files.exists(backupPath)) {
                    Files.copy(databasePath, backupPath);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to create database backup", e);
            }
        }
    }

    private void initializeDatabase() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS customers ("
                    + "customer_id TEXT PRIMARY KEY,"
                    + "first_name TEXT NOT NULL,"
                    + "last_name TEXT NOT NULL,"
                    + "address TEXT,"
                    + "city TEXT,"
                    + "state TEXT,"
                    + "zip TEXT,"
                    + "phone TEXT,"
                    + "email TEXT,"
                    + "service_day TEXT,"
                    + "amount_charged REAL,"
                    + "notes TEXT,"
                    + "starting_date TEXT DEFAULT '',"
                    + "status TEXT DEFAULT 'Active',"
                    + "route_order INTEGER DEFAULT 0"
                    + ");");
            statement.execute("CREATE TABLE IF NOT EXISTS statement_records ("
                    + "record_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "customer_id TEXT NOT NULL,"
                    + "record_date TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "amount REAL NOT NULL"
                    + ");");
            statement.execute("CREATE INDEX IF NOT EXISTS statement_records_customer_history ON statement_records(customer_id, record_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS monthly_statement_batches ("
                    + "request_id TEXT PRIMARY KEY, record_date TEXT NOT NULL, type TEXT NOT NULL,"
                    + "customer_ids TEXT NOT NULL, added INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS record_types ("
                    + "type_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "type_name TEXT UNIQUE NOT NULL"
                    + ");");
            statement.execute("CREATE TABLE IF NOT EXISTS customer_visits ("
                    + "visit_id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id TEXT NOT NULL,"
                    + "visited_at TEXT NOT NULL, ph TEXT NOT NULL, chlo TEXT NOT NULL, stb TEXT NOT NULL,"
                    + "alk INTEGER CHECK(alk > 0), cal INTEGER CHECK(cal > 0),"
                    + "notes TEXT NOT NULL DEFAULT '', photo_data TEXT NOT NULL DEFAULT '', salt INTEGER)");
            migrateVisits(connection);
            addColumnIfMissing(connection, "customer_visits", "salt", "INTEGER");
            statement.execute("CREATE INDEX IF NOT EXISTS customer_visits_history ON customer_visits(customer_id, visited_at DESC, visit_id DESC)");
            statement.execute("CREATE TABLE IF NOT EXISTS pdf_settings ("
                    + "setting_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "setting_key TEXT UNIQUE NOT NULL,"
                    + "setting_value TEXT NOT NULL"
                    + ");");
            addColumnIfMissing(connection, "customers", "city", "TEXT");
            addColumnIfMissing(connection, "customers", "state", "TEXT");
            addColumnIfMissing(connection, "customers", "zip", "TEXT");
            addColumnIfMissing(connection, "customers", "starting_date", "TEXT DEFAULT ''");
            addColumnIfMissing(connection, "customers", "status", "TEXT DEFAULT 'Active'");
            addColumnIfMissing(connection, "customers", "route_order", "INTEGER DEFAULT 0");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private void addColumnIfMissing(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName);
        if (!columns.next()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
            }
        }
    }

    private void migrateVisits(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "customer_visits", "photo_data", "TEXT NOT NULL DEFAULT ''");
        boolean rebuild = false;
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(customer_visits)")) {
            while (columns.next()) {
                String name = columns.getString("name");
                if (("alk".equals(name) || "cal".equals(name)) && columns.getInt("notnull") == 1) rebuild = true;
            }
        }
        if (!rebuild) return;
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer_visits_optional ("
                    + "visit_id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id TEXT NOT NULL,"
                    + "visited_at TEXT NOT NULL, ph TEXT NOT NULL, chlo TEXT NOT NULL, stb TEXT NOT NULL,"
                    + "alk INTEGER CHECK(alk > 0), cal INTEGER CHECK(cal > 0),"
                    + "notes TEXT NOT NULL DEFAULT '', photo_data TEXT NOT NULL DEFAULT '')");
            statement.execute("INSERT INTO customer_visits_optional SELECT visit_id, customer_id, visited_at,"
                    + " ph, chlo, stb, alk, cal, notes, photo_data FROM customer_visits");
            statement.execute("DROP TABLE customer_visits");
            statement.execute("ALTER TABLE customer_visits_optional RENAME TO customer_visits");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    public String getNextCustomerId() {
        if (isRemote()) return CompManagerApiClient.object(apiGet("/v1/customers/next-id")).getString("customer_id");
        String sql = "SELECT MAX(CAST(customer_id AS INTEGER)) AS max_id FROM customers WHERE customer_id GLOB '[0-9]*'";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                int maxId = resultSet.getInt("max_id");
                if (!resultSet.wasNull()) {
                    return String.valueOf(maxId + 1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to determine next customer ID", e);
        }
        return "1";
    }

    public void addCustomer(Customer customer) {
        if (customer.getId() == null || customer.getId().isBlank()) {
            customer.setId(Customer.generateId());
        }
        if (isRemote()) {
            apiPost("/v1/customers", customerJson(customer));
            return;
        }
        String sql = "INSERT INTO customers (customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes, starting_date, status, route_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customer.getId());
            statement.setString(2, customer.getFirstName());
            statement.setString(3, customer.getLastName());
            statement.setString(4, customer.getAddress());
            statement.setString(5, customer.getCity());
            statement.setString(6, customer.getState());
            statement.setString(7, customer.getZip());
            statement.setString(8, customer.getPhone());
            statement.setString(9, customer.getEmail());
            statement.setString(10, customer.getServiceDay());
            statement.setBigDecimal(11, customer.getAmountCharged());
            statement.setString(12, customer.getNotes());
            statement.setString(13, customer.getStartingDate());
            statement.setString(14, customer.getStatus());
            statement.setInt(15, customer.getRouteOrder());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to add customer", e);
        }
    }

    public void updateCustomer(Customer customer) {
        if (isRemote()) {
            apiPut("/v1/customers/" + encodePath(customer.getId()), customerJson(customer));
            return;
        }
        String sql = "UPDATE customers SET first_name = ?, last_name = ?, address = ?, city = ?, state = ?, zip = ?, phone = ?, email = ?, service_day = ?, amount_charged = ?, notes = ?, starting_date = ?, status = ?, route_order = ? WHERE customer_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customer.getFirstName());
            statement.setString(2, customer.getLastName());
            statement.setString(3, customer.getAddress());
            statement.setString(4, customer.getCity());
            statement.setString(5, customer.getState());
            statement.setString(6, customer.getZip());
            statement.setString(7, customer.getPhone());
            statement.setString(8, customer.getEmail());
            statement.setString(9, customer.getServiceDay());
            statement.setBigDecimal(10, customer.getAmountCharged());
            statement.setString(11, customer.getNotes());
            statement.setString(12, customer.getStartingDate());
            statement.setString(13, customer.getStatus());
            statement.setInt(14, customer.getRouteOrder());
            statement.setString(15, customer.getId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to update customer", e);
        }
    }

    public List<Customer> getAllCustomers() {
        if (isRemote()) return remoteCustomers(CompManagerApiClient.array(apiGet("/v1/customers")));
        String sql = "SELECT * FROM customers ORDER BY service_day, CASE WHEN route_order > 0 THEN 0 ELSE 1 END, route_order, last_name, first_name";
        List<Customer> customers = new ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                customers.add(readCustomer(resultSet));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load customers", e);
        }
        return customers;
    }

    public List<Customer> searchCustomers(String query) {
        if (isRemote()) return remoteCustomers(CompManagerApiClient.array(apiGet("/v1/customers?q=" + encodeQuery(query))));
        String sql = "SELECT * FROM customers WHERE lower(first_name) LIKE ? OR lower(last_name) LIKE ? OR lower(address) LIKE ? OR lower(city) LIKE ? OR lower(state) LIKE ? OR lower(zip) LIKE ? OR lower(email) LIKE ? ORDER BY service_day, CASE WHEN route_order > 0 THEN 0 ELSE 1 END, route_order, last_name, first_name";
        List<Customer> customers = new ArrayList<>();
        String pattern = "%" + query.toLowerCase() + "%";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            statement.setString(3, pattern);
            statement.setString(4, pattern);
            statement.setString(5, pattern);
            statement.setString(6, pattern);
            statement.setString(7, pattern);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    customers.add(readCustomer(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to search customers", e);
        }
        return customers;
    }

    public List<Customer> searchCustomersByName(String query) {
        if (isRemote()) return remoteCustomers(CompManagerApiClient.array(apiGet("/v1/customers?q=" + encodeQuery(query))));
        String sql = "SELECT * FROM customers WHERE lower(first_name) LIKE ? OR lower(last_name) LIKE ? ORDER BY CASE WHEN customer_id GLOB '[0-9]*' THEN CAST(customer_id AS INTEGER) ELSE 999999999 END, customer_id";
        List<Customer> customers = new ArrayList<>();
        String pattern = "%" + query.toLowerCase() + "%";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    customers.add(readCustomer(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to search customers by name", e);
        }
        return customers;
    }

    public List<Customer> getAllCustomersOrderedById() {
        if (isRemote()) return remoteCustomers(CompManagerApiClient.array(apiGet("/v1/customers?order=id")));
        String sql = "SELECT * FROM customers ORDER BY CASE WHEN customer_id GLOB '[0-9]*' THEN CAST(customer_id AS INTEGER) ELSE 999999999 END, customer_id";
        List<Customer> customers = new ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                customers.add(readCustomer(resultSet));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load customers ordered by ID", e);
        }
        return customers;
    }

    public Customer getCustomerById(String customerId) {
        if (isRemote()) {
            try {
                return remoteCustomer(CompManagerApiClient.object(apiGet("/v1/customers/" + encodePath(customerId))));
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("API 404:")) return null;
                throw e;
            }
        }
        String sql = "SELECT * FROM customers WHERE customer_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readCustomer(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load customer by ID", e);
        }
        return null;
    }

    public List<VisitRecord> getVisits(String customerId) {
        List<VisitRecord> visits = new ArrayList<>();
        if (isRemote()) {
            JSONArray rows = CompManagerApiClient.array(apiGet("/v1/customers/" + encodePath(customerId) + "/visits"));
            for (int i = 0; i < rows.length(); i++) visits.add(VisitRecord.fromJson(rows.getJSONObject(i)));
            return visits;
        }
        String sql = "SELECT * FROM customer_visits WHERE customer_id = ? ORDER BY visited_at DESC, visit_id DESC";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customerId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) visits.add(readVisit(rows));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load visits", e);
        }
        return visits;
    }

    public JSONObject getVisitPage(String customerId, int limit, int offset) {
        validatePage(limit, offset);
        if (isRemote()) return CompManagerApiClient.object(apiGet("/v1/customers/" + encodePath(customerId)
                + "/visits?summary=1&limit=" + limit + "&offset=" + offset));
        JSONArray visits = new JSONArray();
        try (Connection connection = getConnection();
             PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM customer_visits WHERE customer_id = ?");
             PreparedStatement query = connection.prepareStatement("SELECT visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, salt, notes,"
                     + " CASE WHEN photo_data <> '' THEN 1 ELSE 0 END AS has_photo FROM customer_visits"
                     + " WHERE customer_id = ? ORDER BY visited_at DESC, visit_id DESC LIMIT ? OFFSET ?")) {
            count.setString(1, customerId);
            int total;
            try (ResultSet result = count.executeQuery()) {
                if (!result.next()) throw new SQLException("Visit count was not returned.");
                total = result.getInt(1);
            }
            query.setString(1, customerId);
            query.setInt(2, limit);
            query.setInt(3, offset);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    JSONObject visit = new JSONObject().put("visit_id", rows.getLong("visit_id"))
                            .put("customer_id", customerId).put("visited_at", rows.getString("visited_at"))
                            .put("ph", rows.getString("ph")).put("chlo", rows.getString("chlo"))
                            .put("stb", rows.getString("stb")).put("notes", rows.getString("notes"))
                            .put("alk", rows.getObject("alk") == null ? JSONObject.NULL : rows.getInt("alk"))
                            .put("cal", rows.getObject("cal") == null ? JSONObject.NULL : rows.getInt("cal"))
                            .put("salt", rows.getObject("salt") == null ? JSONObject.NULL : rows.getInt("salt"))
                            .put("has_photo", rows.getInt("has_photo") != 0);
                    visits.put(visit);
                }
            }
            return new JSONObject().put("rows", visits).put("total", total);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to load visit history.", e);
        }
    }

    public VisitRecord getVisit(String customerId, long visitId) {
        if (visitId < 1) throw new IllegalArgumentException("Select a saved visit.");
        if (isRemote()) return VisitRecord.fromJson(CompManagerApiClient.object(
                apiGet("/v1/customers/" + encodePath(customerId) + "/visits/" + visitId)));
        try (Connection connection = getConnection()) {
            return loadLocalVisit(connection, customerId, visitId);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to load visit.", e);
        }
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0) throw new IllegalArgumentException("Invalid record page.");
    }
    public void deleteVisit(String customerId, long visitId) {
        if (visitId < 1) throw new IllegalArgumentException("Select a saved visit to delete.");
        if (isRemote()) {
            apiDelete("/v1/customers/" + encodePath(customerId) + "/visits/" + visitId);
            return;
        }
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM customer_visits WHERE customer_id = ? AND visit_id = ?")) {
            statement.setString(1, customerId);
            statement.setLong(2, visitId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Visit no longer exists for this customer.");
        } catch (SQLException e) {
            throw new RuntimeException("Unable to delete visit", e);
        }
    }

    public VisitRecord saveVisit(VisitRecord visit) {
        if (isRemote()) {
            String path = "/v1/customers/" + encodePath(visit.customerId()) + "/visits";
            Object result = visit.id() == 0 ? apiPost(path, visit.measurementsJson())
                    : apiPut(path + "/" + visit.id(), visit.measurementsJson());
            return VisitRecord.fromJson(CompManagerApiClient.object(result));
        }
        try (Connection connection = getConnection()) {
            if (visit.id() == 0) {
                String sql = "INSERT INTO customer_visits(customer_id, visited_at, ph, chlo, stb, alk, cal, notes, photo_data, salt)"
                        + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS(SELECT 1 FROM customers WHERE customer_id = ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, visit.customerId());
                    statement.setString(2, java.time.Instant.now().toString());
                    bindVisitMeasurements(statement, 3, visit);
                    statement.setString(11, visit.customerId());
                    if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Customer no longer exists.");
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("No visit ID returned");
                        return loadLocalVisit(connection, visit.customerId(), keys.getLong(1));
                    }
                }
            }
            String sql = "UPDATE customer_visits SET ph = ?, chlo = ?, stb = ?, alk = ?, cal = ?, notes = ?, photo_data = ?, salt = ?"
                    + " WHERE visit_id = ? AND customer_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindVisitMeasurements(statement, 1, visit);
                statement.setLong(9, visit.id());
                statement.setString(10, visit.customerId());
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Visit no longer exists for this customer.");
            }
            return loadLocalVisit(connection, visit.customerId(), visit.id());
        } catch (SQLException e) {
            throw new RuntimeException("Unable to save visit", e);
        }
    }

    private void bindVisitMeasurements(PreparedStatement statement, int index, VisitRecord visit) throws SQLException {
        statement.setString(index++, visit.ph());
        statement.setString(index++, visit.chlo());
        statement.setString(index++, visit.stb());
        statement.setObject(index++, visit.alk(), java.sql.Types.INTEGER);
        statement.setObject(index++, visit.cal(), java.sql.Types.INTEGER);
        statement.setString(index++, visit.notes());
        statement.setString(index++, visit.photoData());
        statement.setObject(index, visit.salt(), java.sql.Types.INTEGER);
    }

    private VisitRecord loadLocalVisit(Connection connection, String customerId, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM customer_visits WHERE customer_id = ? AND visit_id = ?")) {
            statement.setString(1, customerId);
            statement.setLong(2, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Saved visit not found");
                return readVisit(row);
            }
        }
    }

    private VisitRecord readVisit(ResultSet row) throws SQLException {
        return new VisitRecord(row.getLong("visit_id"), row.getString("customer_id"),
                java.time.Instant.parse(row.getString("visited_at")), row.getString("ph"),
                row.getString("chlo"), row.getString("stb"),
                row.getObject("alk") == null ? null : row.getInt("alk"),
                row.getObject("cal") == null ? null : row.getInt("cal"), row.getString("notes"), row.getString("photo_data"),
                row.getObject("salt") == null ? null : row.getInt("salt"));
    }

    public List<StatementRecord> getStatementRecords(String customerId) {
        List<StatementRecord> records = loadStatementRecords(customerId);
        records.sort(java.util.Comparator
                .comparing((StatementRecord r) -> parseRecordDate(r.getDate()))
                .thenComparing(r -> r.getAmount().signum() >= 0 ? 0 : 1)
                .thenComparingLong(StatementRecord::getId)
                .reversed());
        return records;
    }

    public JSONObject getStatementRecordPage(String customerId, int limit, int offset) {
        validatePage(limit, offset);
        if (isRemote()) return CompManagerApiClient.object(apiGet("/v1/customers/" + encodePath(customerId)
                + "/statements?limit=" + limit + "&offset=" + offset));
        String dateTail = "substr(trim(record_date), instr(trim(record_date), '/') + 1)";
        String month = "substr(trim(record_date), 1, instr(trim(record_date), '/') - 1)";
        String day = "substr(" + dateTail + ", 1, instr(" + dateTail + ", '/') - 1)";
        String year = "substr(" + dateTail + ", instr(" + dateTail + ", '/') + 1)";
        String isoDate = "printf('%04d-%02d-%02d', CAST(" + year + " AS INTEGER), CAST(" + month
                + " AS INTEGER), CAST(" + day + " AS INTEGER))";
        String dateKey = "CASE WHEN length(" + month + ") BETWEEN 1 AND 2 AND " + month + " NOT GLOB '*[^0-9]*'"
                + " AND length(" + day + ") BETWEEN 1 AND 2 AND " + day + " NOT GLOB '*[^0-9]*'"
                + " AND length(" + year + ") BETWEEN 1 AND 4 AND " + year + " NOT GLOB '*[^0-9]*'"
                + " AND CAST(" + year + " AS INTEGER) > 0 AND date(" + isoDate + ", '+0 days') = " + isoDate
                + " THEN " + isoDate + " ELSE '9999-99-99' END";
        String order = dateKey + ", CASE WHEN amount >= 0 THEN 0 ELSE 1 END, record_id";
        String sql = "SELECT record_id, record_date, type, amount,"
                + " SUM(amount) OVER (ORDER BY " + order + " ROWS UNBOUNDED PRECEDING) AS run"
                + " FROM statement_records WHERE customer_id = ? ORDER BY " + dateKey
                + " DESC, CASE WHEN amount >= 0 THEN 0 ELSE 1 END DESC, record_id DESC LIMIT ? OFFSET ?";
        try (Connection connection = getConnection();
             PreparedStatement totals = connection.prepareStatement("SELECT COUNT(*), COALESCE(SUM(amount),0) FROM statement_records WHERE customer_id = ?");
             PreparedStatement query = connection.prepareStatement(sql)) {
            totals.setString(1, customerId);
            int total;
            BigDecimal balance;
            try (ResultSet row = totals.executeQuery()) {
                if (!row.next()) throw new SQLException("Statement totals were not returned.");
                total = row.getInt(1);
                balance = BigDecimal.valueOf(row.getDouble(2)).setScale(2, RoundingMode.HALF_UP);
            }
            query.setString(1, customerId);
            query.setInt(2, limit);
            query.setInt(3, offset);
            JSONArray rows = new JSONArray();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) rows.put(new JSONObject().put("record_id", result.getLong("record_id"))
                        .put("record_date", result.getString("record_date")).put("type", result.getString("type"))
                        .put("amount", result.getBigDecimal("amount")).put("run", result.getBigDecimal("run")));
            }
            return new JSONObject().put("rows", rows).put("total", total).put("balance", balance);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to load statement page.", e);
        }
    }

    private static java.time.LocalDate parseRecordDate(String text) {
        try {
            String[] p = text.trim().split("/");
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            int year = Integer.parseInt(p[2]);
            return java.time.LocalDate.of(year, a, b);
        } catch (Exception e) {
            return java.time.LocalDate.MAX;
        }
    }

    private List<StatementRecord> loadStatementRecords(String customerId) {
        if (isRemote()) return remoteStatements(CompManagerApiClient.array(apiGet("/v1/customers/" + encodePath(customerId) + "/statements")));
        String sql = "SELECT record_id, record_date, type, amount FROM statement_records WHERE customer_id = ? ORDER BY record_id";
        List<StatementRecord> records = new ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long id = resultSet.getLong("record_id");
                    String date = resultSet.getString("record_date");
                    String type = resultSet.getString("type");
                    BigDecimal amount = BigDecimal.valueOf(resultSet.getDouble("amount")).setScale(2, RoundingMode.HALF_UP);
                    records.add(new StatementRecord(id, date, type, amount));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load statement records", e);
        }
        return records;
    }

    public void addStatementRecord(String customerId, String date, String type, BigDecimal amount) {
        if (isRemote()) {
            apiPost("/v1/customers/" + encodePath(customerId) + "/statements",
                    new JSONObject().put("record_date", date).put("type", type).put("amount", amount));
            return;
        }
        String sql = "INSERT INTO statement_records (customer_id, record_date, type, amount) VALUES (?, ?, ?, ?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customerId);
            statement.setString(2, date);
            statement.setString(3, type);
            statement.setBigDecimal(4, amount);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to add statement record", e);
        }
    }

    public void deleteStatementRecord(long recordId) {
        if (isRemote()) {
            apiDelete("/v1/statements/" + recordId);
            return;
        }
        String sql = "DELETE FROM statement_records WHERE record_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recordId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to delete statement record", e);
        }
    }

    public List<String> getDistinctStatementRecordTypes() {
        if (isRemote()) return remoteStringArray(CompManagerApiClient.array(apiGet("/v1/statement-types")));
        String sql = "SELECT DISTINCT type FROM statement_records ORDER BY type";
        List<String> types = new ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String type = resultSet.getString("type");
                if (type != null && !type.isBlank()) {
                    types.add(type);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load statement record types", e);
        }
        return types;
    }

    public void normalizeStatementRecordAmountsForType(String type, boolean debitType) {
        if (isRemote()) {
            apiPost("/v1/statement-types/normalize", new JSONObject().put("type", type).put("debit", debitType));
            return;
        }
        String sql = "UPDATE statement_records SET amount = ? WHERE record_id = ?";
        String selectSql = "SELECT record_id, amount FROM statement_records WHERE type = ?";
        try (Connection connection = getConnection();
             PreparedStatement selectStatement = connection.prepareStatement(selectSql);
             PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            selectStatement.setString(1, type);
            try (ResultSet resultSet = selectStatement.executeQuery()) {
                while (resultSet.next()) {
                    long recordId = resultSet.getLong("record_id");
                    BigDecimal amount = BigDecimal.valueOf(resultSet.getDouble("amount")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal normalizedAmount = debitType ? amount.abs().negate() : amount.abs();
                    updateStatement.setBigDecimal(1, normalizedAmount);
                    updateStatement.setLong(2, recordId);
                    updateStatement.addBatch();
                }
            }
            updateStatement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to normalize statement amounts for type", e);
        }
    }

    public BigDecimal getStatementBalance(String customerId) {
        if (isRemote()) {
            JSONObject result = CompManagerApiClient.object(apiGet("/v1/customers/" + encodePath(customerId) + "/balance"));
            return BigDecimal.valueOf(result.optDouble("balance", 0)).setScale(2, RoundingMode.HALF_UP);
        }
        String sql = "SELECT SUM(amount) AS total FROM statement_records WHERE customer_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    double total = resultSet.getDouble("total");
                    return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to compute statement balance", e);
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, BigDecimal> getStatementBalances() {
        Map<String, BigDecimal> balances = new HashMap<>();
        if (isRemote()) {
            JSONArray rows = CompManagerApiClient.object(apiGet("/v1/balances")).getJSONArray("rows");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                balances.put(row.getString("customer_id"), row.getBigDecimal("balance").setScale(2, RoundingMode.HALF_UP));
            }
            return balances;
        }
        try (Connection connection = getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT c.customer_id, COALESCE(SUM(r.amount), 0) AS balance"
                     + " FROM customers c LEFT JOIN statement_records r ON r.customer_id = c.customer_id GROUP BY c.customer_id");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) balances.put(rows.getString("customer_id"),
                    BigDecimal.valueOf(rows.getDouble("balance")).setScale(2, RoundingMode.HALF_UP));
            return balances;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to load customer balances.", e);
        }
    }

    public int addMonthlyStatements(String requestId, String date, String type) {
        return addMonthlyStatements(requestId, date, type, null);
    }

    public int addMonthlyStatements(String requestId, String date, String type, List<String> customerIds) {
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("A valid monthly billing request ID is required.");
        }
        String parsedDate = StatementReaderStore.parseDate(date, java.time.Year.now().getValue());
        if (date == null || !date.equals(parsedDate)) throw new IllegalArgumentException("Date must be a valid MM/DD/YYYY date.");
        if (type == null || type.isBlank() || type.length() > 128) throw new IllegalArgumentException("Choose a valid record type.");
        if (isRemote()) return CompManagerApiClient.object(apiPost("/v1/statements/monthly",
                new JSONObject().put("request_id", requestId).put("date", date).put("type", type))).getInt("added");
        List<String> scope = customerIds == null ? null : customerIds.stream().distinct().sorted().toList();
        String scopeKey = scope == null ? "*" : new JSONArray(scope).toString();
        try (Connection connection = getConnection()) {
            try (Statement lock = connection.createStatement()) { lock.execute("BEGIN IMMEDIATE"); }
            try {
                try (PreparedStatement existing = connection.prepareStatement("SELECT * FROM monthly_statement_batches WHERE request_id = ?")) {
                    existing.setString(1, requestId);
                    try (ResultSet row = existing.executeQuery()) {
                        if (row.next()) {
                            if (!date.equals(row.getString("record_date")) || !type.equals(row.getString("type"))
                                    || !scopeKey.equals(row.getString("customer_ids"))) {
                                throw new IllegalArgumentException("This billing request was already used with different details.");
                            }
                            int added = row.getInt("added");
                            try (Statement commit = connection.createStatement()) { commit.execute("COMMIT"); }
                            return added;
                        }
                    }
                }
                int added = 0;
                try (PreparedStatement customers = connection.prepareStatement("SELECT customer_id, amount_charged FROM customers"
                        + " WHERE COALESCE(status,'Active') <> 'Inactive' ORDER BY customer_id");
                     PreparedStatement insert = connection.prepareStatement("INSERT INTO statement_records(customer_id, record_date, type, amount) VALUES (?, ?, ?, ?)");
                     ResultSet rows = customers.executeQuery()) {
                    java.util.Set<String> allowed = scope == null ? null : new java.util.HashSet<>(scope);
                    while (rows.next()) {
                        String id = rows.getString("customer_id");
                        if (allowed != null && !allowed.contains(id)) continue;
                        insert.setString(1, id);
                        insert.setString(2, date);
                        insert.setString(3, type);
                        insert.setBigDecimal(4, BigDecimal.valueOf(rows.getDouble("amount_charged")).abs().setScale(2, RoundingMode.HALF_UP));
                        insert.addBatch();
                        added++;
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement audit = connection.prepareStatement("INSERT INTO monthly_statement_batches VALUES (?, ?, ?, ?, ?)")) {
                    audit.setString(1, requestId);
                    audit.setString(2, date);
                    audit.setString(3, type);
                    audit.setString(4, scopeKey);
                    audit.setInt(5, added);
                    audit.executeUpdate();
                }
                try (Statement commit = connection.createStatement()) { commit.execute("COMMIT"); }
                return added;
            } catch (SQLException | RuntimeException e) {
                try (Statement rollback = connection.createStatement()) { rollback.execute("ROLLBACK"); }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to apply monthly billing batch.", e);
        }
    }

    public Map<String, BigDecimal> getRevenueByDay() {
        if (isRemote()) {
            JSONObject response = CompManagerApiClient.object(apiGet("/v1/revenue"));
            Map<String, BigDecimal> result = new HashMap<>();
            JSONArray rows = response.optJSONArray("by_day");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                result.put(row.optString("service_day", "Unknown"), BigDecimal.valueOf(row.optDouble("total", 0)).setScale(2, RoundingMode.HALF_UP));
            }
            return result;
        }
        String sql = "SELECT service_day, SUM(amount_charged) AS total FROM customers WHERE COALESCE(status, 'Active') <> 'Inactive' GROUP BY service_day";
        Map<String, BigDecimal> revenue = new HashMap<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String day = resultSet.getString("service_day");
                BigDecimal total = BigDecimal.valueOf(resultSet.getDouble("total")).setScale(2, RoundingMode.HALF_UP);
                revenue.put(day == null ? "Unknown" : day, total);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to calculate revenue per day", e);
        }
        return revenue;
    }

    public BigDecimal getTotalRevenue() {
        if (isRemote()) return BigDecimal.valueOf(CompManagerApiClient.object(apiGet("/v1/revenue")).optDouble("total", 0)).setScale(2, RoundingMode.HALF_UP);
        String sql = "SELECT SUM(amount_charged) AS total FROM customers WHERE COALESCE(status, 'Active') <> 'Inactive'";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                double total = resultSet.getDouble("total");
                return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
            }
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } catch (SQLException e) {
            throw new RuntimeException("Unable to calculate total revenue", e);
        }
    }

    private JSONObject customerJson(Customer customer) {
        return new JSONObject()
                .put("customer_id", customer.getId())
                .put("first_name", customer.getFirstName())
                .put("last_name", customer.getLastName())
                .put("address", customer.getAddress())
                .put("city", customer.getCity())
                .put("state", customer.getState())
                .put("zip", customer.getZip())
                .put("phone", customer.getPhone())
                .put("email", customer.getEmail())
                .put("service_day", customer.getServiceDay())
                .put("amount_charged", customer.getAmountCharged())
                .put("notes", customer.getNotes())
                .put("starting_date", customer.getStartingDate())
                .put("status", customer.getStatus())
                .put("route_order", customer.getRouteOrder());
    }

    public void reorderCustomers(String serviceDay, List<String> customerIds) {
        if (isRemote()) {
            apiPost("/v1/customers/reorder", new JSONObject().put("service_day", serviceDay).put("customer_ids", new JSONArray(customerIds)));
            return;
        }
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement("UPDATE customers SET route_order = ? WHERE customer_id = ? AND service_day = ?")) {
            int position = 1;
            for (String id : customerIds) {
                statement.setInt(1, position++);
                statement.setString(2, id);
                statement.setString(3, serviceDay);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to save customer order", e);
        }
    }

    private List<Customer> remoteCustomers(JSONArray rows) {
        List<Customer> customers = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) customers.add(remoteCustomer(rows.getJSONObject(i)));
        return customers;
    }

    private Customer remoteCustomer(JSONObject row) {
        Customer customer = new Customer(row.optString("customer_id"), row.optString("first_name"), row.optString("last_name"),
                row.optString("address"), row.optString("city"), row.optString("state"), row.optString("zip"),
                row.optString("phone"), row.optString("email"), row.optString("service_day"),
                BigDecimal.valueOf(row.optDouble("amount_charged", 0)).setScale(2, RoundingMode.HALF_UP), row.optString("notes"));
        customer.setStartingDate(row.optString("starting_date", ""));
        customer.setStatus(row.optString("status", "Active"));
        customer.setRouteOrder(row.optInt("route_order", 0));
        return customer;
    }

    private List<StatementRecord> remoteStatements(JSONArray rows) {
        List<StatementRecord> records = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            records.add(new StatementRecord(row.optLong("record_id"), row.optString("record_date"), row.optString("type"),
                    BigDecimal.valueOf(row.optDouble("amount", 0)).setScale(2, RoundingMode.HALF_UP)));
        }
        return records;
    }

    private List<String> remoteStringArray(JSONArray array) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i);
            if (value != null && !value.isBlank()) values.add(value);
        }
        return values;
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return encodeQuery(value).replace("+", "%20");
    }

    private Customer readCustomer(ResultSet resultSet) throws SQLException {
        String id = resultSet.getString("customer_id");
        String firstName = resultSet.getString("first_name");
        String lastName = resultSet.getString("last_name");
        String address = resultSet.getString("address");
        String city = resultSet.getString("city");
        String state = resultSet.getString("state");
        String zip = resultSet.getString("zip");
        String phone = resultSet.getString("phone");
        String email = resultSet.getString("email");
        String serviceDay = resultSet.getString("service_day");
        BigDecimal amount = BigDecimal.valueOf(resultSet.getDouble("amount_charged")).setScale(2, RoundingMode.HALF_UP);
        String notes = resultSet.getString("notes");
        Customer customer = new Customer(id, firstName, lastName, address, city, state, zip, phone, email, serviceDay, amount, notes);
        customer.setStartingDate(resultSet.getString("starting_date"));
        customer.setStatus(resultSet.getString("status"));
        customer.setRouteOrder(resultSet.getInt("route_order"));
        return customer;
    }

    public List<String> getRecordTypes() {
        if (isRemote()) return remoteStringArray(CompManagerApiClient.array(apiGet("/v1/record-types")));
        String sql = "SELECT type_name FROM record_types ORDER BY type_name";
        List<String> types = new ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                types.add(resultSet.getString("type_name"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load record types", e);
        }
        return types;
    }

    public void addRecordType(String typeName) {
        if (isRemote()) {
            apiPost("/v1/record-types", new JSONObject().put("type_name", typeName));
            return;
        }
        String sql = "INSERT INTO record_types (type_name) VALUES (?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, typeName);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to add record type", e);
        }
    }

    public void updateRecordType(String oldName, String newName) {
        if (isRemote()) {
            apiPut("/v1/record-types", new JSONObject().put("old_name", oldName).put("new_name", newName));
            return;
        }
        String sql = "UPDATE record_types SET type_name = ? WHERE type_name = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newName);
            statement.setString(2, oldName);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to update record type", e);
        }
    }

    public void deleteRecordType(String typeName) {
        if (isRemote()) {
            apiDelete("/v1/record-types/" + encodePath(typeName));
            return;
        }
        String sql = "DELETE FROM record_types WHERE type_name = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, typeName);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to delete record type", e);
        }
    }

    public void ensureDefaultTypes() {
        List<String> types = getRecordTypes();
        if (types.isEmpty()) {
            try {
                addRecordType("Bill");
                addRecordType("Payment");
                addRecordType("Check");
            } catch (Exception e) {
                // Ignore if types already exist (unique constraint)
            }
        }
    }

    public List<String> getExpenseKeywords() {
        JSONArray keywords;
        if (isRemote()) {
            keywords = CompManagerApiClient.object(apiGet("/v1/expense-keywords")).optJSONArray("keywords");
        } else {
            try {
                keywords = new JSONArray(getPdfSetting("expenseKeywords", "[]"));
            } catch (RuntimeException e) {
                throw new IllegalStateException("Saved expense keywords could not be read.", e);
            }
        }
        List<String> values = new ArrayList<>();
        if (keywords != null) for (int i = 0; i < keywords.length(); i++) values.add(keywords.optString(i));
        return new ExpenseScanner().normalizeKeywords(values);
    }

    public List<String> saveExpenseKeywords(List<String> values) {
        List<String> normalized = new ExpenseScanner().normalizeKeywords(values);
        JSONArray json = new JSONArray(normalized);
        if (isRemote()) {
            JSONObject response = CompManagerApiClient.object(apiPut("/v1/expense-keywords",
                    new JSONObject().put("keywords", json)));
            if (!response.optBoolean("saved")) throw new IllegalStateException("Expense keywords were not saved.");
            JSONArray saved = response.optJSONArray("keywords");
            List<String> result = new ArrayList<>();
            if (saved != null) for (int i = 0; i < saved.length(); i++) result.add(saved.optString(i));
            return new ExpenseScanner().normalizeKeywords(result);
        }
        savePdfSetting("expenseKeywords", json.toString());
        return normalized;
    }

    // PDF Settings Methods
    public void savePdfSetting(String key, String value) {
        if (isRemote()) {
            apiPut("/v1/settings", new JSONObject().put("key", key).put("value", value));
            return;
        }
        String sql = "INSERT OR REPLACE INTO pdf_settings (setting_key, setting_value) VALUES (?, ?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to save PDF setting", e);
        }
    }

    public void savePdfSettings(Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) throw new IllegalArgumentException("No PDF settings were provided.");
        if (isRemote()) {
            apiPut("/v1/settings/batch", new JSONObject().put("settings", new JSONObject(settings)));
            return;
        }
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO pdf_settings(setting_key, setting_value) VALUES (?, ?)")) {
                for (Map.Entry<String, String> setting : settings.entrySet()) {
                    if (setting.getKey() == null || setting.getKey().isBlank() || setting.getValue() == null) {
                        throw new IllegalArgumentException("PDF setting keys and values are required.");
                    }
                    statement.setString(1, setting.getKey());
                    statement.setString(2, setting.getValue());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to save PDF settings.", e);
        }
    }

    public String getPdfSetting(String key, String defaultValue) {
        if (isRemote()) return CompManagerApiClient.object(apiGet("/v1/settings")).optString(key, defaultValue);
        String sql = "SELECT setting_value FROM pdf_settings WHERE setting_key = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("setting_value");
                }
            }
        } catch (SQLException e) {
            // Return default value if setting doesn't exist or error occurs
        }
        return defaultValue;
    }

    public Map<String, String> getAllPdfSettings() {
        if (isRemote()) {
            JSONObject response = CompManagerApiClient.object(apiGet("/v1/settings"));
            Map<String, String> values = new HashMap<>();
            for (String key : response.keySet()) values.put(key, response.optString(key, ""));
            return values;
        }
        String sql = "SELECT setting_key, setting_value FROM pdf_settings";
        Map<String, String> settings = new HashMap<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                settings.put(resultSet.getString("setting_key"), resultSet.getString("setting_value"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load PDF settings", e);
        }
        return settings;
    }
}
