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
        File external = new File("config.properties");
        try (InputStream input = external.exists() ? new FileInputStream(external) : getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to load SMS configuration", e);
        }
        return properties;
    }

    public void sendSms(String to, String messageText) {
        if (!Boolean.parseBoolean(config.getProperty("sms.enabled", "false"))) {
            throw new IllegalStateException("SMS sending is disabled. Set sms.enabled=true in config.properties.");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IllegalStateException("SMS sending is not supported on this machine. Copy the message manually: " + messageText);
        }
        try {
            String smsUri = String.format("sms:%s?body=%s", encode(to), encode(messageText));
            Desktop.getDesktop().browse(new URI(smsUri));
        } catch (Exception e) {
            throw new RuntimeException("Unable to open SMS client", e);
        }
    }

    private String encode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
