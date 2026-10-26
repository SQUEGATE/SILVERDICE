package com.poolapp.db;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class TursoDataMigrator {
    private static final int PASSWORD_HASH_ITERATIONS = 100_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TursoDataMigrator() {
    }

    public static void main(String[] args) throws Exception {
        Path projectRoot = args.length == 0
                ? Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : Paths.get(args[0]).toAbsolutePath().normalize();
        Path masterDatabase = projectRoot.resolve("master.db");
        Path schemaFile = locateSchema(projectRoot);
        String databaseUrl = requiredEnvironment("TURSO_DATABASE_URL");
        String authToken = requiredEnvironment("TURSO_AUTH_TOKEN");

        if (!Files.isRegularFile(masterDatabase)) {
            throw new IllegalStateException("Master database not found: " + masterDatabase);
        }
        String[] tokenParts = authToken.split("\\.", -1);
        System.out.println("Turso token received: " + authToken.length() + " characters; JWT parts: " + tokenParts.length + " (value hidden)");
        if (tokenParts.length != 3 || tokenParts[0].isBlank() || tokenParts[1].isBlank() || tokenParts[2].isBlank()) {
            throw new IllegalArgumentException("The token is not a complete JWT. Create a database token with `turso db tokens create silverdice-database` and paste only the token value.");
        }
        TursoHttpClient turso = new TursoHttpClient(databaseUrl, authToken);
        System.out.println("Checking Turso authentication before making changes...");
        turso.execute("SELECT 1 AS ready");
        System.out.println("Turso authentication succeeded; applying schema and importing local data...");
        executeSchema(turso, schemaFile);
        migrateMaster(turso, masterDatabase);
        System.out.println("Migration completed. Local SQLite files were not modified.");
    }

    private static Path locateSchema(Path root) {
        Path direct = root.resolve("cloudflare/worker/schema.sql");
        if (Files.isRegularFile(direct)) return direct;
        Path nested = root.resolve("DATABASE/cloudflare/worker/schema.sql");
        if (Files.isRegularFile(nested)) return nested;
        throw new IllegalStateException("Cannot find cloudflare/worker/schema.sql from " + root);
    }

    private static void executeSchema(TursoHttpClient turso, Path schemaFile) throws IOException, InterruptedException {
        String schema = Files.readString(schemaFile);
        for (String sql : schema.split(";")) {
            String statement = sql.trim();
            if (!statement.isEmpty()) turso.execute(statement);
        }
    }

    private static void migrateMaster(TursoHttpClient turso, Path masterDatabase) throws SQLException, IOException, InterruptedException, GeneralSecurityException {
        try (Connection master = DriverManager.getConnection("jdbc:sqlite:" + masterDatabase)) {
            migrateMasterUsers(turso, master);
            List<CompanySource> companies = loadCompanies(master);
            for (CompanySource company : companies) {
                migrateCompany(turso, master, company);
                Path companyDb = locateCompanyDatabase(company.databaseFile, masterDatabase.getParent());
                if (companyDb == null) {
                    System.out.println("Skipped missing company database for company ID " + company.id + ": " + company.databaseFile);
                    continue;
                }
                migrateCompanyData(turso, company.id, companyDb);
            }
        }
    }

    private static void migrateMasterUsers(TursoHttpClient turso, Connection master) throws SQLException, IOException, InterruptedException, GeneralSecurityException {
        try (PreparedStatement query = master.prepareStatement("SELECT username, password FROM master_users WHERE role = 'MASTER'");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String username = rows.getString("username");
                turso.execute("INSERT INTO accounts (username, password_hash, role) VALUES (?, ?, 'MASTER') "
                                + "ON CONFLICT(username) DO UPDATE SET password_hash = excluded.password_hash, role = 'MASTER'",
                        username, hashPassword(rows.getString("password")));
            }
        }
    }

    private static List<CompanySource> loadCompanies(Connection master) throws SQLException {
        List<CompanySource> companies = new ArrayList<>();
        String sql = "SELECT company_id, company_name, phone, email, address, username, password, database_file FROM companies ORDER BY company_id";
        try (PreparedStatement query = master.prepareStatement(sql); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                companies.add(new CompanySource(rows.getLong("company_id"), rows.getString("company_name"),
                        rows.getString("phone"), rows.getString("email"), rows.getString("address"),
                        rows.getString("username"), rows.getString("password"), rows.getString("database_file")));
            }
        }
        return companies;
    }

    private static void migrateCompany(TursoHttpClient turso, Connection master, CompanySource company) throws IOException, InterruptedException, GeneralSecurityException, SQLException {
        turso.execute("INSERT INTO companies (company_id, company_name, phone, email, address, username) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(company_id) DO UPDATE SET company_name = excluded.company_name, phone = excluded.phone, email = excluded.email, address = excluded.address, username = excluded.username",
                company.id, safe(company.name), safe(company.phone), safe(company.email), safe(company.address), company.username);
        turso.execute("INSERT INTO accounts (username, password_hash, role, company_id, phone, email) VALUES (?, ?, 'COMPANY', ?, ?, ?) "
                        + "ON CONFLICT(username) DO UPDATE SET password_hash = excluded.password_hash, role = 'COMPANY', company_id = excluded.company_id, phone = excluded.phone, email = excluded.email",
                company.username, hashPassword(company.password), company.id, safe(company.phone), safe(company.email));

        boolean hasVisitsPermission;
        try (ResultSet columns = master.getMetaData().getColumns(null, null, "employees", "can_access_visits")) {
            hasVisitsPermission = columns.next();
        }
        boolean hasSalesQuotePermission;
        try (ResultSet columns = master.getMetaData().getColumns(null, null, "employees", "can_access_sales_quote")) {
            hasSalesQuotePermission = columns.next();
        }
        boolean hasReaderPermission;
        try (ResultSet columns = master.getMetaData().getColumns(null, null, "employees", "can_access_statement_reader")) {
            hasReaderPermission = columns.next();
        }
        try (PreparedStatement query = master.prepareStatement("SELECT employee_id, first_name, last_name, phone, email, username, password, can_view_customer_details, can_view_customers, can_view_statements, can_view_revenue_summary, can_view_pdf, can_edit_pdf, can_edit_customers, can_edit_statements, allowed_customer_ids, allowed_days"
                + (hasVisitsPermission ? ", can_access_visits" : ", 0 AS can_access_visits")
                + (hasSalesQuotePermission ? ", can_access_sales_quote" : ", 0 AS can_access_sales_quote")
                + (hasReaderPermission ? ", can_access_statement_reader" : ", 0 AS can_access_statement_reader")
                + " FROM employees WHERE company_id = ?")) {
            query.setLong(1, company.id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    JSONObject permissions = new JSONObject()
                            .put("canViewCustomerDetails", rows.getInt("can_view_customer_details") == 1)
                            .put("canViewCustomers", rows.getInt("can_view_customers") == 1)
                            .put("canViewStatements", rows.getInt("can_view_statements") == 1)
                            .put("canAccessVisits", rows.getInt("can_access_visits") == 1)
                            .put("canAccessSalesQuote", rows.getInt("can_access_sales_quote") == 1)
                            .put("canAccessStatementReader", rows.getInt("can_access_statement_reader") == 1)
                            .put("canViewRevenueSummary", rows.getInt("can_view_revenue_summary") == 1)
                            .put("canViewPdf", rows.getInt("can_view_pdf") == 1)
                            .put("canEditPdf", rows.getInt("can_edit_pdf") == 1)
                            .put("canEditCustomers", rows.getInt("can_edit_customers") == 1)
                            .put("canEditStatements", rows.getInt("can_edit_statements") == 1);
                    turso.execute("INSERT INTO accounts (username, password_hash, role, company_id, employee_id, first_name, last_name, phone, email, permissions_json, allowed_customer_ids_json, allowed_days_json) "
                                    + "VALUES (?, ?, 'EMPLOYEE', ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT(username) DO UPDATE SET password_hash = excluded.password_hash, role = 'EMPLOYEE', company_id = excluded.company_id, employee_id = excluded.employee_id, first_name = excluded.first_name, last_name = excluded.last_name, phone = excluded.phone, email = excluded.email, permissions_json = excluded.permissions_json, allowed_customer_ids_json = excluded.allowed_customer_ids_json, allowed_days_json = excluded.allowed_days_json",
                            rows.getString("username"), hashPassword(rows.getString("password")), company.id,
                            rows.getLong("employee_id"), safe(rows.getString("first_name")), safe(rows.getString("last_name")),
                            safe(rows.getString("phone")), safe(rows.getString("email")), permissions.toString(),
                            csvToJson(rows.getString("allowed_customer_ids")), csvToJson(rows.getString("allowed_days")));
                }
            }
        }
    }

    private static void migrateCompanyData(TursoHttpClient turso, long companyId, Path databasePath) throws SQLException, IOException, InterruptedException {
        try (Connection company = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            try (PreparedStatement query = company.prepareStatement("SELECT customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes FROM customers");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    turso.execute("INSERT INTO customers (company_id, customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT(company_id, customer_id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name, address = excluded.address, city = excluded.city, state = excluded.state, zip = excluded.zip, phone = excluded.phone, email = excluded.email, service_day = excluded.service_day, amount_charged = excluded.amount_charged, notes = excluded.notes",
                            companyId, rows.getString("customer_id"), safe(rows.getString("first_name")), safe(rows.getString("last_name")),
                            safe(rows.getString("address")), safe(rows.getString("city")), safe(rows.getString("state")), safe(rows.getString("zip")),
                            safe(rows.getString("phone")), safe(rows.getString("email")), safe(rows.getString("service_day")),
                            rows.getDouble("amount_charged"), safe(rows.getString("notes")));
                }
            }
            try (PreparedStatement query = company.prepareStatement("SELECT record_id, customer_id, record_date, type, amount FROM statement_records");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    turso.execute("INSERT INTO statement_records (company_id, record_id, customer_id, record_date, type, amount) VALUES (?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT(company_id, record_id) DO UPDATE SET customer_id = excluded.customer_id, record_date = excluded.record_date, type = excluded.type, amount = excluded.amount",
                            companyId, rows.getLong("record_id"), rows.getString("customer_id"), rows.getString("record_date"), rows.getString("type"), rows.getDouble("amount"));
                }
            }
            boolean hasVisits;
            try (ResultSet tables = company.getMetaData().getTables(null, null, "customer_visits", null)) {
                hasVisits = tables.next();
            }
            if (hasVisits) {
                boolean hasPhoto;
                try (ResultSet columns = company.getMetaData().getColumns(null, null, "customer_visits", "photo_data")) {
                    hasPhoto = columns.next();
                }
                try (PreparedStatement query = company.prepareStatement("SELECT * FROM customer_visits");
                     ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        turso.execute("INSERT INTO customer_visits (company_id, visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes, photo_data)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                        + " ON CONFLICT(company_id, visit_id) DO UPDATE SET customer_id = excluded.customer_id,"
                                        + " visited_at = excluded.visited_at, ph = excluded.ph, chlo = excluded.chlo, stb = excluded.stb,"
                                        + " alk = excluded.alk, cal = excluded.cal, notes = excluded.notes, photo_data = excluded.photo_data",
                                companyId, rows.getLong("visit_id"), rows.getString("customer_id"), rows.getString("visited_at"),
                                rows.getString("ph"), rows.getString("chlo"), rows.getString("stb"),
                                rows.getObject("alk"), rows.getObject("cal"), rows.getString("notes"),
                                hasPhoto ? rows.getString("photo_data") : "");
                    }
                }
            }
            try (PreparedStatement query = company.prepareStatement("SELECT type_name FROM record_types"); ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    turso.execute("INSERT INTO record_types (company_id, type_name) VALUES (?, ?) ON CONFLICT(company_id, type_name) DO NOTHING", companyId, rows.getString("type_name"));
                }
            }
            try (PreparedStatement query = company.prepareStatement("SELECT setting_key, setting_value FROM pdf_settings"); ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    turso.execute("INSERT INTO pdf_settings (company_id, setting_key, setting_value) VALUES (?, ?, ?) ON CONFLICT(company_id, setting_key) DO UPDATE SET setting_value = excluded.setting_value",
                            companyId, rows.getString("setting_key"), rows.getString("setting_value"));
                }
            }
            migrateSalesQuotes(turso, companyId, company);
            migrateReaderImports(turso, companyId, company);
            migrateMonthlyRequests(turso, companyId, company);
        }
        System.out.println("Migrated company " + companyId + " from " + databasePath.getFileName());
    }

    private static void migrateSalesQuotes(TursoHttpClient turso, long companyId, Connection company)
            throws SQLException, IOException, InterruptedException {
        try (ResultSet tables = company.getMetaData().getTables(null, null, "sales_quotes", null)) {
            if (!tables.next()) return;
        }

        long nextId = 1;
        try (PreparedStatement query = company.prepareStatement("SELECT * FROM sales_quotes");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                long quoteId = rows.getLong("quote_id");
                nextId = Math.max(nextId, Math.addExact(quoteId, 1));
                turso.execute("INSERT INTO sales_quotes (company_id, quote_id, quote_number, customer_id, quote_date,"
                                + " notes, terms, lines_json, converted_at, created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT(company_id, quote_id) DO UPDATE SET quote_number = excluded.quote_number,"
                                + " customer_id = excluded.customer_id, quote_date = excluded.quote_date, notes = excluded.notes,"
                                + " terms = excluded.terms, lines_json = excluded.lines_json, converted_at = excluded.converted_at,"
                                + " created_at = excluded.created_at, updated_at = excluded.updated_at",
                        companyId, quoteId, rows.getString("quote_number"), rows.getString("customer_id"),
                        rows.getString("quote_date"), rows.getString("notes"), rows.getString("terms"),
                        rows.getString("lines_json"), rows.getString("converted_at"),
                        rows.getString("created_at"), rows.getString("updated_at"));
            }
        }
        boolean hasSequence;
        try (ResultSet tables = company.getMetaData().getTables(null, null, "sales_quote_sequences", null)) {
            hasSequence = tables.next();
        }
        if (hasSequence) {
            try (PreparedStatement query = company.prepareStatement("SELECT MAX(next_id) FROM sales_quote_sequences");
                 ResultSet rows = query.executeQuery()) {
                if (rows.next()) nextId = Math.max(nextId, rows.getLong(1));
            }
        }
        turso.execute("INSERT INTO sales_quote_sequences(company_id, next_id) VALUES (?, ?)"
                        + " ON CONFLICT(company_id) DO UPDATE SET next_id = MAX(next_id, excluded.next_id)",
                companyId, nextId);
    }

    private static void migrateReaderImports(TursoHttpClient turso, long companyId, Connection company)
            throws SQLException, IOException, InterruptedException {
        try (ResultSet tables = company.getMetaData().getTables(null, null, "reader_imports", null)) {
            if (!tables.next()) return;
        }
        try (PreparedStatement query = company.prepareStatement("SELECT * FROM reader_imports");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                turso.execute("INSERT INTO reader_imports(company_id, document_hash, source_id, customer_id,"
                                + " record_id, record_date, amount, reference, source_text, imported_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT(company_id, document_hash, source_id) DO NOTHING",
                        companyId, rows.getString("document_hash"), rows.getString("source_id"),
                        rows.getString("customer_id"), rows.getLong("record_id"), rows.getString("record_date"),
                        rows.getDouble("amount"), rows.getString("reference"),
                        rows.getString("source_text"), rows.getString("imported_at"));
            }
        }
    }

    private static void migrateMonthlyRequests(TursoHttpClient turso, long companyId, Connection company)
            throws SQLException, IOException, InterruptedException {
        try (ResultSet tables = company.getMetaData().getTables(null, null, "monthly_statement_batches", null)) {
            if (!tables.next()) return;
        }
        try (PreparedStatement query = company.prepareStatement("SELECT request_id, record_date, type, added FROM monthly_statement_batches");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                List<java.util.Map<String, String>> existing = turso.execute("SELECT record_date, type, added FROM monthly_statement_requests WHERE company_id = ? AND request_id = ?",
                        companyId, rows.getString("request_id"));
                if (!existing.isEmpty()) {
                    java.util.Map<String, String> saved = existing.get(0);
                    if (!rows.getString("record_date").equals(saved.get("record_date"))
                            || !rows.getString("type").equals(saved.get("type"))
                            || rows.getInt("added") != Integer.parseInt(saved.get("added"))) {
                        throw new IllegalStateException("Conflicting monthly billing request identity during migration.");
                    }
                    continue;
                }
                turso.execute("INSERT INTO monthly_statement_requests(company_id, request_id, record_date, type, added, claim)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        companyId, rows.getString("request_id"), rows.getString("record_date"), rows.getString("type"),
                        rows.getInt("added"), java.util.UUID.randomUUID().toString());
            }
        }
    }

    private static Path locateCompanyDatabase(String databaseFile, Path masterDirectory) {
        if (databaseFile != null && !databaseFile.isBlank()) {
            Path configured = Paths.get(databaseFile);
            if (!configured.isAbsolute()) configured = masterDirectory.resolve(configured).normalize();
            if (Files.isRegularFile(configured)) return configured;
        }
        return null;
    }

    private static String hashPassword(String password) throws GeneralSecurityException {
        if (password == null) throw new IllegalStateException("A source account has a null password");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PASSWORD_HASH_ITERATIONS, 256);
        byte[] hash;
        try {
            hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "pbkdf2-sha256$" + PASSWORD_HASH_ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    private static String csvToJson(String csv) {
        JSONArray values = new JSONArray();
        if (csv != null && !csv.isBlank()) {
            for (String item : csv.split(",")) {
                String value = item.trim();
                if (!value.isBlank()) values.put(value);
            }
        }
        return values.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key + " in the current process environment first");
        return value.trim();
    }

    private static final class CompanySource {
        private final long id;
        private final String name;
        private final String phone;
        private final String email;
        private final String address;
        private final String username;
        private final String password;
        private final String databaseFile;

        private CompanySource(long id, String name, String phone, String email, String address,
                              String username, String password, String databaseFile) {
            this.id = id;
            this.name = name;
            this.phone = phone;
            this.email = email;
            this.address = address;
            this.username = username;
            this.password = password;
            this.databaseFile = databaseFile;
        }
    }
}
