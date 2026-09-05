package com.poolapp.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

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
        String smtpHost = resolveSmtpHost(senderEmail);
        String smtpUsername = requiredValue("mail.smtp.username");
        String smtpPassword = requiredValue("mail.smtp.password");
        if (attachment == null || !attachment.isFile()) {
            throw new IllegalArgumentException("Statement PDF attachment was not created.");
        }
        try {
            Properties smtpProperties = new Properties();
            smtpProperties.put("mail.smtp.host", smtpHost);
            smtpProperties.put("mail.smtp.port", resolveSmtpPort(senderEmail));
            smtpProperties.put("mail.smtp.auth", config.getProperty("mail.smtp.auth", "true"));
            smtpProperties.put("mail.smtp.starttls.enable", config.getProperty("mail.smtp.starttls.enable", "true"));

            Session session = Session.getInstance(smtpProperties, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(smtpUsername, smtpPassword);
                }
            });
            MimeMessage message = new MimeMessage(session);
            InternetAddress sender = new InternetAddress(senderEmail, senderName);
            message.setFrom(sender);
            message.setReplyTo(new InternetAddress[]{sender});
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(messageText, "UTF-8");
            MimeBodyPart pdfPart = new MimeBodyPart();
            pdfPart.attachFile(attachment);

            Multipart content = new MimeMultipart();
            content.addBodyPart(textPart);
            content.addBodyPart(pdfPart);
            message.setContent(content);
            Transport.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Unable to send email through SMTP", e);
        }
    }

    private String requiredValue(String key) {
        String value = config.getProperty(key, "").trim();
        if (value.isBlank() || value.contains("example.com") || value.equals("your-password")
                || value.equals("your-app-password")) {
            throw new IllegalStateException("Set " + key + " in config.properties before sending email.");
        }
        return value;
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
