package com.poolapp.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record SalesQuote(long quoteId, String quoteNumber, String customerId, String quoteDate,
                         String notes, String terms, List<Line> lines, String convertedAt,
                         String createdAt, String updatedAt) {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    public static final String DEFAULT_TERMS =
            "I hope you're doing well! I wanted to let you know that in order to move forward with your order, "
            + "we kindly require payment in advance for the items requested. This helps us ensure timely processing "
            + "and secure the necessary materials for your service.\n\n"
            + "Once payment is received, we'll begin preparing everything right away and keep you updated throughout the process.\n\n"
            + "If you have any questions or need assistance with payment options, please feel free to reach out.\n\n"
            + "Thank you for your understanding and continued trust in our service!";
    public static final String CREDIT_KEYWORDS =
            "payment,paid,credit,refund,discount,adjustment,rebate,deposit,return,transfer in,check,cheque";

    public SalesQuote {
        customerId = text(customerId);
        quoteNumber = text(quoteNumber);
        notes = text(notes);
        terms = text(terms);
        lines = List.copyOf(lines);
        if (customerId.isBlank()) throw new IllegalArgumentException("Select a customer.");
        validateDate(quoteDate);
        if (notes.length() > 16000 || terms.length() > 16000)
            throw new IllegalArgumentException("Notes and terms may contain at most 16000 characters.");
        if (lines.isEmpty() || lines.size() > 200)
            throw new IllegalArgumentException("A quotation requires between 1 and 200 lines.");
    }

    public record Line(String date, String type, String description, BigDecimal amount) {
        public Line {
            validateDate(date);
            type = text(type).trim();
            description = text(description);
            if (type.isBlank()) throw new IllegalArgumentException("Line type is required.");
            if (amount == null || amount.signum() == 0 || amount.abs().compareTo(new BigDecimal("999999999.99")) > 0
                    || amount.stripTrailingZeros().scale() > 2)
                throw new IllegalArgumentException("Amount must be nonzero, at most 999999999.99, with at most 2 decimals.");
        }

        public String statementType() {
            return description.isBlank() || description.equals(type) ? type : type + ": " + description;
        }

        public JSONObject toJson() {
            return new JSONObject().put("date", date).put("type", type)
                    .put("description", description).put("amount", amount);
        }
    }

    public boolean converted() { return convertedAt != null && !convertedAt.isBlank(); }
    public BigDecimal total() { return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }
    public static String today() { return LocalDate.now().format(DATE); }
    public static void validateDate(String value) {
        try {
            if (value == null || !value.matches("\\d{2}/\\d{2}/\\d{4}")) throw new IllegalArgumentException();
            LocalDate.parse(value, DATE);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Dates must be valid MM/DD/YYYY dates.");
        }
    }

    // Statement debits are negative; payment-like credits are positive, including custom keywords.
    public static BigDecimal signedAmount(String type, BigDecimal magnitude, Map<String, String> settings) {
        String keywords = settings.getOrDefault("recordTypeCreditKeywords", CREDIT_KEYWORDS);
        if (keywords.isBlank()) keywords = CREDIT_KEYWORDS;
        String value = text(type).toLowerCase(Locale.ROOT);
        for (String word : keywords.split("[,\\n]")) {
            if (!word.isBlank() && value.contains(word.trim().toLowerCase(Locale.ROOT))) return magnitude.abs();
        }
        return magnitude.abs().negate();
    }

    public JSONObject body() {
        JSONArray array = new JSONArray();
        lines.forEach(line -> array.put(line.toJson()));
        return new JSONObject().put("quote_date", quoteDate).put("notes", notes).put("terms", terms).put("lines", array);
    }

    public static SalesQuote fromJson(JSONObject json) {
        JSONArray array = json.getJSONArray("lines");
        java.util.ArrayList<Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject line = array.getJSONObject(i);
            lines.add(new Line(line.getString("date"), line.getString("type"),
                    line.optString("description", ""), new BigDecimal(line.get("amount").toString())));
        }
        return new SalesQuote(json.getLong("quote_id"), json.getString("quote_number"), json.getString("customer_id"),
                json.getString("quote_date"), json.optString("notes", ""), json.optString("terms", ""), lines,
                json.isNull("converted_at") ? null : json.getString("converted_at"),
                json.optString("created_at", ""), json.optString("updated_at", ""));
    }

    private static String text(String value) { return value == null ? "" : value; }
}
