package com.poolapp.db;

import com.poolapp.model.CompanyProfile;
import com.poolapp.model.EmployeeProfile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
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
import java.util.List;

public class MasterDatabaseManager {
    private final Path databasePath;
    private final String dbUrl;
    private final CompManagerApiClient apiClient;
    private String sessionToken;

    public MasterDatabaseManager() {
        String apiBaseUrl = AppApiConfiguration.getBaseUrl();
        if (!apiBaseUrl.isBlank()) {
            this.databasePath = null;
            this.dbUrl = null;
            this.apiClient = new CompManagerApiClient(apiBaseUrl);
            return;
        }
        this.databasePath = getDefaultMasterDatabasePath().toAbsolutePath().normalize();
        this.dbUrl = "jdbc:sqlite:" + this.databasePath;
        this.apiClient = null;
        createDatabaseBackup();
        initializeDatabase();
        ensureDefaultMasterUser();
        ensureDefaultCompany();
    }

    public MasterDatabaseManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.dbUrl = "jdbc:sqlite:" + this.databasePath;
        this.apiClient = null;
        createDatabaseBackup();
        initializeDatabase();
        ensureDefaultMasterUser();
        ensureDefaultCompany();
    }

    public MasterDatabaseManager(String apiBaseUrl, String apiSessionToken) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank() || apiSessionToken == null || apiSessionToken.isBlank()) {
            throw new IllegalArgumentException("Remote API URL and session token are required");
        }
        this.databasePath = null;
        this.dbUrl = null;
        this.apiClient = new CompManagerApiClient(apiBaseUrl, apiSessionToken);
        this.sessionToken = apiSessionToken;
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public String getApiBaseUrl() {
        return apiClient == null ? null : AppApiConfiguration.getBaseUrl();
    }

    public DatabaseManager createDatabaseManager(long companyId) {
        if (apiClient == null) throw new IllegalStateException("Remote API mode is not enabled");
        JSONObject result = CompManagerApiClient.object(apiRequest("POST", "/v1/admin/companies/" + companyId + "/session", new JSONObject()));
        return new DatabaseManager(AppApiConfiguration.getBaseUrl(), result.getString("token"), companyId);
    }

    public DatabaseManager createDatabaseManager(long companyId, String token) {
        if (apiClient == null) throw new IllegalStateException("Remote API mode is not enabled");
        return new DatabaseManager(AppApiConfiguration.getBaseUrl(), token, companyId);
    }

    public DatabaseManager createEmployeePreviewDatabaseManager(long employeeId, long companyId) {
        if (apiClient == null) throw new IllegalStateException("Remote API mode is not enabled");
        JSONObject result = CompManagerApiClient.object(apiRequest("POST", "/v1/companies/employees/" + employeeId + "/session", new JSONObject()));
        return new DatabaseManager(AppApiConfiguration.getBaseUrl(), result.getString("token"), companyId);
    }

    private JSONObject remoteLogin(String username, String password) {
        try {
            JSONObject response = CompManagerApiClient.object(apiClient.post("/v1/auth/login",
                    new JSONObject().put("username", username).put("password", password)));
            JSONObject account = new JSONObject(response.getJSONObject("account").toString());
            account.put("token", response.getString("token"));
            return account;
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("API 401:")) return null;
            throw e;
        }
    }

    private Object apiRequest(String method, String path, JSONObject body) {
        if (sessionToken == null || sessionToken.isBlank()) throw new IllegalStateException("Log in before using cloud data");
        CompManagerApiClient authenticated = new CompManagerApiClient(AppApiConfiguration.getBaseUrl(), sessionToken);
        switch (method) {
            case "GET": return authenticated.get(path);
            case "POST": return authenticated.post(path, body == null ? new JSONObject() : body);
            case "PUT": return authenticated.put(path, body == null ? new JSONObject() : body);
            case "DELETE": return authenticated.delete(path);
            default: throw new IllegalArgumentException("Unsupported API method: " + method);
        }
    }

    private List<CompanyProfile> remoteCompanies(String path) {
        JSONArray rows = CompManagerApiClient.array(apiRequest("GET", path, null));
        List<CompanyProfile> companies = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) companies.add(remoteCompany(rows.getJSONObject(i)));
        return companies;
    }

    private CompanyProfile remoteCompany(JSONObject row) {
        CompanyProfile company = new CompanyProfile();
        company.setId(row.getLong("company_id"));
        company.setCompanyName(row.optString("company_name"));
        company.setPhone(row.optString("phone"));
        company.setEmail(row.optString("email"));
        company.setAddress(row.optString("address"));
        company.setUsername(row.optString("username"));
        return company;
    }

    private EmployeeProfile remoteEmployee(JSONObject row) {
        EmployeeProfile employee = new EmployeeProfile();
        employee.setEmployeeId(row.getLong("employee_id"));
        employee.setCompanyId(row.getLong("company_id"));
        employee.setFirstName(row.optString("first_name"));
        employee.setLastName(row.optString("last_name"));
        employee.setPhone(row.optString("phone"));
        employee.setEmail(row.optString("email"));
        employee.setUsername(row.optString("username"));
        employee.setAllowedCustomerIds(jsonStringList(row.optJSONArray("allowed_customer_ids")));
        employee.setAllowedDays(jsonStringList(row.optJSONArray("allowed_days")));
        JSONObject permissions = row.optJSONObject("permissions");
        if (permissions != null) {
            employee.setCanViewCustomerDetails(permissions.optBoolean("canViewCustomerDetails"));
            employee.setCanViewCustomers(permissions.optBoolean("canViewCustomers"));
            employee.setCanViewStatements(permissions.optBoolean("canViewStatements"));
            employee.setCanViewRevenueSummary(permissions.optBoolean("canViewRevenueSummary"));
            employee.setCanViewPdf(permissions.optBoolean("canViewPdf"));
            employee.setCanEditPdf(permissions.optBoolean("canEditPdf"));
            employee.setCanEditCustomers(permissions.optBoolean("canEditCustomers"));
            employee.setCanEditStatements(permissions.optBoolean("canEditStatements"));
        }
        return employee;
    }

    private List<String> jsonStringList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) values.add(array.optString(i));
        return values;
    }

    private static Path getDefaultMasterDatabasePath() {
        return determineProjectRoot().resolve("master.db").toAbsolutePath();
    }

    private static Path determineProjectRoot() {
        try {
            Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            while (current != null) {
                if (Files.exists(current.resolve("build.gradle")) || Files.exists(current.resolve("settings.gradle"))) {
                    return current;
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {
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
                throw new RuntimeException("Unable to create master database backup", e);
            }
        }
    }

    private void initializeDatabase() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS master_users ("
                    + "username TEXT PRIMARY KEY,"
                    + "password TEXT NOT NULL,"
                    + "role TEXT NOT NULL"
                    + ");");
            statement.execute("CREATE TABLE IF NOT EXISTS companies ("
                    + "company_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "company_name TEXT NOT NULL,"
                    + "phone TEXT,"
                    + "email TEXT,"
                    + "address TEXT,"
                    + "username TEXT UNIQUE NOT NULL,"
                    + "password TEXT NOT NULL,"
                    + "database_file TEXT NOT NULL"
                    + ");");
            statement.execute("CREATE TABLE IF NOT EXISTS employees ("
                    + "employee_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "company_id INTEGER NOT NULL,"
                    + "first_name TEXT NOT NULL,"
                    + "last_name TEXT NOT NULL,"
                    + "phone TEXT,"
                    + "email TEXT,"
                    + "username TEXT UNIQUE NOT NULL,"
                    + "password TEXT NOT NULL,"
                    + "can_view_customer_details INTEGER NOT NULL DEFAULT 1,"
                    + "can_view_customers INTEGER NOT NULL DEFAULT 1,"
                    + "can_view_statements INTEGER NOT NULL DEFAULT 1,"
                    + "can_view_revenue_summary INTEGER NOT NULL DEFAULT 0,"
                    + "can_view_pdf INTEGER NOT NULL DEFAULT 0,"
                        + "can_edit_pdf INTEGER NOT NULL DEFAULT 0,"
                    + "can_edit_customers INTEGER NOT NULL DEFAULT 0,"
                    + "can_edit_statements INTEGER NOT NULL DEFAULT 0,"
                    + "allowed_customer_ids TEXT,"
                    + "allowed_days TEXT"
                    + ");");
            addColumnIfMissing(connection, "companies", "email", "TEXT");
                        addColumnIfMissing(connection, "employees", "phone", "TEXT");
                        addColumnIfMissing(connection, "employees", "can_edit_pdf", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "employees", "can_edit_customers", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "employees", "can_edit_statements", "INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize master database", e);
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

    private void ensureDefaultMasterUser() {
        if (authenticateMaster("squegate", "miguelina5")) {
            return;
        }
        String sql = "INSERT OR IGNORE INTO master_users (username, password, role) VALUES (?, ?, ?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "squegate");
            statement.setString(2, "miguelina5");
            statement.setString(3, "MASTER");
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to create default master user", e);
        }
    }

    private void ensureDefaultCompany() {
        if (!getAllCompanies().isEmpty()) {
            return;
        }
        Path defaultCompanyDb = determineProjectRoot().resolve("customer.db").toAbsolutePath();
        CompanyProfile profile = new CompanyProfile(null, "Default Company", "", "", "company", "company123", defaultCompanyDb);
        saveCompany(profile);
    }

    public boolean authenticateMaster(String username, String password) {
        if (apiClient != null) {
            JSONObject account = remoteLogin(username, password);
            if (account == null || !"MASTER".equals(account.optString("role"))) return false;
            sessionToken = account.optString("token", null);
            return sessionToken != null && !sessionToken.isBlank();
        }
        String sql = "SELECT 1 FROM master_users WHERE username = ? AND password = ? AND role = 'MASTER'";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to authenticate master user", e);
        }
    }

    public CompanyProfile authenticateCompany(String username, String password) {
        if (apiClient != null) {
            JSONObject account = remoteLogin(username, password);
            if (account == null || !"COMPANY".equals(account.optString("role"))) return null;
            sessionToken = account.optString("token", null);
            CompanyProfile company = new CompanyProfile();
            company.setId(account.optLong("companyId"));
            company.setCompanyName(account.optString("companyName"));
            company.setPhone(account.optString("phone"));
            company.setEmail(account.optString("email"));
            company.setUsername(account.optString("username"));
            company.setApiSessionToken(sessionToken);
            return company;
        }
        String sql = "SELECT company_id, company_name, phone, email, address, username, password, database_file FROM companies WHERE username = ? AND password = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readCompany(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to authenticate company user", e);
        }
        return null;
    }

    public EmployeeProfile authenticateEmployee(String username, String password) {
        if (apiClient != null) {
            JSONObject account = remoteLogin(username, password);
            if (account == null || !"EMPLOYEE".equals(account.optString("role"))) return null;
            sessionToken = account.optString("token", null);
            EmployeeProfile employee = new EmployeeProfile();
            employee.setEmployeeId(account.optLong("employeeId"));
            employee.setCompanyId(account.optLong("companyId"));
            employee.setCompanyName(account.optString("companyName"));
            employee.setFirstName(account.optString("firstName"));
            employee.setLastName(account.optString("lastName"));
            employee.setPhone(account.optString("phone"));
            employee.setEmail(account.optString("email"));
            employee.setUsername(account.optString("username"));
            employee.setApiSessionToken(sessionToken);
            JSONObject permissions = account.optJSONObject("permissions");
            if (permissions != null) {
                employee.setCanViewCustomerDetails(permissions.optBoolean("canViewCustomerDetails"));
                employee.setCanViewCustomers(permissions.optBoolean("canViewCustomers"));
                employee.setCanViewStatements(permissions.optBoolean("canViewStatements"));
                employee.setCanViewRevenueSummary(permissions.optBoolean("canViewRevenueSummary"));
                employee.setCanViewPdf(permissions.optBoolean("canViewPdf"));
                employee.setCanEditPdf(permissions.optBoolean("canEditPdf"));
                employee.setCanEditCustomers(permissions.optBoolean("canEditCustomers"));
                employee.setCanEditStatements(permissions.optBoolean("canEditStatements"));
            }
            employee.setAllowedCustomerIds(jsonStringList(account.optJSONArray("allowedCustomerIds")));
            employee.setAllowedDays(jsonStringList(account.optJSONArray("allowedDays")));
            return employee;
        }
        String sql = "SELECT e.employee_id, e.company_id, c.company_name, c.database_file, e.first_name, e.last_name, e.phone, e.email, e.username, e.password, "
            + "e.can_view_customer_details, e.can_view_customers, e.can_view_statements, e.can_view_revenue_summary, e.can_view_pdf, e.can_edit_pdf, e.can_edit_customers, e.can_edit_statements, "
                + "e.allowed_customer_ids, e.allowed_days "
                + "FROM employees e "
                + "JOIN companies c ON c.company_id = e.company_id "
                + "WHERE e.username = ? AND e.password = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readEmployee(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to authenticate employee user", e);
        }
        return null;
    }

    public List<CompanyProfile> getAllCompanies() {
        if (apiClient != null) return remoteCompanies("/v1/admin/companies");
        String sql = "SELECT company_id, company_name, phone, email, address, username, password, database_file FROM companies ORDER BY company_id";
        List<CompanyProfile> companies = new ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                companies.add(readCompany(resultSet));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load companies", e);
        }
        return companies;
    }

    public List<CompanyProfile> searchCompanies(String query) {
        if (apiClient != null) return remoteCompanies("/v1/admin/companies?q=" + java.net.URLEncoder.encode(query == null ? "" : query, java.nio.charset.StandardCharsets.UTF_8));
        String sql = "SELECT company_id, company_name, phone, email, address, username, password, database_file "
                + "FROM companies "
            + "WHERE lower(company_name) LIKE ? OR lower(username) LIKE ? OR lower(phone) LIKE ? OR lower(email) LIKE ? OR lower(address) LIKE ? OR CAST(company_id AS TEXT) LIKE ? "
                + "ORDER BY company_id";
        List<CompanyProfile> companies = new ArrayList<>();
        String pattern = "%" + (query == null ? "" : query.trim().toLowerCase()) + "%";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            statement.setString(3, pattern);
            statement.setString(4, pattern);
            statement.setString(5, pattern);
            statement.setString(6, pattern);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    companies.add(readCompany(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to search companies", e);
        }
        return companies;
    }

    public CompanyProfile getCompanyById(long companyId) {
        if (apiClient != null) return remoteCompany(CompManagerApiClient.object(apiRequest("GET", "/v1/admin/companies/" + companyId, null)));
        String sql = "SELECT company_id, company_name, phone, email, address, username, password, database_file FROM companies WHERE company_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, companyId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readCompany(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load company", e);
        }
        return null;
    }

    public void saveCompany(CompanyProfile company) {
        if (apiClient != null) {
            JSONObject body = new JSONObject()
                    .put("company_name", company.getCompanyName())
                    .put("phone", company.getPhone() == null ? "" : company.getPhone())
                    .put("email", company.getEmail() == null ? "" : company.getEmail())
                    .put("address", company.getAddress() == null ? "" : company.getAddress())
                    .put("username", company.getUsername());
            if (company.getId() != null) body.put("company_id", company.getId());
            if (company.getPassword() != null && !company.getPassword().isBlank()) body.put("password", company.getPassword());
            JSONObject result = CompManagerApiClient.object(apiRequest("POST", "/v1/admin/companies", body));
            if (company.getId() == null) company.setId(result.getLong("company_id"));
            return;
        }
        if (company.getDatabasePath() == null) {
            company.setDatabasePath(createCompanyDatabasePath(company));
        }
        ensureCompanyDatabase(company.getDatabasePath());

        if (company.getId() == null) {
            String sql = "INSERT INTO companies (company_name, phone, email, address, username, password, database_file) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, company.getCompanyName());
                statement.setString(2, company.getPhone());
                statement.setString(3, company.getEmail());
                statement.setString(4, company.getAddress());
                statement.setString(5, company.getUsername());
                statement.setString(6, company.getPassword());
                statement.setString(7, company.getDatabasePath().toString());
                statement.executeUpdate();
                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        company.setId(generatedKeys.getLong(1));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Unable to create company", e);
            }
        } else {
            String sql = "UPDATE companies SET company_name = ?, phone = ?, email = ?, address = ?, username = ?, password = ?, database_file = ? WHERE company_id = ?";
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, company.getCompanyName());
                statement.setString(2, company.getPhone());
                statement.setString(3, company.getEmail());
                statement.setString(4, company.getAddress());
                statement.setString(5, company.getUsername());
                statement.setString(6, company.getPassword());
                statement.setString(7, company.getDatabasePath().toString());
                statement.setLong(8, company.getId());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Unable to update company", e);
            }
        }
    }

    public void deleteCompany(long companyId) {
        if (apiClient != null) {
            apiRequest("DELETE", "/v1/admin/companies/" + companyId, null);
            return;
        }
        CompanyProfile company = getCompanyById(companyId);
        if (company == null) {
            return;
        }

        String deleteEmployeesSql = "DELETE FROM employees WHERE company_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(deleteEmployeesSql)) {
            statement.setLong(1, companyId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to delete company employees", e);
        }

        String sql = "DELETE FROM companies WHERE company_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, companyId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to delete company", e);
        }

        if (company.getDatabasePath() != null) {
            try {
                Files.deleteIfExists(company.getDatabasePath());
                Files.deleteIfExists(company.getDatabasePath().resolveSibling(company.getDatabasePath().getFileName().toString() + ".bak"));
            } catch (IOException ignored) {
            }
        }
    }

    public List<EmployeeProfile> getEmployeesForCompany(long companyId) {
        if (apiClient != null) {
            JSONArray rows = CompManagerApiClient.array(apiRequest("GET", "/v1/admin/companies/" + companyId + "/employees", null));
            List<EmployeeProfile> employees = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) employees.add(remoteEmployee(rows.getJSONObject(i)));
            return employees;
        }
        String sql = "SELECT e.employee_id, e.company_id, c.company_name, c.database_file, e.first_name, e.last_name, e.phone, e.email, e.username, e.password, "
            + "e.can_view_customer_details, e.can_view_customers, e.can_view_statements, e.can_view_revenue_summary, e.can_view_pdf, e.can_edit_pdf, e.can_edit_customers, e.can_edit_statements, "
                + "e.allowed_customer_ids, e.allowed_days "
                + "FROM employees e JOIN companies c ON c.company_id = e.company_id WHERE e.company_id = ? ORDER BY e.employee_id";
        List<EmployeeProfile> employees = new ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, companyId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    employees.add(readEmployee(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to load employees", e);
        }
        return employees;
    }

    public void saveEmployee(EmployeeProfile employee) {
        if (apiClient != null) {
            JSONObject permissions = new JSONObject()
                .put("canViewCustomerDetails", employee.isCanViewCustomerDetails())
                .put("canViewCustomers", employee.isCanViewCustomers())
                .put("canViewStatements", employee.isCanViewStatements())
                .put("canViewRevenueSummary", employee.isCanViewRevenueSummary())
                .put("canViewPdf", employee.isCanViewPdf())
                .put("canEditPdf", employee.isCanEditPdf())
                .put("canEditCustomers", employee.isCanEditCustomers())
                .put("canEditStatements", employee.isCanEditStatements());
            JSONObject body = new JSONObject()
                .put("username", employee.getUsername())
                .put("first_name", employee.getFirstName())
                .put("last_name", employee.getLastName())
                .put("phone", employee.getPhone() == null ? "" : employee.getPhone())
                .put("email", employee.getEmail() == null ? "" : employee.getEmail())
                .put("permissions", permissions)
                .put("allowed_customer_ids", new JSONArray(employee.getAllowedCustomerIds()))
                .put("allowed_days", new JSONArray(employee.getAllowedDays()));
            if (employee.getEmployeeId() != null) body.put("employee_id", employee.getEmployeeId());
            if (employee.getPassword() != null && !employee.getPassword().isBlank()) body.put("password", employee.getPassword());
            JSONObject result = CompManagerApiClient.object(apiRequest("POST", "/v1/admin/companies/" + employee.getCompanyId() + "/employees", body));
            if (employee.getEmployeeId() == null) employee.setEmployeeId(result.getLong("employee_id"));
            return;
        }
        if (employee.getEmployeeId() == null) {
            String sql = "INSERT INTO employees (company_id, first_name, last_name, phone, email, username, password, can_view_customer_details, can_view_customers, can_view_statements, can_view_revenue_summary, can_view_pdf, can_edit_pdf, can_edit_customers, can_edit_statements, allowed_customer_ids, allowed_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindEmployee(statement, employee, false);
                statement.executeUpdate();
                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        employee.setEmployeeId(generatedKeys.getLong(1));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Unable to create employee", e);
            }
        } else {
            String sql = "UPDATE employees SET first_name = ?, last_name = ?, phone = ?, email = ?, username = ?, password = ?, can_view_customer_details = ?, can_view_customers = ?, can_view_statements = ?, can_view_revenue_summary = ?, can_view_pdf = ?, can_edit_pdf = ?, can_edit_customers = ?, can_edit_statements = ?, allowed_customer_ids = ?, allowed_days = ? WHERE employee_id = ? AND company_id = ?";
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, employee.getFirstName());
                statement.setString(index++, employee.getLastName());
                statement.setString(index++, employee.getPhone());
                statement.setString(index++, employee.getEmail());
                statement.setString(index++, employee.getUsername());
                statement.setString(index++, employee.getPassword());
                statement.setInt(index++, employee.isCanViewCustomerDetails() ? 1 : 0);
                statement.setInt(index++, employee.isCanViewCustomers() ? 1 : 0);
                statement.setInt(index++, employee.isCanViewStatements() ? 1 : 0);
                statement.setInt(index++, employee.isCanViewRevenueSummary() ? 1 : 0);
                statement.setInt(index++, employee.isCanViewPdf() ? 1 : 0);
                statement.setInt(index++, employee.isCanEditPdf() ? 1 : 0);
                statement.setInt(index++, employee.isCanEditCustomers() ? 1 : 0);
                statement.setInt(index++, employee.isCanEditStatements() ? 1 : 0);
                statement.setString(index++, joinValues(employee.getAllowedCustomerIds()));
                statement.setString(index++, joinValues(employee.getAllowedDays()));
                statement.setLong(index++, employee.getEmployeeId());
                statement.setLong(index, employee.getCompanyId());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Unable to update employee", e);
            }
        }
    }

    public void deleteEmployee(long employeeId, long companyId) {
        if (apiClient != null) {
            apiRequest("DELETE", "/v1/admin/employees/" + employeeId, null);
            return;
        }
        String sql = "DELETE FROM employees WHERE employee_id = ? AND company_id = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employeeId);
            statement.setLong(2, companyId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to delete employee", e);
        }
    }

    private void bindEmployee(PreparedStatement statement, EmployeeProfile employee, boolean includeKeys) throws SQLException {
        int index = 1;
        statement.setLong(index++, employee.getCompanyId());
        statement.setString(index++, employee.getFirstName());
        statement.setString(index++, employee.getLastName());
        statement.setString(index++, employee.getPhone());
        statement.setString(index++, employee.getEmail());
        statement.setString(index++, employee.getUsername());
        statement.setString(index++, employee.getPassword());
        statement.setInt(index++, employee.isCanViewCustomerDetails() ? 1 : 0);
        statement.setInt(index++, employee.isCanViewCustomers() ? 1 : 0);
        statement.setInt(index++, employee.isCanViewStatements() ? 1 : 0);
        statement.setInt(index++, employee.isCanViewRevenueSummary() ? 1 : 0);
        statement.setInt(index++, employee.isCanViewPdf() ? 1 : 0);
        statement.setInt(index++, employee.isCanEditPdf() ? 1 : 0);
        statement.setInt(index++, employee.isCanEditCustomers() ? 1 : 0);
        statement.setInt(index++, employee.isCanEditStatements() ? 1 : 0);
        statement.setString(index++, joinValues(employee.getAllowedCustomerIds()));
        statement.setString(index, joinValues(employee.getAllowedDays()));
    }

    private EmployeeProfile readEmployee(ResultSet resultSet) throws SQLException {
        EmployeeProfile employee = new EmployeeProfile();
        employee.setEmployeeId(resultSet.getLong("employee_id"));
        employee.setCompanyId(resultSet.getLong("company_id"));
        employee.setCompanyName(resultSet.getString("company_name"));
        String databaseFile = resultSet.getString("database_file");
        if (databaseFile != null && !databaseFile.isBlank()) {
            employee.setCompanyDatabasePath(Paths.get(databaseFile));
        }
        employee.setFirstName(resultSet.getString("first_name"));
        employee.setLastName(resultSet.getString("last_name"));
            employee.setPhone(resultSet.getString("phone"));
        employee.setEmail(resultSet.getString("email"));
        employee.setUsername(resultSet.getString("username"));
        employee.setPassword(resultSet.getString("password"));
        employee.setCanViewCustomerDetails(resultSet.getInt("can_view_customer_details") == 1);
        employee.setCanViewCustomers(resultSet.getInt("can_view_customers") == 1);
        employee.setCanViewStatements(resultSet.getInt("can_view_statements") == 1);
        employee.setCanViewRevenueSummary(resultSet.getInt("can_view_revenue_summary") == 1);
        employee.setCanViewPdf(resultSet.getInt("can_view_pdf") == 1);
        employee.setCanEditPdf(resultSet.getInt("can_edit_pdf") == 1);
        employee.setCanEditCustomers(resultSet.getInt("can_edit_customers") == 1);
        employee.setCanEditStatements(resultSet.getInt("can_edit_statements") == 1);
        employee.setAllowedCustomerIds(splitValues(resultSet.getString("allowed_customer_ids")));
        employee.setAllowedDays(splitValues(resultSet.getString("allowed_days")));
        return employee;
    }

    private List<String> splitValues(String value) {
        List<String> items = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return items;
        }
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private String joinValues(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private Path createCompanyDatabasePath(CompanyProfile company) {
        Path companyDir = determineProjectRoot().resolve("company_databases");
        try {
            Files.createDirectories(companyDir);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create company database directory", e);
        }
        String safeName = sanitizeFileName(company.getUsername() != null && !company.getUsername().isBlank()
                ? company.getUsername()
                : company.getCompanyName());
        return companyDir.resolve(safeName + ".db").toAbsolutePath();
    }

    private void ensureCompanyDatabase(Path databasePath) {
        new DatabaseManager(databasePath);
    }

    private String sanitizeFileName(String value) {
        String result = value == null ? "company" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
        result = result.replaceAll("_+", "_");
        result = result.replaceAll("^_|_$", "");
        return result.isBlank() ? "company" : result;
    }

    private CompanyProfile readCompany(ResultSet resultSet) throws SQLException {
        CompanyProfile company = new CompanyProfile();
        company.setId(resultSet.getLong("company_id"));
        company.setCompanyName(resultSet.getString("company_name"));
        company.setPhone(resultSet.getString("phone"));
        company.setEmail(resultSet.getString("email"));
        company.setAddress(resultSet.getString("address"));
        company.setUsername(resultSet.getString("username"));
        company.setPassword(resultSet.getString("password"));
        String databaseFile = resultSet.getString("database_file");
        if (databaseFile != null && !databaseFile.isBlank()) {
            company.setDatabasePath(Paths.get(databaseFile));
        }
        return company;
    }
}
