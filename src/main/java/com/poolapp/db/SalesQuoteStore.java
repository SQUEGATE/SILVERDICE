package com.poolapp.db;

import com.poolapp.model.SalesQuote;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cloud operations use the authenticated API; local writes use independent atomic SQLite transactions. */
public final class SalesQuoteStore {
    private final DatabaseManager database;

    public SalesQuoteStore(DatabaseManager database) { this.database = database; }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + database.getDatabasePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("CREATE TABLE IF NOT EXISTS sales_quotes (quote_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "quote_number TEXT NOT NULL UNIQUE, customer_id TEXT NOT NULL, quote_date TEXT NOT NULL,"
                    + "notes TEXT NOT NULL DEFAULT '', terms TEXT NOT NULL DEFAULT '', lines_json TEXT NOT NULL,"
                    + "converted_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sales_quote_sequences (id INTEGER PRIMARY KEY CHECK(id=1), next_id INTEGER NOT NULL)");
            s.execute("INSERT OR IGNORE INTO sales_quote_sequences VALUES (1,1)");
            s.execute("UPDATE sales_quote_sequences SET next_id=MAX(next_id,"
                    + "COALESCE((SELECT MAX(CAST(SUBSTR(quote_number,4) AS INTEGER))+1 FROM sales_quotes),1)) "
                    + "WHERE id=1");
        } catch (SQLException e) { c.close(); throw e; }
        return c;
    }

    private static String path(String customerId) {
        return "/v1/customers/" + java.net.URLEncoder.encode(customerId, java.nio.charset.StandardCharsets.UTF_8) + "/quotes";
    }

    public List<SalesQuote> list(String customerId) {
        if (database.getApiClient() != null) {
            JSONArray rows = CompManagerApiClient.array(database.getApiClient().get(path(customerId)));
            List<SalesQuote> result = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) result.add(SalesQuote.fromJson(rows.getJSONObject(i)));
            return result;
        }
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT * FROM sales_quotes WHERE customer_id=? ORDER BY quote_id DESC")) {
            s.setString(1, customerId);
            List<SalesQuote> result = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) result.add(read(rs)); }
            return result;
        } catch (SQLException e) { throw failure(e); }
    }

    public SalesQuote save(SalesQuote quote) {
        if (quote.converted()) throw new IllegalStateException("Converted quotations are read-only.");
        if (database.getApiClient() != null) {
            Object result = quote.quoteId() == 0
                    ? database.getApiClient().post(path(quote.customerId()), quote.body())
                    : database.getApiClient().put(path(quote.customerId()) + "/" + quote.quoteId(), quote.body());
            return SalesQuote.fromJson(CompManagerApiClient.object(result));
        }
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                // Acquire the write lock before reading the sequence or conversion status.
                try (Statement lock = c.createStatement()) { lock.executeUpdate(
                        "UPDATE sales_quote_sequences SET next_id=next_id WHERE id=1"); }
                long id = quote.quoteId();
                String now = Instant.now().toString();
                if (id == 0) {
                    try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM customers WHERE customer_id=?")) {
                        s.setString(1, quote.customerId());
                        try (ResultSet rs = s.executeQuery()) {
                            if (!rs.next()) throw new IllegalArgumentException("Customer no longer exists.");
                        }
                    }
                    long number;
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                            "SELECT next_id FROM sales_quote_sequences WHERE id=1")) {
                        rs.next(); number = rs.getLong(1);
                    }
                    try (Statement s = c.createStatement()) { s.executeUpdate(
                            "UPDATE sales_quote_sequences SET next_id=next_id+1 WHERE id=1"); }
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO sales_quotes "
                            + "(quote_number,customer_id,quote_date,notes,terms,lines_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)")) {
                        s.setString(1, String.format(Locale.ROOT, "SQ-%06d", number));
                        s.setString(2, quote.customerId()); s.setString(3, quote.quoteDate());
                        s.setString(4, quote.notes()); s.setString(5, quote.terms());
                        s.setString(6, quote.body().getJSONArray("lines").toString());
                        s.setString(7, now); s.setString(8, now); s.executeUpdate();
                    }
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                        rs.next(); id = rs.getLong(1);
                    }
                } else {
                    requireEditable(load(c, quote.customerId(), id));
                    try (PreparedStatement s = c.prepareStatement("UPDATE sales_quotes SET quote_date=?,notes=?,terms=?,lines_json=?,updated_at=? "
                            + "WHERE quote_id=? AND customer_id=?")) {
                        s.setString(1, quote.quoteDate()); s.setString(2, quote.notes()); s.setString(3, quote.terms());
                        s.setString(4, quote.body().getJSONArray("lines").toString()); s.setString(5, now);
                        s.setLong(6, id); s.setString(7, quote.customerId()); s.executeUpdate();
                    }
                }
                SalesQuote saved = load(c, quote.customerId(), id);
                c.commit(); return saved;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw failure(e); }
    }

    public void delete(String customerId, long id) {
        if (database.getApiClient() != null) {
            database.getApiClient().delete(path(customerId) + "/" + id); return;
        }
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "DELETE FROM sales_quotes WHERE customer_id=? AND quote_id=? AND converted_at IS NULL")) {
            s.setString(1, customerId); s.setLong(2, id);
            if (s.executeUpdate() != 1) throw new IllegalStateException("Quotation was deleted or converted; refresh the list.");
        } catch (SQLException e) { throw failure(e); }
    }

    public SalesQuote convert(String customerId, long id) {
        if (database.getApiClient() != null) return SalesQuote.fromJson(CompManagerApiClient.object(
                database.getApiClient().post(path(customerId) + "/" + id + "/convert", new JSONObject())));
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                try (PreparedStatement s = c.prepareStatement("UPDATE sales_quotes SET converted_at=?,updated_at=? "
                        + "WHERE customer_id=? AND quote_id=? AND converted_at IS NULL")) {
                    s.setString(1, now); s.setString(2, now); s.setString(3, customerId); s.setLong(4, id);
                    if (s.executeUpdate() != 1) {
                        SalesQuote existing = load(c, customerId, id);
                        c.commit(); return existing;
                    }
                }
                SalesQuote quote = load(c, customerId, id);
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO statement_records (customer_id,record_date,type,amount) VALUES (?,?,?,?)")) {
                    for (SalesQuote.Line line : quote.lines()) {
                        s.setString(1, customerId); s.setString(2, line.date()); s.setString(3, line.statementType());
                        s.setBigDecimal(4, line.amount()); s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit(); return quote;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw failure(e); }
    }

    private static void requireEditable(SalesQuote quote) {
        if (quote.converted()) throw new IllegalStateException("Converted quotations are read-only.");
    }

    private static SalesQuote load(Connection c, String customerId, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM sales_quotes WHERE customer_id=? AND quote_id=?")) {
            s.setString(1, customerId); s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Quotation no longer exists.");
                return read(rs);
            }
        }
    }

    private static SalesQuote read(ResultSet rs) throws SQLException {
        JSONObject json = new JSONObject();
        for (String key : List.of("quote_id", "quote_number", "customer_id", "quote_date", "notes", "terms",
                "created_at", "updated_at")) json.put(key, rs.getObject(key));
        json.put("converted_at", rs.getString("converted_at") == null ? JSONObject.NULL : rs.getString("converted_at"));
        json.put("lines", new JSONArray(rs.getString("lines_json")));
        return SalesQuote.fromJson(json);
    }

    private static IllegalStateException failure(SQLException e) {
        return new IllegalStateException("Unable to access quotations: " + e.getMessage(), e);
    }
}
