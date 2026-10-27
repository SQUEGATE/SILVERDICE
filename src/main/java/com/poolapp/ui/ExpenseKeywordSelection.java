package com.poolapp.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Remembers, per device and signed-in user, which saved expense keywords were last checked. */
public final class ExpenseKeywordSelection {
    private final Preferences preferences;

    public ExpenseKeywordSelection() {
        this(Preferences.userNodeForPackage(ExpenseKeywordSelection.class).node("expenseKeywordSelection"));
    }

    public ExpenseKeywordSelection(Preferences preferences) { this.preferences = preferences; }

    /** Keywords from {@code saved} that were checked last time; empty when nothing was remembered. */
    public Set<String> load(String scope, List<String> saved) {
        Set<String> remembered = Set.of(preferences.get(key(scope), "").split(","));
        Set<String> checked = new LinkedHashSet<>();
        for (String keyword : saved) if (remembered.contains(fingerprint(keyword))) checked.add(keyword);
        return checked;
    }

    public void save(String scope, Collection<String> checked) {
        // Short fingerprints keep 100 keywords well under the Preferences value limit.
        Set<String> values = new LinkedHashSet<>();
        for (String keyword : checked) values.add(fingerprint(keyword));
        if (values.isEmpty()) preferences.remove(key(scope));
        else preferences.put(key(scope), String.join(",", values));
        try { preferences.flush(); } catch (BackingStoreException ignored) { }
    }

    private static String key(String scope) { return hash("scope\n" + (scope == null ? "default" : scope)).substring(0, 32); }

    private static String fingerprint(String keyword) {
        return hash(keyword.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)).substring(0, 16);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
