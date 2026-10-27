package com.poolapp.db;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared expense keyword matching, amount extraction, and total calculation rules. */
public final class ExpenseScanner {
    public static final int MAX_KEYWORD_LENGTH = 80;
    public static final int MAX_KEYWORDS = 100;
    private static final Pattern MONEY = Pattern.compile(
            "(?:\\(\\s*((?:\\$\\s*)?-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2})\\s*\\)|((?:\\$\\s*)?-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}))(?!\\d)");

    public record SourceLine(int page, String text) {
        public SourceLine {
            text = text == null ? "" : text;
        }
    }

    public record Match(int page, String keyword, String text, Long amountCents) {
        public Match {
            keyword = keyword == null ? "" : keyword;
            text = text == null ? "" : text;
        }
    }

    public record KeywordTotal(int count, long totalCents) { }

    public record Summary(Map<String, KeywordTotal> byKeyword, long grandTotalCents, int includedLineCount) {
        public Summary {
            byKeyword = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byKeyword));
        }
    }

    private record CompiledKeyword(String spelling, Pattern pattern) { }

    public List<String> normalizeKeywords(List<String> values) {
        List<String> normalized = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String keyword = value.trim().replaceAll("\\s+", " ");
                if (keyword.isEmpty()) continue;
                if (keyword.length() > MAX_KEYWORD_LENGTH) {
                    throw new IllegalArgumentException("Each expense keyword must be 80 characters or fewer.");
                }
                if (seen.add(keyword.toLowerCase(Locale.ROOT))) normalized.add(keyword);
            }
        }
        if (normalized.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("You can save no more than 100 expense keywords.");
        }
        return List.copyOf(normalized);
    }

    public List<Match> scan(List<SourceLine> lines, List<String> keywords) {
        List<String> normalized = normalizeKeywords(keywords);
        List<CompiledKeyword> compiled = normalized.stream().map(keyword ->
                new CompiledKeyword(keyword, Pattern.compile(keywordPattern(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)))
                .toList();
        List<Match> matches = new ArrayList<>();
        if (lines == null) return List.of();
        for (SourceLine line : lines) {
            if (line == null) continue;
            for (CompiledKeyword keyword : compiled) {
                if (keyword.pattern().matcher(line.text()).find()) {
                    matches.add(new Match(line.page(), keyword.spelling(), line.text(), firstAmountCents(line.text())));
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    public Summary summarize(List<Match> matches, Predicate<Match> included) {
        return summarize(matches, included, List.of());
    }

    public Summary summarize(List<Match> matches, Predicate<Match> included, List<String> keywordOrder) {
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        LinkedHashMap<String, Long> totals = new LinkedHashMap<>();
        for (String keyword : normalizeKeywords(keywordOrder)) {
            counts.put(keyword, new int[1]);
            totals.put(keyword, 0L);
        }
        long grandTotal = 0;
        int includedLines = 0;
        if (matches != null) {
            for (Match match : matches) {
                if (match == null || (included != null && !included.test(match))) continue;
                includedLines++;
                counts.computeIfAbsent(match.keyword(), ignored -> new int[1])[0]++;
                totals.putIfAbsent(match.keyword(), 0L);
                if (match.amountCents() != null) {
                    long amount = match.amountCents();
                    totals.put(match.keyword(), Math.addExact(totals.get(match.keyword()), amount));
                    grandTotal = Math.addExact(grandTotal, amount);
                }
            }
        }
        LinkedHashMap<String, KeywordTotal> byKeyword = new LinkedHashMap<>();
        for (String keyword : counts.keySet()) {
            byKeyword.put(keyword, new KeywordTotal(counts.get(keyword)[0], totals.get(keyword)));
        }
        return new Summary(byKeyword, grandTotal, includedLines);
    }

    public static Long firstAmountCents(String line) {
        if (line == null || line.isEmpty()) return null;
        Matcher matcher = MONEY.matcher(line);
        if (!matcher.find()) return null;
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        try {
            return new BigDecimal(value.replace("$", "").replace(",", "").replace(" ", ""))
                    .abs().movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("An expense amount exceeds the supported total range.", e);
        }
    }

    private static String keywordPattern(String keyword) {
        StringBuilder expression = new StringBuilder("(?<![A-Za-z0-9])");
        for (int i = 0; i < keyword.length(); i++) {
            char ch = keyword.charAt(i);
            expression.append(ch == ' ' ? "\\s+" : Pattern.quote(String.valueOf(ch)));
        }
        return expression.append("(?![A-Za-z0-9])").toString();
    }
}
