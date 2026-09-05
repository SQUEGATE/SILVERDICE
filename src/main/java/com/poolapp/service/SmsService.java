package com.poolapp.service;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class SmsService {
    private final Properties config;

    public SmsService() {
        this.config = loadConfiguration();
    }

    private Properties loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to load packaged SMS configuration", e);
        }

        File external = new File("config.properties");
        if (external.exists()) {
            try (InputStream input = new FileInputStream(external)) {
                properties.load(input);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load external SMS configuration", e);
            }
        }
        return properties;
    }

    public void sendSms(String to, String messageText) {
        String phoneNumber = normalizePhoneNumber(to);
        if (phoneNumber.isBlank()) {
            throw new IllegalArgumentException("Customer phone number is invalid or empty.");
        }
        // Windows shows its own "no app found" dialog instead of throwing when no sms: handler is registered.
        if (isWindows() && !isSmsProtocolRegistered()) {
            openGoogleMessages(phoneNumber, messageText, null);
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IllegalStateException("Messaging app integration is not supported on this machine.");
        }
        try {
            String smsUri = String.format("sms:%s?body=%s", encode(phoneNumber), encode(messageText));
            Desktop.getDesktop().browse(new URI(smsUri));
        } catch (Exception e) {
            openGoogleMessages(phoneNumber, messageText, e);
        }
    }

    private String normalizePhoneNumber(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.length() == 10) {
            return "+1" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("1")) {
            return "+" + digits;
        }
        if (phoneNumber != null && phoneNumber.trim().startsWith("+") && digits.length() >= 8) {
            return "+" + digits;
        }
        return "";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isSmsProtocolRegistered() {
        try {
            Process process = new ProcessBuilder("reg", "query", "HKCR\\sms").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void openGoogleMessages(String phoneNumber, String messageText, Exception originalError) {
        File messagesShortcut = new File(System.getenv("APPDATA"),
                "Microsoft\\Windows\\Start Menu\\Programs\\Chrome Apps\\Messages.lnk");
        if (!messagesShortcut.isFile()) {
            throw new RuntimeException("Windows has no SMS handler, and Google Messages could not be found. "
                    + "Open Google Messages manually at messages.google.com/web.", originalError);
        }
        try {
            new ProcessBuilder("explorer.exe", messagesShortcut.getAbsolutePath()).start();
        } catch (IOException e) {
            throw new RuntimeException("Unable to open Google Messages: " + e.getMessage(), e);
        }
    }

    private String encode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
