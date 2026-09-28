package com.poolapp.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class EmailService {
    private final Properties config;

    public EmailService() {
        this.config = loadConfiguration();
    }

    private Properties loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to load packaged email configuration", e);
        }

        File external = new File("config.properties");
        if (external.exists()) {
            try (InputStream input = new FileInputStream(external)) {
                properties.load(input);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load external email configuration", e);
            }
        }
        return properties;
    }

    public void sendEmail(String to, String subject, String messageText, String senderEmail, String senderName, File attachment) {
        if (attachment == null || !attachment.isFile()) {
            throw new IllegalArgumentException("Statement PDF attachment was not created.");
        }
        try {
            startOutlookCompose(to, subject, messageText, attachment);
        } catch (IOException e) {
            throw new RuntimeException("Unable to open Outlook: " + e.getMessage(), e);
        }
    }

    private void startOutlookCompose(String to, String subject, String messageText, File attachment) throws IOException {
        String outlookSubject = subject == null ? "" : subject.replace("\"", "'");
        String recipient = to == null ? "" : to.trim();
        IOException lastError = null;
        String[][] commands = {
                {"outlook.exe", "/c", "ipm.note", "/m", recipient + "?subject=" + outlookSubject, "/a", attachment.getAbsolutePath()},
                {"olk.exe", "/c", "ipm.note", "/m", recipient + "?subject=" + outlookSubject, "/a", attachment.getAbsolutePath()}
        };
        for (String[] command : commands) {
            try {
                new ProcessBuilder(command).start();
                return;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError == null ? new IOException("Outlook was not found on this device.") : lastError;
    }

    private String extractErrorMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable curr = t;
        while (curr != null) {
            String msg = curr.getMessage();
            if (msg != null && !msg.isBlank()) {
                if (sb.length() > 0 && !sb.toString().contains(msg.trim())) {
                    sb.append(" -> ");
                    sb.append(msg.trim());
                } else if (sb.length() == 0) {
                    sb.append(msg.trim());
                }
            }
            curr = curr.getCause();
        }
        return sb.length() > 0 ? sb.toString() : t.getClass().getSimpleName();
    }

    private String resolveSmtpUsername(String senderEmail) {
        String configuredUsername = config.getProperty("mail.smtp.username", "").trim();
        if (!configuredUsername.isBlank() && !configuredUsername.equalsIgnoreCase("auto")
                && !configuredUsername.contains("example.com")) {
            return configuredUsername;
        }
        if (senderEmail != null && !senderEmail.isBlank() && senderEmail.contains("@")) {
            return senderEmail.trim();
        }
        throw new IllegalStateException("SMTP username could not be determined. Please set a valid sender email for your account.");
    }

    private String resolveSmtpPassword(String smtpUsername) {
        String configuredPassword = config.getProperty("mail.smtp.password", "").trim();
        if (!configuredPassword.isBlank() && !configuredPassword.equals("your-password")
                && !configuredPassword.equals("your-app-password")) {
            return configuredPassword;
        }

        final String[] passwordHolder = new String[1];
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                passwordHolder[0] = promptForPassword(smtpUsername);
            } else {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    passwordHolder[0] = promptForPassword(smtpUsername);
                });
            }
        } catch (Exception ignored) {
        }

        String entered = passwordHolder[0];
        if (entered == null || entered.isBlank()) {
            throw new IllegalStateException("An App Password is required to send email via SMTP for " + smtpUsername + ".");
        }
        config.setProperty("mail.smtp.password", entered);
        return entered;
    }

    private String promptForPassword(String username) {
        javax.swing.JPasswordField passField = new javax.swing.JPasswordField(20);
        javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(6, 6));
        panel.add(new javax.swing.JLabel("Enter App Password for SMTP (" + username + "):"), java.awt.BorderLayout.NORTH);
        panel.add(passField, java.awt.BorderLayout.CENTER);
        panel.add(new javax.swing.JLabel("<html><font size='2' color='gray'>For Gmail, generate a 16-character App Password in your Google Account security settings.</font></html>"), java.awt.BorderLayout.SOUTH);

        int option = javax.swing.JOptionPane.showConfirmDialog(
                null,
                panel,
                "SMTP Password Required",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE
        );
        if (option == javax.swing.JOptionPane.OK_OPTION) {
            return new String(passField.getPassword()).trim();
        }
        return null;
    }

    private String resolveSmtpHost(String senderEmail) {
        String configuredHost = config.getProperty("mail.smtp.host", "").trim();
        if (!configuredHost.isBlank() && !configuredHost.equalsIgnoreCase("auto")
                && !configuredHost.contains("example.com")) {
            return configuredHost;
        }

        String domain = emailDomain(senderEmail);
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            return "smtp.gmail.com";
        }
        if (domain.equals("outlook.com") || domain.equals("hotmail.com")
                || domain.equals("live.com") || domain.equals("msn.com")) {
            return "smtp.office365.com";
        }
        if (domain.equals("yahoo.com") || domain.equals("yahoo.es")) {
            return "smtp.mail.yahoo.com";
        }
        if (domain.equals("icloud.com") || domain.equals("me.com") || domain.equals("mac.com")) {
            return "smtp.mail.me.com";
        }
        if (domain.equals("proton.me") || domain.equals("protonmail.com")) {
            return "smtp.protonmail.ch";
        }
        return config.getProperty("mail.smtp.fallback.host", "smtp.gmail.com").trim();
    }

    private String resolveSmtpPort(String senderEmail) {
        String configuredPort = config.getProperty("mail.smtp.port", "").trim();
        if (!configuredPort.isBlank() && !configuredPort.equals("587")) {
            return configuredPort;
        }
        String domain = emailDomain(senderEmail);
        return domain.equals("icloud.com") || domain.equals("me.com") || domain.equals("mac.com")
                ? "587" : "587";
    }

    private String emailDomain(String email) {
        if (email == null || !email.contains("@")) {
            throw new IllegalStateException("The sender email must contain a valid domain.");
        }
        return email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase();
    }
}
