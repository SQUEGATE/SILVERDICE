package com.poolapp.db;

import com.poolapp.model.Customer;
import com.poolapp.service.StatementPdfReader;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates preview and import for both local SQLite and the authenticated API.
 */
public final class StatementReaderStore {
    public static final String PAYMENT_TYPE = "Zelle Payment";
    private static final Pattern DATE_TEXT = Pattern.compile("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    private static final DateTimeFormatter OUTPUT_DATE = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.ROOT);

    public record PreviewRow(String sourceId, int page, int line, String text, String date,
                             BigDecimal amount, String customerId, List<String> candidateIds,
                             String status, String reason, String reference, boolean overrideDuplicate) {
        public PreviewRow(String sourceId, int page, int line, String text, String date, BigDecimal amount,
                          String customerId, List<String> candidateIds, String status, String reason, String reference) {
            this(sourceId, page, line, text, date, amount, customerId, candidateIds, status, reason, reference, false);
        }
        public PreviewRow {
            text = text == null ? "" : text;
            date = date == null ? "" : date;
            customerId = customerId == null ? "" : customerId;
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
            status = status == null ? "review" : status;
            reason = reason == null ? "" : reason;
            reference = reference == null ? "" : reference;
        }
    }

    public record ImportRow(String sourceId, String status, String reason, Long recordId) { }
    public record ImportResult(int imported, int skipped, List<ImportRow> rows) {
        public ImportResult { rows = List.copyOf(rows); }
    }

    private final DatabaseManager database;
    private final Clock clock;

    public StatementReaderStore(DatabaseManager database) {
        this(database, Clock.systemUTC());
    }

    public StatementReaderStore(DatabaseManager database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public List<PreviewRow> preview(String documentHash, List<StatementPdfReader.SourceRow> sourceRows,
                                    List<Customer> accessibleCustomers) {
        validateDocument(documentHash, sourceRows);
        if (database.isRemote()) return remotePreview(documentHash, sourceRows);
        return localPreview(documentHash, sourceRows, accessibleCustomers);
    }

    public ImportResult importRows(String documentHash, List<PreviewRow> selectedRows,
                                   List<Customer> accessibleCustomers) {
        validateDocument(documentHash, List.of());
        if (selectedRows == null || selectedRows.size() > StatementPdfReader.MAX_ROWS) {
            throw new IllegalArgumentException("Select no more than 1,000 statement rows.");
        }
        List<ImportRow> previouslyImported = new ArrayList<>();
        List<PreviewRow> actionable = new ArrayList<>();
        Set<String> sourceIds = new HashSet<>();
        Set<String> positions = new HashSet<>();
        for (PreviewRow row : selectedRows) {
            if (row == null || !sourceIds.add(row.sourceId()) || !positions.add(row.page() + ":" + row.line())) {
                throw new IllegalArgumentException("Import rows contain duplicate source IDs or positions.");
            }
            if ("imported".equals(row.status())) previouslyImported.add(new ImportRow(row.sourceId(), "skipped", "This PDF row was already imported.", null));
            else {
                validateImportRow(row);
                actionable.add(row);
            }
        }
        if (actionable.isEmpty()) return new ImportResult(0, previouslyImported.size(), previouslyImported);
        ImportResult result = database.isRemote() ? remoteImport(documentHash, actionable)
                : localImport(documentHash, actionable, accessibleCustomers);
        if (previouslyImported.isEmpty()) return result;
        List<ImportRow> combined = new ArrayList<>(result.rows());
        combined.addAll(previouslyImported);
        return new ImportResult(result.imported(), result.skipped() + previouslyImported.size(), combined);
    }

    public static String normalizeName(String value) {
        if (value == null) return "";
        String decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static String parseDate(String value, int currentYear) {
        if (value == null) return "";
        Matcher matcher = DATE_TEXT.matcher(value.trim());
        if (!matcher.matches()) return "";
        try {
            int month = Integer.parseInt(matcher.group(1));
            int day = Integer.parseInt(matcher.group(2));
            String yearText = matcher.group(3);
            int year = yearText == null ? currentYear : Integer.parseInt(yearText);
            if (yearText != null && yearText.length() == 2) year += year < 50 ? 2000 : 1900;
            LocalDate date = LocalDate.of(year, month, day);
            return date.format(OUTPUT_DATE);
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static BigDecimal parsePositiveAmount(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String cleaned = value.trim().replace("$", "").replace(",", "").replace("(", "-").replace(")", "");
            BigDecimal amount = new BigDecimal(cleaned).setScale(2, RoundingMode.UNNECESSARY);
            return amount.signum() > 0 && amount.compareTo(MAX_AMOUNT) <= 0 ? amount : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<PreviewRow> localPreview(String documentHash, List<StatementPdfReader.SourceRow> rows,
                                          List<Customer> customers) {
        List<Customer> accessible = customers == null ? List.of() : customers;
        List<CustomerName> names = accessible.stream().map(StatementReaderStore::prepareName).toList();
        int year = LocalDate.now(clock).getYear();
        List<PreviewRow> results = new ArrayList<>();
        Set<String> fingerprintsInDocument = new HashSet<>();
        try (Connection connection = localConnection()) {
            boolean hasAudit = auditTableExists(connection);
            Set<String> importedSources = new HashSet<>();
            Set<String> importedReferences = new HashSet<>();
            if (hasAudit) try (Statement query = connection.createStatement();
                              ResultSet audit = query.executeQuery("SELECT document_hash, source_id, reference FROM reader_imports")) {
                while (audit.next()) {
                    if (documentHash.equals(audit.getString("document_hash"))) importedSources.add(audit.getString("source_id"));
                    String reference = audit.getString("reference");
                    if (reference != null) importedReferences.add(reference.trim().toUpperCase(Locale.ROOT));
                }
            }
            Set<String> existingPayments = new HashSet<>();
            try (Statement query = connection.createStatement();
                 ResultSet payments = query.executeQuery("SELECT customer_id, record_date, amount FROM statement_records WHERE amount < 0")) {
                while (payments.next()) existingPayments.add(payments.getString("customer_id") + "|" + payments.getString("record_date")
                        + "|" + BigDecimal.valueOf(payments.getDouble("amount")).negate().setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
            for (StatementPdfReader.SourceRow source : rows) {
                String date = parseDate(source.dateText(), year);
                BigDecimal amount = "deposit".equals(source.direction()) ? parsePositiveAmount(source.depositText()) : null;
                String sourceText = clip(source.text(), 4000);
                if (importedSources.contains(source.sourceId())) {
                    results.add(previewRow(source, date, amount, "", List.of(), "imported", "This PDF row was already imported."));
                    continue;
                }
                if (!source.reference().isBlank() && importedReferences.contains(source.reference().trim().toUpperCase(Locale.ROOT))) {
                    results.add(previewRow(source, date, amount, "", List.of(), "imported", "This bank reference was already imported."));
                    continue;
                }
                if (!"deposit".equals(source.direction()) || date.isBlank() || amount == null) {
                    String reason = !"deposit".equals(source.direction()) ? "Not a deposit."
                            : date.isBlank() ? "Date is missing or invalid." : "Deposit amount is missing or invalid.";
                    String state = "deposit".equals(source.direction()) ? "review" : "ignored";
                    results.add(previewRow(source, date, amount, "", List.of(), state, reason));
                    continue;
                }
                List<Customer> full = new ArrayList<>();
                List<Customer> candidateCustomers = new ArrayList<>();
                NameWindows windows = prepareWindows(sourceText);
                for (CustomerName name : names) {
                    Match match = matchName(windows, name);
                    if (match.full()) full.add(name.customer());
                    if (match.full() || match.partial()) candidateCustomers.add(name.customer());
                }
                String customerId = "";
                String status = "review";
                String reason = full.size() > 1 ? "Ambiguous full customer name; select the customer manually."
                        : full.isEmpty() ? "Partial or unmatched customer name; select the customer manually."
                        : "Unique full customer name.";
                if (full.size() == 1) {
                    customerId = full.get(0).getId();
                    status = "ready";
                    if (existingPayments.contains(customerId + "|" + date + "|" + amount.toPlainString())) {
                        status = "duplicate";
                        reason = "A payment with this customer, date, and amount already exists; explicit override is required.";
                    }
                    String fingerprint = customerId + "|" + date + "|" + amount.toPlainString();
                    if (!fingerprintsInDocument.add(fingerprint)) {
                        status = "duplicate";
                        reason = "This document repeats the same customer, date, and amount; explicit override is required.";
                    }
                }
                List<String> candidates = candidateCustomers.stream().map(Customer::getId).distinct().toList();
                results.add(previewRow(source, date, amount, customerId, candidates, status, reason));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to preview statement rows.", e);
        }
        return results;
    }

    private record CustomerName(Customer customer, List<String> words, Set<String> compact,
                                List<Map<String, Integer>> variants) { }
    private record NameWindows(Set<String> words, Set<String> compact, Map<String, Integer> counts) { }

    private static CustomerName prepareName(Customer customer) {
        List<String> firstWords = Arrays.stream(normalizeWords(customer.getFirstName())).filter(word -> !word.isBlank()).toList();
        List<String> lastWords = Arrays.stream(normalizeWords(customer.getLastName())).filter(word -> !word.isBlank()).toList();
        if (firstWords.isEmpty() || lastWords.isEmpty()) return new CustomerName(customer, List.of(), Set.of(), List.of());
        List<String> nameWords = new ArrayList<>(firstWords);
        nameWords.addAll(lastWords);
        if (nameWords.size() > 12) return new CustomerName(customer, List.of(), Set.of(), List.of());
        Set<String> compactNames = new HashSet<>(List.of(String.join("", firstWords) + String.join("", lastWords),
                String.join("", lastWords) + String.join("", firstWords)));
        List<Map<String, Integer>> variants = new ArrayList<>();
        for (List<String> first : List.of(firstWords, List.of(String.join("", firstWords)))) {
            for (List<String> last : List.of(lastWords, List.of(String.join("", lastWords)))) {
                List<String> words = new ArrayList<>(first);
                words.addAll(last);
                variants.add(wordCounts(words));
            }
        }
        return new CustomerName(customer, nameWords, compactNames, variants);
    }

    private static Map<String, Integer> wordCounts(List<String> words) {
        Map<String, Integer> counts = new HashMap<>();
        for (String word : words) counts.merge(word, 1, Integer::sum);
        return counts;
    }

    private static NameWindows prepareWindows(String text) {
        List<String> sourceWords = Arrays.stream(normalizeWords(text)).filter(word -> !word.isBlank()).toList();
        Set<String> compact = new HashSet<>();
        for (int start = 0; start < sourceWords.size(); start++) {
            for (int size = 1; size <= 12 && start + size <= sourceWords.size(); size++) {
                List<String> window = sourceWords.subList(start, start + size);
                compact.add(String.join("", window));
            }
        }
        return new NameWindows(new HashSet<>(sourceWords), compact, wordCounts(sourceWords));
    }

    private static Match matchName(NameWindows source, CustomerName name) {
        boolean full = !name.words().isEmpty() && (name.variants().stream().anyMatch(counts ->
                counts.entrySet().stream().allMatch(entry -> source.counts().getOrDefault(entry.getKey(), 0) >= entry.getValue()))
                || name.compact().stream().anyMatch(source.compact()::contains));
        boolean partial = name.words().stream().filter(word -> word.length() > 1).anyMatch(source.words()::contains);
        return new Match(full, partial);
    }

    private static String[] normalizeWords(String text) {
        String normalized = java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return normalized.split("[^\\p{L}\\p{N}]+");
    }

    private record Match(boolean full, boolean partial) { }

    private static PreviewRow previewRow(StatementPdfReader.SourceRow source, String date, BigDecimal amount,
                                         String customerId, List<String> candidates, String status, String reason) {
        return new PreviewRow(source.sourceId(), source.page(), source.line(), clip(source.text(), 4000),
                date, amount, customerId, candidates, status, reason, clip(source.reference(), 160));
    }

    private static boolean wasImported(Connection connection, String hash, String sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM reader_imports WHERE document_hash = ? AND source_id = ?")) {
            statement.setString(1, hash);
            statement.setString(2, sourceId);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static boolean referenceExists(Connection connection, String reference) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM reader_imports WHERE reference IS NOT NULL AND upper(trim(reference)) = upper(trim(?)) LIMIT 1")) {
            statement.setString(1, reference);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static boolean fingerprintExists(Connection connection, String customerId, String date,
                                             BigDecimal positiveAmount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM statement_records WHERE customer_id = ? AND record_date = ? AND amount = ? LIMIT 1")) {
            statement.setString(1, customerId);
            statement.setString(2, date);
            statement.setBigDecimal(3, positiveAmount.negate());
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private ImportResult localImport(String hash, List<PreviewRow> rows, List<Customer> customers) {
        Set<String> accessibleIds = new HashSet<>();
        if (customers != null) for (Customer customer : customers) accessibleIds.add(customer.getId());
        List<ImportRow> resultRows = new ArrayList<>();
        try (Connection connection = localConnection()) {
            ensureAuditSchema(connection);
            connection.setAutoCommit(false);
            try {
                for (PreviewRow row : rows) {
                    String customerId = row.customerId();
                    if (!accessibleIds.contains(customerId)) {
                        resultRows.add(new ImportRow(row.sourceId(), "skipped", "Customer is no longer accessible.", null));
                        continue;
                    }
                    if (wasImported(connection, hash, row.sourceId())) {
                        resultRows.add(new ImportRow(row.sourceId(), "skipped", "This PDF row was already imported.", null));
                        continue;
                    }
                    if (!row.reference().isBlank() && referenceExists(connection, row.reference())) {
                        resultRows.add(new ImportRow(row.sourceId(), "skipped", "This bank reference was already imported.", null));
                        continue;
                    }
                    if ("duplicate".equals(row.status()) && !row.overrideDuplicate()) {
                        resultRows.add(new ImportRow(row.sourceId(), "skipped", "Explicit duplicate override is required.", null));
                        continue;
                    }
                    BigDecimal negative = row.amount().negate().setScale(2, RoundingMode.UNNECESSARY);
                    if (!row.overrideDuplicate() && fingerprintExists(connection, customerId, row.date(), row.amount())) {
                        resultRows.add(new ImportRow(row.sourceId(), "skipped", "Payment fingerprint already exists; preview again and explicitly override.", null));
                        continue;
                    }
                    long recordId;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO statement_records(customer_id, record_date, type, amount) VALUES (?, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        statement.setString(1, customerId);
                        statement.setString(2, row.date());
                        statement.setString(3, PAYMENT_TYPE);
                        statement.setBigDecimal(4, negative);
                        statement.executeUpdate();
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("Statement row ID was not returned.");
                            recordId = keys.getLong(1);
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO reader_imports(document_hash, source_id, customer_id, record_id, record_date, amount, reference, source_text, imported_at)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        statement.setString(1, hash);
                        statement.setString(2, row.sourceId());
                        statement.setString(3, customerId);
                        statement.setLong(4, recordId);
                        statement.setString(5, row.date());
                        statement.setBigDecimal(6, negative);
                        statement.setString(7, row.reference().isBlank() ? null : row.reference().trim().toUpperCase(Locale.ROOT));
                        statement.setString(8, clip(row.text(), 4000));
                        statement.setString(9, java.time.Instant.now(clock).toString());
                        statement.executeUpdate();
                    }
                    resultRows.add(new ImportRow(row.sourceId(), "imported", "Payment imported.", recordId));
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to import statement payments; no rows were committed.", e);
        }
        int imported = (int) resultRows.stream().filter(row -> row.status().equals("imported")).count();
        return new ImportResult(imported, resultRows.size() - imported, resultRows);
    }

    private List<PreviewRow> remotePreview(String hash, List<StatementPdfReader.SourceRow> sourceRows) {
        JSONArray rows = new JSONArray();
        for (StatementPdfReader.SourceRow row : sourceRows) {
            rows.put(new JSONObject().put("source_id", row.sourceId()).put("page", row.page()).put("line", row.line())
                    .put("text", clip(row.text(), 4000)).put("date_text", clip(row.dateText(), 32))
                    .put("deposit_text", clip(row.depositText(), 80)).put("direction", row.direction())
                    .put("reference", clip(row.reference(), 160)));
        }
        JSONObject response = CompManagerApiClient.object(database.getApiClient().post("/v1/statement-reader/preview",
                new JSONObject().put("document_hash", hash).put("rows", rows)));
        JSONArray preview = response.getJSONArray("rows");
        List<PreviewRow> result = new ArrayList<>();
        Map<String, StatementPdfReader.SourceRow> sources = new java.util.HashMap<>();
        for (StatementPdfReader.SourceRow source : sourceRows) sources.put(source.sourceId(), source);
        for (int i = 0; i < preview.length(); i++) {
            JSONObject row = preview.getJSONObject(i);
            List<String> candidates = new ArrayList<>();
            JSONArray ids = row.optJSONArray("candidate_ids");
            if (ids != null) for (int j = 0; j < ids.length(); j++) candidates.add(ids.getString(j));
            BigDecimal amount = row.isNull("amount") ? null : BigDecimal.valueOf(row.getDouble("amount")).setScale(2, RoundingMode.HALF_UP);
            String sourceId = row.getString("source_id");
            String state = row.optString("status");
            String reason = row.optString("reason");
            StatementPdfReader.SourceRow source = sources.get(sourceId);
            if ("ignored".equals(state) && source != null && "deposit".equals(source.direction())) {
                state = "review";
                reason = reason.isBlank() ? "Check the date and amount and complete them manually." : reason;
            }
            result.add(new PreviewRow(sourceId, row.getInt("page"), row.getInt("line"),
                    row.optString("text"), row.optString("date"), amount, row.optString("customer_id"),
                    candidates, state, reason, row.optString("reference")));
        }
        return result;
    }

    private ImportResult remoteImport(String hash, List<PreviewRow> selected) {
        JSONArray rows = new JSONArray();
        for (PreviewRow row : selected) {
            rows.put(new JSONObject().put("source_id", row.sourceId()).put("page", row.page()).put("line", row.line())
                    .put("text", clip(row.text(), 4000)).put("date", row.date())
                    .put("amount", row.amount().doubleValue()).put("customer_id", row.customerId())
                    .put("reference", clip(row.reference(), 160))
                    .put("override_duplicate", row.overrideDuplicate()));
        }
        JSONObject response = CompManagerApiClient.object(database.getApiClient().post("/v1/statement-reader/import",
                new JSONObject().put("document_hash", hash).put("rows", rows)));
        JSONArray returned = response.getJSONArray("rows");
        List<ImportRow> result = new ArrayList<>();
        for (int i = 0; i < returned.length(); i++) {
            JSONObject row = returned.getJSONObject(i);
            Long recordId = row.has("record_id") && !row.isNull("record_id") ? row.getLong("record_id") : null;
            result.add(new ImportRow(row.getString("source_id"), row.getString("status"), row.optString("reason"), recordId));
        }
        return new ImportResult(response.getInt("imported"), response.getInt("skipped"), result);
    }

    private void validateDocument(String hash, List<StatementPdfReader.SourceRow> rows) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid PDF document hash.");
        if (rows == null || rows.size() > StatementPdfReader.MAX_ROWS) throw new IllegalArgumentException("PDF exceeds the 1,000-row limit.");
        Set<String> ids = new HashSet<>();
        Set<String> positions = new HashSet<>();
        for (StatementPdfReader.SourceRow row : rows) {
            if (row == null || row.page() < 1 || row.line() < 1 || row.sourceId() == null || row.sourceId().isBlank()
                    || row.text() == null || row.text().length() > 4000 || row.dateText() == null
                    || row.dateText().length() > 32 || row.depositText() == null || row.depositText().length() > 80
                    || row.direction() == null || !validReference(row.reference())
                    || !Set.of("deposit", "withdrawal", "unknown").contains(row.direction())
                    || !ids.add(row.sourceId()) || !positions.add(row.page() + ":" + row.line())) {
                throw new IllegalArgumentException("Invalid or duplicate PDF source row.");
            }
        }
    }

    private static void validateImportRow(PreviewRow row) {
        if (row == null || row.sourceId() == null || row.sourceId().isBlank() || row.page() < 1 || row.line() < 1 || row.text().length() > 4000
                || row.customerId().isBlank() || row.reference().length() > 160 || row.amount() == null
                || !validReference(row.reference())
                || row.amount().signum() <= 0 || row.amount().compareTo(MAX_AMOUNT) > 0 || row.date().isBlank()
                || !parseDate(row.date(), 0).equals(row.date())
                || !Set.of("ready", "review", "duplicate").contains(row.status())
                || (row.overrideDuplicate() && !"duplicate".equals(row.status()))) {
            throw new IllegalArgumentException("Selected rows require a valid date, accessible customer, and positive amount.");
        }
    }

    private static boolean validReference(String reference) {
        return reference != null && reference.length() <= 160
                && (reference.isEmpty() || reference.matches("(?=.*\\d)[A-Za-z0-9 ._:/#-]+"));
    }

    private Connection localConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.getDatabasePath());
    }

    private static void ensureAuditSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS reader_imports ("
                    + "document_hash TEXT NOT NULL, source_id TEXT NOT NULL, customer_id TEXT NOT NULL,"
                    + "record_id INTEGER NOT NULL CHECK(record_id > 0),"
                    + "record_date TEXT NOT NULL CHECK(length(record_date) = 10 AND record_date GLOB '[0-1][0-9]/[0-3][0-9]/[0-9][0-9][0-9][0-9]'),"
                    + "amount REAL NOT NULL CHECK(amount < 0 AND amount = ROUND(amount, 2)),"
                    + "reference TEXT CHECK(reference IS NULL OR (reference = upper(trim(reference)) AND length(reference) <= 160)),"
                    + "source_text TEXT NOT NULL,"
                    + "imported_at TEXT NOT NULL, PRIMARY KEY(document_hash, source_id))");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS reader_imports_reference_idx "
                    + "ON reader_imports(reference COLLATE NOCASE) WHERE reference IS NOT NULL AND reference <> ''");
        }
    }

    private static boolean auditTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'reader_imports'");
             ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
