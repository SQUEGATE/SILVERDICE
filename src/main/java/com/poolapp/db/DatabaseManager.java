package com.poolapp.db;

import com.poolapp.model.Customer;
import com.poolapp.model.StatementRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    public DatabaseManager() {
        this(getDefaultDatabasePath());
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.dbUrl = "jdbc:sqlite:" + this.databasePath;
        createDatabaseBackup();
        initializeDatabase();
    }

    public static Path getDefaultDatabasePath() {
        return Paths.get(determineDatabasePath());
    }

    public Path getDatabasePath() {
        return databasePath;
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
                    + "notes TEXT"
                    + ");");
            statement.execute("CREATE TABLE IF NOT EXISTS statement_records ("
                    + "record_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "customer_id TEXT NOT NULL,"
                    + "record_date TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "amount REAL NOT NULL"
                    + ");");
            statement.execute("CREATE TABLE IF NOT EXISTS record_types ("
                    + "type_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "type_name TEXT UNIQUE NOT NULL"
                    + ");");
            statement.execute("CREATE TABLE IF NOT EXISTS pdf_settings ("
                    + "setting_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "setting_key TEXT UNIQUE NOT NULL,"
                    + "setting_value TEXT NOT NULL"
                    + ");");
            addColumnIfMissing(connection, "customers", "city", "TEXT");
            addColumnIfMissing(connection, "customers", "state", "TEXT");
            addColumnIfMissing(connection, "customers", "zip", "TEXT");
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

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    public String getNextCustomerId() {
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
        String sql = "INSERT INTO customers (customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to add customer", e);
        }
    }

    public void updateCustomer(Customer customer) {
        String sql = "UPDATE customers SET first_name = ?, last_name = ?, address = ?, city = ?, state = ?, zip = ?, phone = ?, email = ?, service_day = ?, amount_charged = ?, notes = ? WHERE customer_id = ?";
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
            statement.setString(12, customer.getId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to update customer", e);
        }
    }

    public List<Customer> getAllCustomers() {
        String sql = "SELECT * FROM customers ORDER BY service_day, last_name, first_name";
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
        String sql = "SELECT * FROM customers WHERE lower(first_name) LIKE ? OR lower(last_name) LIKE ? OR lower(address) LIKE ? OR lower(city) LIKE ? OR lower(state) LIKE ? OR lower(zip) LIKE ? OR lower(email) LIKE ? ORDER BY service_day, last_name, first_name";
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

    public List<StatementRecord> getStatementRecords(String customerId) {
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
        String sql = "DELETE FROM statement_records WHERE record_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recordId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to delete statement record", e);
        }
    }

    public BigDecimal getStatementBalance(String customerId) {
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

    public Map<String, BigDecimal> getRevenueByDay() {
        String sql = "SELECT service_day, SUM(amount_charged) AS total FROM customers GROUP BY service_day";
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
        String sql = "SELECT SUM(amount_charged) AS total FROM customers";
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
        return new Customer(id, firstName, lastName, address, city, state, zip, phone, email, serviceDay, amount, notes);
    }

    public List<String> getRecordTypes() {
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
        String sql = "INSERT INTO record_types (type_name) VALUES (?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, typeName);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to add record type", e);
        }
    }

    public void updateRecordType(String oldName, String newName) {
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

    // PDF Settings Methods
    public void savePdfSetting(String key, String value) {
        String sql = "INSERT OR REPLACE INTO pdf_settings (setting_key, setting_value) VALUES (?, ?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to save PDF setting", e);
        }
    }

    public String getPdfSetting(String key, String defaultValue) {
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
