package com.poolapp.service;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class SmsService {
    private static final String GOOGLE_MESSAGES_APP_ID = "hpfldicfbfomlpcikngkocigghgafkph";
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
        String cleanPhone = to == null ? "" : to.replaceAll("[^0-9+]", "");
        if (cleanPhone.isBlank()) {
            throw new IllegalArgumentException("Customer phone number is invalid or empty.");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IllegalStateException("Messaging app integration is not supported on this machine.");
        }
        try {
            String smsUri = String.format("sms:%s?body=%s", encode(cleanPhone), encode(messageText));
            Desktop.getDesktop().browse(new URI(smsUri));
        } catch (Exception e) {
            openGoogleMessages(cleanPhone, messageText, e);
        }
    }

    private void openGoogleMessages(String phoneNumber, String messageText, Exception originalError) {
        File chromeProxy = new File(System.getenv("ProgramFiles"), "Google\\Chrome\\Application\\chrome_proxy.exe");
        if (!chromeProxy.isFile()) {
            throw new RuntimeException("Windows has no SMS handler, and Google Messages could not be found. "
                    + "Open Google Messages manually at messages.google.com/web.", originalError);
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(messageText), null);
            new ProcessBuilder(chromeProxy.getAbsolutePath(), "--profile-directory=Default", "--app-id=" + GOOGLE_MESSAGES_APP_ID).start();
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
