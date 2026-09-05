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

    public LoginFrame() {
        super("Welcome");
        this.masterDatabaseManager = new MasterDatabaseManager();

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
        JButton loginButton = new JButton("Login");
        buttonPanel.add(loginButton);

        loginButton.addActionListener(e -> performLogin());
        passwordField.addActionListener(e -> performLogin());

        add(content, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void performLogin() {
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isBlank() || password.isBlank()) {
            JOptionPane.showMessageDialog(this, "Enter your username and password.", "Login", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (masterDatabaseManager.authenticateMaster(username, password)) {
            SwingUtilities.invokeLater(() -> {
                MasterDashboardFrame frame = new MasterDashboardFrame(masterDatabaseManager);
                frame.setVisible(true);
            });
            dispose();
            return;
        }

        CompanyProfile company = masterDatabaseManager.authenticateCompany(username, password);
        if (company != null) {
            DatabaseManager companyDatabase = new DatabaseManager(company.getDatabasePath());
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

        EmployeeProfile employee = masterDatabaseManager.authenticateEmployee(username, password);
        if (employee != null) {
            DatabaseManager companyDatabase = new DatabaseManager(employee.getCompanyDatabasePath());
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

        JOptionPane.showMessageDialog(this, "Invalid username or password.", "Login Failed", JOptionPane.ERROR_MESSAGE);
    }
}
