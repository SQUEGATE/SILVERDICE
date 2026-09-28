package com.poolapp;

import com.poolapp.ui.LoginFrame;
import com.poolapp.update.UpdateChecker;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LoginFrame loginFrame = new LoginFrame();
            loginFrame.setVisible(true);
            UpdateChecker.checkInBackground(loginFrame);
        });
    }
}
