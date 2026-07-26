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

public class EmailService {
    private final Properties config;

    public EmailService() {
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
            throw new RuntimeException("Unable to load email configuration", e);
        }
        return properties;
    }

    public void sendEmail(String to, String subject, String messageText) {
        if (!Boolean.parseBoolean(config.getProperty("email.enabled", "false"))) {
            throw new IllegalStateException("Email sending is disabled. Set email.enabled=true in config.properties.");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
            throw new IllegalStateException("Email sending is not supported on this machine.");
        }
        try {
            String mailto = String.format("mailto:%s?subject=%s&body=%s",
                    encode(to),
                    encode(subject),
                    encode(messageText));
            Desktop.getDesktop().mail(new URI(mailto));
        } catch (Exception e) {
            throw new RuntimeException("Unable to open email client", e);
        }
    }

    private String encode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
