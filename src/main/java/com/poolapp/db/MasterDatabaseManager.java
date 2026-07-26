package com.poolapp.db;

import com.poolapp.model.CompanyProfile;

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

    public MasterDatabaseManager() {
        this(getDefaultMasterDatabasePath());
    }

    public MasterDatabaseManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.dbUrl = "jdbc:sqlite:" + this.databasePath;
        createDatabaseBackup();
        initializeDatabase();
        ensureDefaultMasterUser();
        ensureDefaultCompany();
    }

    public Path getDatabasePath() {
        return databasePath;
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
                    + "address TEXT,"
                    + "username TEXT UNIQUE NOT NULL,"
                    + "password TEXT NOT NULL,"
                    + "database_file TEXT NOT NULL"
                    + ");");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize master database", e);
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
        String sql = "SELECT company_id, company_name, phone, address, username, password, database_file FROM companies WHERE username = ? AND password = ?";
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

    public List<CompanyProfile> getAllCompanies() {
        String sql = "SELECT company_id, company_name, phone, address, username, password, database_file FROM companies ORDER BY company_name";
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

    public CompanyProfile getCompanyById(long companyId) {
        String sql = "SELECT company_id, company_name, phone, address, username, password, database_file FROM companies WHERE company_id = ?";
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
        if (company.getDatabasePath() == null) {
            company.setDatabasePath(createCompanyDatabasePath(company));
        }
        ensureCompanyDatabase(company.getDatabasePath());

        if (company.getId() == null) {
            String sql = "INSERT INTO companies (company_name, phone, address, username, password, database_file) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, company.getCompanyName());
                statement.setString(2, company.getPhone());
                statement.setString(3, company.getAddress());
                statement.setString(4, company.getUsername());
                statement.setString(5, company.getPassword());
                statement.setString(6, company.getDatabasePath().toString());
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
            String sql = "UPDATE companies SET company_name = ?, phone = ?, address = ?, username = ?, password = ?, database_file = ? WHERE company_id = ?";
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, company.getCompanyName());
                statement.setString(2, company.getPhone());
                statement.setString(3, company.getAddress());
                statement.setString(4, company.getUsername());
                statement.setString(5, company.getPassword());
                statement.setString(6, company.getDatabasePath().toString());
                statement.setLong(7, company.getId());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Unable to update company", e);
            }
        }
    }

    public void deleteCompany(long companyId) {
        CompanyProfile company = getCompanyById(companyId);
        if (company == null) {
            return;
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
