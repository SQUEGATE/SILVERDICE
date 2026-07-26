package com.poolapp.ui;

import com.poolapp.db.MasterDatabaseManager;
import com.poolapp.db.DatabaseManager;
import com.poolapp.model.CompanyProfile;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class MasterDashboardFrame extends JFrame {
    private final MasterDatabaseManager masterDatabaseManager;
    private final DefaultTableModel companyTableModel;
    private final JTable companyTable;
    private final JTextField companySearchField;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JLabel companyNameValue;
    private final JLabel usernameValue;
    private final JLabel passwordValue;
    private final JLabel phoneValue;
    private final JLabel addressValue;
    private CompanyProfile selectedCompany;

    public MasterDashboardFrame(MasterDatabaseManager masterDatabaseManager) {
        super("Master Company Dashboard");
        this.masterDatabaseManager = masterDatabaseManager;
        this.cardLayout = new CardLayout();
        this.cardPanel = new JPanel(cardLayout);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 620);
        setLocationRelativeTo(null);

        companyTableModel = new DefaultTableModel(new Object[]{"ID", "Company", "Username", "Phone", "Address"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        companyTable = new JTable(companyTableModel);
        companyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        companyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedCompanyDetails();
                }
            }
        });
        companySearchField = new JTextField(24);

        companyNameValue = new JLabel("-");
        usernameValue = new JLabel("-");
        passwordValue = new JLabel("-");
        phoneValue = new JLabel("-");
        addressValue = new JLabel("-");

        cardPanel.add(buildCompanyListPanel(), "LIST");
        cardPanel.add(buildCompanyDetailsPanel(), "DETAILS");

        add(cardPanel, BorderLayout.CENTER);
        refreshCompanies();
        cardLayout.show(cardPanel, "LIST");
    }

    private JPanel buildCompanyListPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel title = new JLabel("Companies");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));

        JPanel headerPanel = new JPanel(new BorderLayout(8, 8));
        headerPanel.add(title, BorderLayout.NORTH);

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton searchButton = new JButton("Search");
        JButton clearSearchButton = new JButton("Clear");
        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(companySearchField);
        searchPanel.add(searchButton);
        searchPanel.add(clearSearchButton);

        searchButton.addActionListener(e -> refreshCompanies());
        clearSearchButton.addActionListener(e -> {
            companySearchField.setText("");
            refreshCompanies();
        });
        companySearchField.addActionListener(e -> refreshCompanies());

        headerPanel.add(searchPanel, BorderLayout.SOUTH);
        root.add(headerPanel, BorderLayout.NORTH);

        root.add(new JScrollPane(companyTable), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton addButton = new JButton("Add Company");
        JButton editButton = new JButton("Edit Company");
        JButton deleteButton = new JButton("Delete Company");
        JButton companyViewButton = new JButton("Company View");
        JButton logoutButton = new JButton("Logout");

        addButton.addActionListener(e -> openCompanyEditor(null));
        editButton.addActionListener(e -> openSelectedCompanyEditor());
        deleteButton.addActionListener(e -> deleteSelectedCompany());
        companyViewButton.addActionListener(e -> openCompanySystemView());
        logoutButton.addActionListener(e -> logout());

        buttonPanel.add(addButton);
        buttonPanel.add(editButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(companyViewButton);
        buttonPanel.add(logoutButton);
        root.add(buttonPanel, BorderLayout.SOUTH);

        return root;
    }

    private JPanel buildCompanyDetailsPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel title = new JLabel("Company Details");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        root.add(title, BorderLayout.NORTH);

        JPanel detailPanel = new JPanel(new GridBagLayout());
        detailPanel.setBorder(BorderFactory.createTitledBorder("Company Information"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;

        addDetailRow(detailPanel, gbc, 0, "Company Name:", companyNameValue);
        addDetailRow(detailPanel, gbc, 1, "Username:", usernameValue);
        addDetailRow(detailPanel, gbc, 2, "Password:", passwordValue);
        addDetailRow(detailPanel, gbc, 3, "Phone:", phoneValue);
        addDetailRow(detailPanel, gbc, 4, "Address:", addressValue);

        root.add(detailPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton exitButton = new JButton("Back to Companies");
        JButton editButton = new JButton("Edit Company");
        JButton deleteButton = new JButton("Delete Company");
        JButton viewButton = new JButton("Company View");

        exitButton.addActionListener(e -> showListView());
        editButton.addActionListener(e -> openSelectedCompanyEditor());
        deleteButton.addActionListener(e -> deleteSelectedCompany());
        viewButton.addActionListener(e -> openCompanySystemView());

        buttonPanel.add(editButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(viewButton);
        buttonPanel.add(exitButton);
        root.add(buttonPanel, BorderLayout.SOUTH);

        return root;
    }

    private void addDetailRow(JPanel panel, GridBagConstraints gbc, int row, String label, JLabel valueLabel) {
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD));
        panel.add(valueLabel, gbc);
    }

    private void refreshCompanies() {
        companyTableModel.setRowCount(0);
        String query = companySearchField == null ? "" : companySearchField.getText().trim();
        List<CompanyProfile> companies = query.isBlank()
            ? masterDatabaseManager.getAllCompanies()
            : masterDatabaseManager.searchCompanies(query);
        for (CompanyProfile company : companies) {
            companyTableModel.addRow(new Object[]{
                    company.getId(),
                    company.getCompanyName(),
                    company.getUsername(),
                    company.getPhone(),
                    company.getAddress()
            });
        }
    }

    private CompanyProfile getSelectedCompany() {
        int selectedRow = companyTable.getSelectedRow();
        if (selectedRow < 0) {
            return null;
        }
        Object idValue = companyTableModel.getValueAt(selectedRow, 0);
        if (idValue == null) {
            return null;
        }
        return masterDatabaseManager.getCompanyById(((Number) idValue).longValue());
    }

    private void openSelectedCompanyDetails() {
        selectedCompany = getSelectedCompany();
        if (selectedCompany == null) {
            JOptionPane.showMessageDialog(this, "Select a company first.", "Company View", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        companyNameValue.setText(selectedCompany.getCompanyName());
        usernameValue.setText(selectedCompany.getUsername());
        passwordValue.setText(selectedCompany.getPassword());
        phoneValue.setText(selectedCompany.getPhone());
        addressValue.setText(selectedCompany.getAddress());
        cardLayout.show(cardPanel, "DETAILS");
    }

    private void openCompanySystemView() {
        CompanyProfile company = getSelectedCompany();
        if (company == null) {
            JOptionPane.showMessageDialog(this, "Select a company first.", "Company View", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        DatabaseManager companyDatabase = new DatabaseManager(company.getDatabasePath());
        final PoolAppFrame[] companyFrameHolder = new PoolAppFrame[1];
        companyFrameHolder[0] = new PoolAppFrame(companyDatabase, company, () -> {
            if (companyFrameHolder[0] != null) {
                companyFrameHolder[0].dispose();
            }
            setVisible(true);
            refreshCompanies();
        }, () -> {
            if (companyFrameHolder[0] != null) {
                companyFrameHolder[0].dispose();
            }
            dispose();
            SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
        });
        setVisible(false);
        companyFrameHolder[0].setVisible(true);
    }

    private void showListView() {
        refreshCompanies();
        cardLayout.show(cardPanel, "LIST");
    }

    private void openSelectedCompanyEditor() {
        CompanyProfile company = getSelectedCompany();
        if (company == null) {
            JOptionPane.showMessageDialog(this, "Select a company first.", "Edit Company", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        openCompanyEditor(company);
    }

    private void openCompanyEditor(CompanyProfile existingCompany) {
        CompanyEditorDialog dialog = new CompanyEditorDialog(this, existingCompany);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            masterDatabaseManager.saveCompany(dialog.getCompanyProfile());
            refreshCompanies();
        }
    }

    private void deleteSelectedCompany() {
        CompanyProfile company = getSelectedCompany();
        if (company == null) {
            JOptionPane.showMessageDialog(this, "Select a company first.", "Delete Company", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete company '" + company.getCompanyName() + "'? This removes its customer database too.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            masterDatabaseManager.deleteCompany(company.getId());
            refreshCompanies();
            showListView();
        }
    }

    private void logout() {
        dispose();
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }

    private static class CompanyEditorDialog extends JDialog {
        private final JTextField companyNameField;
        private final JTextField phoneField;
        private final JTextField addressField;
        private final JTextField usernameField;
        private final JPasswordField passwordField;
        private final JPasswordField confirmPasswordField;
        private boolean saved;
        private final CompanyProfile companyProfile;

        CompanyEditorDialog(JFrame parent, CompanyProfile existingCompany) {
            super(parent, existingCompany == null ? "Add Company" : "Edit Company", true);
            this.saved = false;
            this.companyProfile = existingCompany == null ? new CompanyProfile() : cloneCompany(existingCompany);

            setSize(520, 360);
            setLocationRelativeTo(parent);
            setLayout(new BorderLayout(10, 10));

            JPanel formPanel = new JPanel(new GridBagLayout());
            formPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(6, 6, 6, 6);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            companyNameField = new JTextField(companyProfile.getCompanyName() == null ? "" : companyProfile.getCompanyName(), 24);
            phoneField = new JTextField(companyProfile.getPhone() == null ? "" : companyProfile.getPhone(), 24);
            addressField = new JTextField(companyProfile.getAddress() == null ? "" : companyProfile.getAddress(), 24);
            usernameField = new JTextField(companyProfile.getUsername() == null ? "" : companyProfile.getUsername(), 24);
            passwordField = new JPasswordField(24);
            confirmPasswordField = new JPasswordField(24);

            addRow(formPanel, gbc, 0, "Company Name:", companyNameField);
            addRow(formPanel, gbc, 1, "Phone:", phoneField);
            addRow(formPanel, gbc, 2, "Address:", addressField);
            addRow(formPanel, gbc, 3, "Username:", usernameField);
            addRow(formPanel, gbc, 4, "Password:", passwordField);
            addRow(formPanel, gbc, 5, "Confirm Password:", confirmPasswordField);

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton saveButton = new JButton("Save");
            JButton cancelButton = new JButton("Cancel");
            buttonPanel.add(saveButton);
            buttonPanel.add(cancelButton);

            saveButton.addActionListener(e -> saveCompany());
            cancelButton.addActionListener(e -> dispose());

            add(formPanel, BorderLayout.CENTER);
            add(buttonPanel, BorderLayout.SOUTH);
        }

        private void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
            gbc.gridx = 0;
            gbc.gridy = row;
            panel.add(new JLabel(label), gbc);
            gbc.gridx = 1;
            panel.add(field, gbc);
        }

        private void saveCompany() {
            String companyName = companyNameField.getText().trim();
            String phone = phoneField.getText().trim();
            String address = addressField.getText().trim();
            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            String confirmPassword = new String(confirmPasswordField.getPassword());

            if (companyName.isBlank() || username.isBlank()) {
                JOptionPane.showMessageDialog(this, "Company name and username are required.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (companyProfile.getId() == null || !password.isBlank() || !confirmPassword.isBlank()) {
                if (password.isBlank() || confirmPassword.isBlank()) {
                    JOptionPane.showMessageDialog(this, "Enter and confirm the password.", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (!password.equals(confirmPassword)) {
                    JOptionPane.showMessageDialog(this, "Passwords do not match.", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                companyProfile.setPassword(password);
            }

            companyProfile.setCompanyName(companyName);
            companyProfile.setPhone(phone);
            companyProfile.setAddress(address);
            companyProfile.setUsername(username);
            if (companyProfile.getPassword() == null || companyProfile.getPassword().isBlank()) {
                companyProfile.setPassword("company123");
            }
            saved = true;
            dispose();
        }

        private CompanyProfile cloneCompany(CompanyProfile source) {
            return new CompanyProfile(source.getId(), source.getCompanyName(), source.getPhone(), source.getAddress(),
                    source.getUsername(), source.getPassword(), source.getDatabasePath());
        }

        boolean isSaved() {
            return saved;
        }

        CompanyProfile getCompanyProfile() {
            return companyProfile;
        }
    }
}
