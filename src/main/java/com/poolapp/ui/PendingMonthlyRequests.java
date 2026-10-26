package com.poolapp.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Keep only unconfirmed billing requests, including after a restart or lost response. */
public final class PendingMonthlyRequests {
    private final Preferences preferences;

    public PendingMonthlyRequests() {
        this(Preferences.userNodeForPackage(PendingMonthlyRequests.class).node("pendingMonthly"));
    }

    public PendingMonthlyRequests(Preferences preferences) { this.preferences = preferences; }

    private String key(String identity, String scope) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((identity + "\n" + scope).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public String find(String identity, String scope) { return preferences.get(key(identity, scope), null); }

    public String retain(String identity, String scope, String requestId) {
        String existing = find(identity, scope);
        String retained = existing != null ? existing : requestId == null ? UUID.randomUUID().toString() : requestId;
        preferences.put(key(identity, scope), retained);
        flush();
        return retained;
    }

    public void confirmed(String identity, String scope) {
        preferences.remove(key(identity, scope));
        flush();
    }

    private void flush() {
        try { preferences.flush(); }
        catch (BackingStoreException e) { throw new IllegalStateException("Unable to preserve pending billing request; no automatic retry will be made.", e); }
    }
}
