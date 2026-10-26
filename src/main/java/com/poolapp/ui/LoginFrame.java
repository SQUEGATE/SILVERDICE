package com.poolapp.ui;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.MasterDatabaseManager;
import com.poolapp.model.CompanyProfile;
import com.poolapp.model.EmployeeProfile;

import javax.swing.*;
import java.awt.*;

public class LoginFrame extends JFrame {
    private final MasterDatabaseManager masterDatabaseManager;
    private final JTextField usernameField;
    private final JPasswordField passwordField;
    private final JButton loginButton = new JButton("Login");
    private final JLabel status = new JLabel(" ");
    private boolean loggingIn;
    private long loginGeneration;

    public LoginFrame() {
        super("Comp Manager");
        this.masterDatabaseManager = new MasterDatabaseManager();
        AppWindowStyle.apply(this);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(460, 280);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(12, 12));

        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        JLabel titleLabel = new JLabel("Welcome");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 22f));

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        content.add(titleLabel, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1;
        content.add(new JLabel("Username:"), gbc);
        gbc.gridy = 2;
        content.add(new JLabel("Password:"), gbc);

        usernameField = new JTextField(20);
        passwordField = new JPasswordField(20);

        gbc.gridx = 1;
        gbc.gridy = 1;
        content.add(usernameField, gbc);
        gbc.gridy = 2;
        content.add(passwordField, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(status);
        buttonPanel.add(loginButton);

        loginButton.addActionListener(e -> performLogin());
        passwordField.addActionListener(e -> performLogin());

        add(content, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void performLogin() {
        if (loggingIn) return;
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isBlank() || password.isBlank()) {
            JOptionPane.showMessageDialog(this, "Enter your username and password.", "Login", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        loggingIn = true;
        long generation = ++loginGeneration;
        loginButton.setEnabled(false);
        usernameField.setEnabled(false);
        passwordField.setEnabled(false);
        status.setText("Signing in...");
        new SwingWorker<LoginResult, Void>() {
            @Override protected LoginResult doInBackground() {
                MasterDatabaseManager.Authentication auth = masterDatabaseManager.authenticate(username, password);
                if (auth == null || "MASTER".equals(auth.role())) return new LoginResult(auth, null);
                long companyId = auth.company() != null ? auth.company().getId() : auth.employee().getCompanyId();
                java.nio.file.Path path = auth.company() != null ? auth.company().getDatabasePath() : auth.employee().getCompanyDatabasePath();
                DatabaseManager database = masterDatabaseManager.getApiBaseUrl() != null
                        ? masterDatabaseManager.createDatabaseManager(companyId, masterDatabaseManager.getSessionToken())
                        : new DatabaseManager(path);
                return new LoginResult(auth, database);
            }
            @Override protected void done() {
                if (generation != loginGeneration || !isDisplayable()) return;
                try {
                    LoginResult result = get();
                    if (result.authentication() == null) {
                        status.setText("Sign-in failed.");
                        JOptionPane.showMessageDialog(LoginFrame.this, "Invalid username or password.",
                                "Login Failed", JOptionPane.ERROR_MESSAGE);
                    } else {
                        openAccount(result);
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    status.setText("Sign-in failed.");
                    JOptionPane.showMessageDialog(LoginFrame.this, cause.getMessage(), "Login Failed", JOptionPane.ERROR_MESSAGE);
                } finally {
                    loggingIn = false;
                    loginButton.setEnabled(true);
                    usernameField.setEnabled(true);
                    passwordField.setEnabled(true);
                    passwordField.setText("");
                }
            }
        }.execute();
    }

    private record LoginResult(MasterDatabaseManager.Authentication authentication, DatabaseManager database) { }

    private void openAccount(LoginResult result) {
        MasterDatabaseManager.Authentication auth = result.authentication();
        if ("MASTER".equals(auth.role())) {
            SwingUtilities.invokeLater(() -> {
                MasterDashboardFrame frame = new MasterDashboardFrame(masterDatabaseManager);
                frame.setVisible(true);
            });
            dispose();
            return;
        }

        CompanyProfile company = auth.company();
        if (company != null) {
            DatabaseManager companyDatabase = result.database();
            SwingUtilities.invokeLater(() -> {
                final PoolAppFrame[] frameHolder = new PoolAppFrame[1];
                frameHolder[0] = new PoolAppFrame(companyDatabase, company, null, () -> {
                    if (frameHolder[0] != null) {
                        frameHolder[0].dispose();
                    }
                    new LoginFrame().setVisible(true);
                });
                frameHolder[0].setVisible(true);
            });
            dispose();
            return;
        }

        EmployeeProfile employee = auth.employee();
        if (employee != null) {
            DatabaseManager companyDatabase = result.database();
            SwingUtilities.invokeLater(() -> {
                final PoolAppFrame[] frameHolder = new PoolAppFrame[1];
                frameHolder[0] = new PoolAppFrame(companyDatabase, null, employee, null, () -> {
                    if (frameHolder[0] != null) {
                        frameHolder[0].dispose();
                    }
                    new LoginFrame().setVisible(true);
                });
                frameHolder[0].setVisible(true);
            });
            dispose();
            return;
        }

        JOptionPane.showMessageDialog(this, "Invalid username or password.\nServer: "
                + (masterDatabaseManager.getApiBaseUrl() != null ? masterDatabaseManager.getApiBaseUrl() : "local database (not shared)"),
                "Login Failed", JOptionPane.ERROR_MESSAGE);
    }
}
