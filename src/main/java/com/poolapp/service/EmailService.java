package com.poolapp.service;

import java.io.File;
import java.io.IOException;

public class EmailService {
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
}
