package com.poolapp.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts transaction rows locally. The original PDF is never included in API requests.
 */
public final class StatementPdfReader {
    public static final long MAX_PDF_BYTES = 25L * 1024 * 1024;
    public static final int MAX_PAGES = 100;
    public static final int MAX_ROWS = 1000;
    private static final Pattern MONEY = Pattern.compile("(?<![\\w])\\$?\\(?-?\\d[\\d,]*\\.\\d{2}\\)?");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?)(?!\\d)");
    private static final Set<String> DEPOSIT_HEADERS = Set.of("deposit", "deposits", "addition", "additions", "credit", "credits");
    private static final Set<String> WITHDRAWAL_HEADERS = Set.of("withdrawal", "withdrawals", "subtraction", "subtractions", "debit", "debits");
    private static final Set<String> BALANCE_HEADERS = Set.of("balance", "ending", "daily");

    public record SourceRow(String sourceId, int page, int line, String text, String dateText,
                            String depositText, String direction, String reference) { }

    public record Extraction(String documentHash, int pageCount, List<SourceRow> rows) {
        public Extraction {
            rows = List.copyOf(rows);
        }
    }

    private record PositionedChar(int page, float x, float y, float width, String value) { }
    private record Token(String text, float left, float right, int start, int end) {
        float center() { return (left + right) / 2f; }
    }
    private record TextLine(int page, int line, float y, String text, List<Token> tokens) { }
    private record HeaderBounds(float dateRight, float depositLeft, float depositRight,
                                float withdrawalLeft, float withdrawalRight, float descriptionX,
                                boolean hasDeposit, boolean hasWithdrawal) { }
    private record NumberToken(String text, float x, boolean dollarSign) { }

    public Extraction extract(Path pdf) throws IOException {
        if (pdf == null || !Files.isRegularFile(pdf)) throw new IOException("Select a PDF file.");
        long size = Files.size(pdf);
        if (size > MAX_PDF_BYTES) throw new IOException("PDF exceeds the 25 MB limit.");
        return extract(Files.readAllBytes(pdf));
    }

    public Extraction extract(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) throw new IOException("The selected PDF is empty.");
        if (pdfBytes.length > MAX_PDF_BYTES) throw new IOException("PDF exceeds the 25 MB limit.");
        String hash = sha256(pdfBytes);
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > MAX_PAGES) throw new IOException("PDF exceeds the 100-page limit.");
            PositionStripper stripper = new PositionStripper();
            stripper.setSortByPosition(true);
            stripper.getText(document);
            if (stripper.characters.isEmpty()) {
                throw new IOException("This PDF has no extractable text. Scanned/image PDFs are unsupported; OCR is not available.");
            }
            List<TextLine> lines = makeLines(stripper.characters);
            List<SourceRow> rows = extractRows(lines);
            if (rows.size() > MAX_ROWS) throw new IOException("PDF exceeds the 1,000-row limit.");
            return new Extraction(hash, pageCount, rows);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Unable to read this PDF. It may be malformed or password-protected.", e);
        }
    }

    private List<SourceRow> extractRows(List<TextLine> lines) throws IOException {
        List<SourceRow> output = new ArrayList<>();
        int page = -1;
        List<TextLine> pageLines = new ArrayList<>();
        for (TextLine line : lines) {
            if (page != line.page()) {
                if (!pageLines.isEmpty()) parsePage(pageLines, output);
                pageLines = new ArrayList<>();
                page = line.page();
            }
            pageLines.add(line);
        }
        if (!pageLines.isEmpty()) parsePage(pageLines, output);
        if (output.size() > MAX_ROWS) throw new IOException("PDF exceeds the 1,000-row limit.");
        return output;
    }

    private void parsePage(List<TextLine> lines, List<SourceRow> output) throws IOException {
        HeaderBounds bounds = findBounds(lines);
        MutableRow current = null;
        for (TextLine line : lines) {
            if (line.text().isBlank() || isHeader(line.text())) continue;
            String dateText = firstColumnDate(line, bounds);
            List<NumberToken> amounts = moneyTokens(line);
            NumberToken deposit = findInColumn(amounts, bounds.depositLeft(), bounds.depositRight(), bounds.hasDeposit());
            NumberToken withdrawal = findInColumn(amounts, bounds.withdrawalLeft(), bounds.withdrawalRight(), bounds.hasWithdrawal());
            boolean fallbackDollar = !bounds.hasDeposit() && !bounds.hasWithdrawal()
                    && amounts.size() == 1 && amounts.get(0).dollarSign()
                    && !hasWithdrawalOrBalanceText(line.text());
            String direction = deposit != null || fallbackDollar ? "deposit" : withdrawal != null ? "withdrawal" : "unknown";
            String amount = deposit != null ? deposit.text() : fallbackDollar ? amounts.get(0).text() : "";
            if (!dateText.isEmpty()) {
                current = new MutableRow(line.page(), line.line(), line.text(), dateText, amount, direction);
                output.add(current.toSource());
                if (output.size() > MAX_ROWS) throw new IOException("PDF exceeds the 1,000-row limit.");
            } else if (current != null && !isLikelyFooter(line.text()) && isDescriptionContinuation(line, bounds)) {
                current.text = (current.text + " " + line.text()).trim();
                current.refreshSource(output);
            }
        }
    }

    private static boolean isDescriptionContinuation(TextLine line, HeaderBounds bounds) {
        if (line.tokens().isEmpty()) return false;
        if (moneyTokens(line).stream().anyMatch(value -> value.x() >= bounds.depositLeft()
                && value.x() <= bounds.withdrawalRight())) return false;
        return line.tokens().get(0).left() >= bounds.descriptionX() - 8f;
    }

    private static boolean isLikelyFooter(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("page ") || lower.contains("ending balance") || lower.contains("beginning balance")
                || lower.contains("total deposits") || lower.contains("total withdrawals");
    }

    private static boolean hasWithdrawalOrBalanceText(String text) {
        return Pattern.compile("(?i)\\b(withdraw\\w*|debit|atm|balance|ending)\\b").matcher(text).find();
    }

    private static String firstColumnDate(TextLine line, HeaderBounds bounds) {
        Matcher matcher = DATE.matcher(line.text());
        while (matcher.find()) {
            float x = xAt(line, matcher.start(), matcher.end());
            if (x <= bounds.dateRight()) return matcher.group(1);
        }
        return "";
    }

    private static float xAt(TextLine line, int from, int to) {
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        for (Token token : line.tokens()) {
            if (token.end() > from && token.start() < to) {
                left = Math.min(left, token.left());
                right = Math.max(right, token.right());
            }
        }
        return left == Float.MAX_VALUE ? Float.MAX_VALUE : (left + right) / 2f;
    }

    private static NumberToken findInColumn(List<NumberToken> amounts, float left, float right, boolean present) {
        if (!present) return null;
        List<NumberToken> matches = amounts.stream().filter(value -> value.x() >= left && value.x() <= right).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<NumberToken> moneyTokens(TextLine line) {
        List<NumberToken> values = new ArrayList<>();
        Matcher matcher = MONEY.matcher(line.text());
        while (matcher.find()) {
            String text = matcher.group();
            values.add(new NumberToken(text, xAt(line, matcher.start(), matcher.end()), text.contains("$")));
        }
        return values;
    }

    private static HeaderBounds findBounds(List<TextLine> lines) {
        List<Float> dates = new ArrayList<>(), deposits = new ArrayList<>(), withdrawals = new ArrayList<>(), balances = new ArrayList<>();
        List<Float> dateNeighbors = new ArrayList<>();
        List<Float> description = new ArrayList<>();
        for (TextLine line : lines) {
            if (!isHeader(line.text())) continue;
            for (Token token : line.tokens()) {
                String normalized = token.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
                if (normalized.equals("date")) dates.add(token.center());
                if (DEPOSIT_HEADERS.contains(normalized)) deposits.add(token.center());
                if (WITHDRAWAL_HEADERS.contains(normalized)) withdrawals.add(token.center());
                if (BALANCE_HEADERS.contains(normalized)) balances.add(token.center());
                if (Set.of("number", "description", "memo", "details").contains(normalized)) dateNeighbors.add(token.center());
                if (normalized.equals("description") || normalized.equals("memo") || normalized.equals("details")) description.add(token.left());
            }
        }
        float dateX = median(dates, 0f), depositX = median(deposits, Float.MAX_VALUE);
        float withdrawalX = median(withdrawals, Float.MAX_VALUE), balanceX = median(balances, Float.MAX_VALUE);
        float firstMoneyX = minFinite(depositX, withdrawalX, balanceX);
        float nextDateColumn = dateNeighbors.stream().filter(value -> value > dateX).min(Float::compare).orElse(firstMoneyX);
        float dateRight = finite(dateX) && finite(nextDateColumn) ? midpoint(dateX, nextDateColumn)
                : finite(firstMoneyX) ? firstMoneyX - 10f : Float.MAX_VALUE;
        float depositLeft = finite(depositX) ? nearestLeft(depositX, dateX, withdrawalX, balanceX) : Float.MAX_VALUE;
        float depositRight = finite(depositX) ? nearestRight(depositX, dateX, withdrawalX, balanceX) : Float.MAX_VALUE;
        float withdrawalLeft = finite(withdrawalX) ? nearestLeft(withdrawalX, dateX, depositX, balanceX) : Float.MAX_VALUE;
        float withdrawalRight = finite(withdrawalX) ? nearestRight(withdrawalX, dateX, depositX, balanceX) : Float.MAX_VALUE;
        float descriptionX = median(description, finite(depositX) ? depositX - 70f : 0f);
        return new HeaderBounds(dateRight, depositLeft, depositRight, withdrawalLeft, withdrawalRight,
                descriptionX, !deposits.isEmpty(), !withdrawals.isEmpty());
    }

    private static boolean isHeader(String text) {
        if (DATE.matcher(text).find()) return false;
        String[] words = text.toLowerCase(Locale.ROOT).split("[^a-z]+");
        Set<String> headers = Set.of("date", "number", "description", "memo", "details", "deposit", "deposits",
                "addition", "additions", "withdrawal", "withdrawals", "subtraction", "subtractions",
                "balance", "ending", "daily");
        long matches = java.util.Arrays.stream(words).filter(headers::contains).distinct().count();
        return matches >= 2;
    }

    private static float median(List<Float> values, float fallback) {
        if (values.isEmpty()) return fallback;
        List<Float> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static float minFinite(float... values) {
        float result = Float.MAX_VALUE;
        for (float value : values) if (finite(value)) result = Math.min(result, value);
        return result;
    }

    private static float nearestLeft(float x, float... values) {
        float result = Float.NEGATIVE_INFINITY;
        for (float value : values) if (finite(value) && value < x) result = Math.max(result, value);
        return result == Float.NEGATIVE_INFINITY ? x - 120f : midpoint(result, x);
    }

    private static float nearestRight(float x, float... values) {
        float result = Float.POSITIVE_INFINITY;
        for (float value : values) if (finite(value) && value > x) result = Math.min(result, value);
        return result == Float.POSITIVE_INFINITY ? x + 120f : midpoint(result, x);
    }

    private static boolean finite(float value) { return Float.isFinite(value) && value != Float.MAX_VALUE; }
    private static float midpoint(float left, float right) { return (left + right) / 2f; }

    private static List<TextLine> makeLines(List<PositionedChar> chars) {
        List<TextLine> lines = new ArrayList<>();
        java.util.Map<Integer, Integer> pageLineNumbers = new java.util.HashMap<>();
        chars.sort(Comparator.comparingInt(PositionedChar::page).thenComparing(PositionedChar::y).thenComparing(PositionedChar::x));
        int index = 0;
        while (index < chars.size()) {
            PositionedChar first = chars.get(index);
            List<PositionedChar> lineChars = new ArrayList<>();
            int end = index;
            while (end < chars.size() && chars.get(end).page() == first.page()
                    && Math.abs(chars.get(end).y() - first.y()) <= 2.25f) {
                lineChars.add(chars.get(end++));
            }
            StringBuilder text = new StringBuilder();
            List<Token> tokens = new ArrayList<>();
            StringBuilder token = new StringBuilder();
            float tokenLeft = 0f, tokenRight = 0f, previousRight = -Float.MAX_VALUE;
            int tokenStart = 0;
            for (PositionedChar ch : lineChars) {
                String value = ch.value();
                boolean whitespace = value.isBlank();
                boolean gap = !whitespace && token.length() > 0 && ch.x() - previousRight > Math.max(1.5f, ch.width() * 0.48f);
                if ((whitespace || gap) && token.length() > 0) {
                    appendToken(tokens, text, token, tokenLeft, tokenRight, tokenStart);
                    text.append(' ');
                    token.setLength(0);
                }
                if (whitespace) {
                    previousRight = ch.x() + ch.width();
                    continue;
                }
                if (token.length() == 0) {
                    tokenLeft = ch.x();
                    tokenStart = text.length();
                }
                token.append(value);
                tokenRight = Math.max(tokenRight, ch.x() + ch.width());
                previousRight = ch.x() + ch.width();
            }
            if (token.length() > 0) appendToken(tokens, text, token, tokenLeft, tokenRight, tokenStart);
            String raw = text.toString().trim().replaceAll("\\s+", " ");
            int lineNo = pageLineNumbers.merge(first.page(), 1, Integer::sum);
            lines.add(new TextLine(first.page(), lineNo, first.y(), raw, List.copyOf(tokens)));
            index = Math.max(end, index + 1);
        }
        return lines;
    }

    private static void appendToken(List<Token> tokens, StringBuilder text, StringBuilder value,
                                    float left, float right, int start) {
        String token = value.toString();
        if (!token.isBlank()) {
            text.append(token);
            tokens.add(new Token(token, left, right, start, text.length()));
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
    }

    private static final class MutableRow {
        final int page, line;
        String text;
        final String date, amount, direction;
        MutableRow(int page, int line, String text, String date, String amount, String direction) {
            this.page = page;
            this.line = line;
            this.text = text;
            this.date = date;
            this.amount = amount;
            this.direction = direction;
        }
        SourceRow toSource() {
            String reference = "";
            Matcher matcher = Pattern.compile("(?i)(?:ref(?:erence)?\\s*#?\\s*)([A-Z0-9._:/#-]{1,160})").matcher(text);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (candidate.matches("(?=.*\\d)[A-Za-z0-9 ._:/#-]+")) {
                    reference = candidate;
                    break;
                }
            }
            return new SourceRow(page + ":" + line, page, line,
                    text.length() <= 4000 ? text : text.substring(0, 4000), date, amount, direction, reference);
        }
        void refreshSource(List<SourceRow> rows) {
            rows.set(rows.size() - 1, toSource());
        }
    }

    private static final class PositionStripper extends PDFTextStripper {
        final List<PositionedChar> characters = new ArrayList<>();
        private int page = 0;

        PositionStripper() throws IOException { }

        @Override
        protected void writePageStart() {
            page++;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                if (unicode != null && !unicode.isEmpty()) {
                    characters.add(new PositionedChar(page, position.getXDirAdj(), position.getYDirAdj(),
                            position.getWidthDirAdj(), unicode));
                }
            }
        }
    }
}
