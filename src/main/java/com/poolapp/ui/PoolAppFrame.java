package com.poolapp.ui;

import com.poolapp.model.PdfSettings;
import com.poolapp.db.DatabaseManager;
import com.poolapp.db.MasterDatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.model.CompanyProfile;
import com.poolapp.model.EmployeeProfile;
import com.poolapp.model.StatementRecord;
import com.poolapp.service.EmailService;
import com.poolapp.service.SmsService;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public class PoolAppFrame extends JFrame {
    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final String DEBIT_PREFIX = "DEBIT: ";
    private static final String CREDIT_PREFIX = "CREDIT: ";

    private final DatabaseManager dbManager;
    private final EmailService emailService;
    private final SmsService smsService;
    private final JTextField idField;
    private final JTextField firstNameField;
    private final JTextField lastNameField;
    private final JTextField addressField;
    private final JTextField cityField;
    private final JTextField stateField;
    private final JTextField zipField;
    private final JTextField phoneField;
    private final JTextField emailField;
    private final JComboBox<String> dayCombo;
    private final JTextField amountField;
    private final JTextArea notesArea;
    private final JTextField searchField;
    private final JTextField detailSearchField;
    private final JButton prevCustomerButton;
    private final JButton nextCustomerButton;
    private final JComboBox<String> dayFilterCombo;
    private final DefaultTableModel tableModel;
    private final JTable customerTable;
    private final JTextArea revenueArea;
    private final JPanel cardPanel;
    private final JPanel miniTabsPanel;
    private final DefaultListModel<EmployeeProfile> employeeListModel;
    private final JList<EmployeeProfile> employeeList;
    private final JLabel employeeScreenLabel;
    private final JTextField employeeSearchField;
    private final CompanyProfile companyProfile;
    private final EmployeeProfile employeeProfile;
    private final Runnable exitCompanyViewAction;
    private final Runnable logoutAction;
    private final JTextField recordDateField;
    private final JComboBox<String> recordTypeCombo;
    private final JTextField recordAmountField;
    private final JLabel recordCustomerLabel;
    private final JLabel recordBalanceLabel;
    private final JLabel customerBalanceLabel;
    private final DefaultTableModel recordTableModel;
    private final JTable recordTable;
    private final MasterDatabaseManager masterDatabaseManager;
    private List<StatementRecord> currentStatementRecords;
    private String activeRecordCustomerId;
    private final List<Customer> customersById;
    private int currentCustomerIndex = -1;
    private PdfSettings pdfSettings;
    private List<String> creditTypeKeywords;
    private List<String> debitTypeKeywords;

    public PoolAppFrame() {
        this(new DatabaseManager(), null, null, null, null);
    }

    public PoolAppFrame(DatabaseManager dbManager, CompanyProfile companyProfile, Runnable exitCompanyViewAction) {
        this(dbManager, companyProfile, null, exitCompanyViewAction, null);
    }

    public PoolAppFrame(DatabaseManager dbManager, CompanyProfile companyProfile, Runnable exitCompanyViewAction, Runnable logoutAction) {
        this(dbManager, companyProfile, null, exitCompanyViewAction, logoutAction);
    }

    public PoolAppFrame(DatabaseManager dbManager, CompanyProfile companyProfile, EmployeeProfile employeeProfile,
                        Runnable exitCompanyViewAction, Runnable logoutAction) {
        super(resolveWindowTitle(companyProfile, employeeProfile));
        this.dbManager = dbManager;
        this.emailService = new EmailService();
        this.smsService = new SmsService();
        this.companyProfile = companyProfile;
        this.employeeProfile = employeeProfile;
        this.exitCompanyViewAction = exitCompanyViewAction;
        this.logoutAction = logoutAction;

        idField = new JTextField(16);
        firstNameField = new JTextField(20);
        lastNameField = new JTextField(20);
        addressField = new JTextField(24);
        cityField = new JTextField(14);
        stateField = new JTextField(10);
        zipField = new JTextField(8);
        phoneField = new JTextField(14);
        emailField = new JTextField(40);
        dayCombo = new JComboBox<>(DAYS);
        amountField = new JTextField(10);
        notesArea = new JTextArea(6, 28);
        searchField = new JTextField(22);
        detailSearchField = new JTextField(18);
        prevCustomerButton = new JButton("Previous");
        nextCustomerButton = new JButton("Next");
        prevCustomerButton.setPreferredSize(new Dimension(100, 28));
        nextCustomerButton.setPreferredSize(new Dimension(100, 28));
        recordDateField = new JTextField(LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy")), 10);
        recordTypeCombo = new JComboBox<>();
        recordTypeCombo.setEditable(true);
        recordAmountField = new JTextField(10);
        recordCustomerLabel = new JLabel("No customer selected");
        recordBalanceLabel = new JLabel("Current Balance: $0.00");
        customerBalanceLabel = new JLabel("Current Balance: $0.00");
        recordTableModel = new DefaultTableModel(new Object[]{"Date", "Type", "Amount"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        recordTable = new JTable(recordTableModel);
        employeeListModel = new DefaultListModel<>();
        employeeList = new JList<>(employeeListModel);
        employeeScreenLabel = new JLabel("Employees");
        employeeSearchField = new JTextField(22);
        masterDatabaseManager = new MasterDatabaseManager();
        currentStatementRecords = new ArrayList<>();
        activeRecordCustomerId = "";
        customersById = new ArrayList<>();
        pdfSettings = loadPdfSettings();
        loadRecordTypeKeywords();

        ((AbstractDocument) zipField.getDocument()).setDocumentFilter(new DigitFilter(10));
        ((AbstractDocument) amountField.getDocument()).setDocumentFilter(new CurrencyFilter());
        ((AbstractDocument) recordAmountField.getDocument()).setDocumentFilter(new CurrencyFilter());
        recordAmountField.setText("$0.00");
        recordAmountField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                String current = recordAmountField.getText().trim();
                if (current.startsWith("$")) {
                    recordAmountField.setText(current.substring(1));
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                formatRecordAmountField();
            }
        });
        emailField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String email = emailField.getText().trim();
                if (!email.isBlank() && !EMAIL_PATTERN.matcher(email).matches()) {
                    JOptionPane.showMessageDialog(PoolAppFrame.this, "Please enter a valid email address.", "Invalid Email", JOptionPane.WARNING_MESSAGE);
                    emailField.requestFocus();
                }
            }
        });
        phoneField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                formatPhoneField();
            }
        });
        amountField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                String current = amountField.getText().trim();
                if (current.startsWith("$")) {
                    amountField.setText(current.substring(1));
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                formatAmountField();
            }
        });
        amountField.setText("$0.00");
        dayFilterCombo = new JComboBox<>();
        tableModel = new DefaultTableModel(new Object[]{"ID", "First Name", "Last Name", "Day", "Amount", "Phone", "Email"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        customerTable = new JTable(tableModel);
        revenueArea = new JTextArea(10, 32);
        revenueArea.setEditable(false);
        cardPanel = new JPanel(new CardLayout());
        miniTabsPanel = new JPanel(new GridLayout(2, 1, 6, 6));

        initComponents();
        loadCustomers(filterAccessibleCustomers(dbManager.getAllCustomers()));
        refreshRevenueSummary();
        refreshDetailCustomerList();
    }

    private static String resolveWindowTitle(CompanyProfile companyProfile, EmployeeProfile employeeProfile) {
        String companyName = companyProfile != null ? companyProfile.getCompanyName()
                : employeeProfile != null ? employeeProfile.getCompanyName() : null;
        if (companyName == null || companyName.isBlank()) {
            return "Pool Service Customer Manager";
        }
        return companyName + " - Pool Service Customer Manager";
    }

    private void initComponents() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);

        idField.setEditable(false);
        setNewCustomerId();
        dayFilterCombo.addItem("All");
        for (String day : DAYS) {
            dayFilterCombo.addItem(day);
        }

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createTitledBorder("Customer Details"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.WEST;

        int row = 0;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("Customer ID:"), gc);
        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(idField, gc);
        gc.gridwidth = 1;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("Search Name:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(detailSearchField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        gc.gridx = 2;
        JButton detailSearchButton = new JButton("Find");
        formPanel.add(detailSearchButton, gc);
        gc.gridx = 3;
        JPanel detailNavPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        Dimension navButtonSize = new Dimension(110, 28);
        prevCustomerButton.setPreferredSize(navButtonSize);
        prevCustomerButton.setMinimumSize(navButtonSize);
        prevCustomerButton.setMaximumSize(navButtonSize);
        nextCustomerButton.setPreferredSize(navButtonSize);
        nextCustomerButton.setMinimumSize(navButtonSize);
        nextCustomerButton.setMaximumSize(navButtonSize);
        detailNavPanel.add(prevCustomerButton);
        detailNavPanel.add(nextCustomerButton);
        formPanel.add(detailNavPanel, gc);

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("First Name:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(firstNameField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        gc.gridx = 2;
        formPanel.add(new JLabel("Last Name:"), gc);
        gc.gridx = 3;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(lastNameField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("Address:"), gc);
        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(addressField, gc);
        gc.gridwidth = 1;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("City:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(cityField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        gc.gridx = 2;
        formPanel.add(new JLabel("State:"), gc);
        gc.gridx = 3;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(stateField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("Zip Code:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(zipField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("Phone:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 0.8;
        formPanel.add(phoneField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        gc.gridx = 2;
        formPanel.add(new JLabel("Email:"), gc);
        gc.gridx = 3;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.2;
        formPanel.add(emailField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        formPanel.add(new JLabel("Service Day:"), gc);
        gc.gridx = 1;
        formPanel.add(dayCombo, gc);

        gc.gridx = 2;
        formPanel.add(new JLabel("Amount Charged:"), gc);
        gc.gridx = 3;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        formPanel.add(amountField, gc);
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;

        row++;
        gc.gridx = 0;
        gc.gridy = row;
        gc.anchor = GridBagConstraints.NORTHWEST;
        formPanel.add(new JLabel("Notes:"), gc);
        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1.0;
        gc.weighty = 1.0;
        JScrollPane notesScroll = new JScrollPane(notesArea);
        formPanel.add(notesScroll, gc);
        gc.gridwidth = 1;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        gc.weighty = 0;

        row++;
        gc.gridx = 1;
        gc.gridy = row;
        JButton saveButton = new JButton("Save / Update");
        JButton clearButton = new JButton("New Record");
        formPanel.add(saveButton, gc);
        gc.gridx = 2;
        formPanel.add(clearButton, gc);

        JPanel searchPanel = new JPanel(new GridBagLayout());
        searchPanel.setBorder(BorderFactory.createTitledBorder("Search & Filter"));
        GridBagConstraints sp = new GridBagConstraints();
        sp.insets = new Insets(4, 4, 4, 4);
        sp.anchor = GridBagConstraints.WEST;

        sp.gridx = 0;
        sp.gridy = 0;
        searchPanel.add(new JLabel("Search by name:"), sp);

        sp.gridx = 1;
        sp.weightx = 1.0;
        sp.fill = GridBagConstraints.HORIZONTAL;
        searchPanel.add(searchField, sp);

        JButton searchButton = new JButton("Search");
        JButton clearFilterButton = new JButton("Clear");
        searchField.setColumns(18);

        sp.gridx = 2;
        sp.weightx = 0;
        sp.fill = GridBagConstraints.NONE;
        searchPanel.add(searchButton, sp);

        sp.gridx = 3;
        searchPanel.add(clearFilterButton, sp);

        sp.gridx = 0;
        sp.gridy = 1;
        searchPanel.add(new JLabel("Group by day:"), sp);

        sp.gridx = 1;
        dayFilterCombo.setPreferredSize(new Dimension(130, 24));
        searchPanel.add(dayFilterCombo, sp);

        customerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tableScroll = new JScrollPane(customerTable);

        JPanel tablePanel = new JPanel(new BorderLayout(8, 8));
        tablePanel.setBorder(BorderFactory.createTitledBorder("Customers"));
        tablePanel.add(searchPanel, BorderLayout.NORTH);
        tablePanel.add(tableScroll, BorderLayout.CENTER);
        JPanel customersBalancePanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
        customersBalancePanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        customersBalancePanel.add(customerBalanceLabel);
        tablePanel.add(customersBalancePanel, BorderLayout.SOUTH);

        JPanel recordPanel = new JPanel(new GridBagLayout());
        recordPanel.setBorder(BorderFactory.createTitledBorder("Statement & Invoice Records"));
        GridBagConstraints rc = new GridBagConstraints();
        rc.insets = new Insets(6, 6, 6, 6);
        rc.anchor = GridBagConstraints.WEST;

        rc.gridx = 0;
        rc.gridy = 0;
        recordPanel.add(new JLabel("Customer:"), rc);
        rc.gridx = 1;
        rc.gridwidth = 3;
        rc.fill = GridBagConstraints.HORIZONTAL;
        rc.weightx = 1.0;
        recordPanel.add(recordCustomerLabel, rc);
        rc.gridwidth = 1;
        rc.fill = GridBagConstraints.NONE;
        rc.weightx = 0;

        rc.gridy = 1;
        rc.gridx = 0;
        recordPanel.add(new JLabel("Date:"), rc);
        rc.gridx = 1;
        rc.fill = GridBagConstraints.HORIZONTAL;
        rc.weightx = 0.5;
        recordPanel.add(recordDateField, rc);
        rc.fill = GridBagConstraints.NONE;
        rc.weightx = 0;

        rc.gridx = 2;
        recordPanel.add(new JLabel("Type:"), rc);
        rc.gridx = 3;
        rc.fill = GridBagConstraints.HORIZONTAL;
        rc.weightx = 0.5;
        recordPanel.add(recordTypeCombo, rc);
        rc.fill = GridBagConstraints.NONE;
        rc.weightx = 0;

        rc.gridy = 2;
        rc.gridx = 2;
        JButton addTypeButton = new JButton("Add Type");
        recordPanel.add(addTypeButton, rc);
        rc.gridx = 3;
        JButton keywordButton = new JButton("Keywords");
        recordPanel.add(keywordButton, rc);
        rc.gridx = 0;
        rc.gridy = 3;
        recordPanel.add(new JLabel("Amount:"), rc);
        rc.gridx = 1;
        rc.fill = GridBagConstraints.HORIZONTAL;
        rc.weightx = 0.5;
        recordPanel.add(recordAmountField, rc);
        rc.fill = GridBagConstraints.NONE;
        rc.weightx = 0;

        JButton addRecordButton = new JButton("Add Record");
        JButton deleteRecordButton = new JButton("Delete Record");
        rc.gridx = 2;
        rc.gridwidth = 1;
        rc.fill = GridBagConstraints.HORIZONTAL;
        rc.weightx = 0.5;
        recordPanel.add(addRecordButton, rc);
        rc.gridx = 3;
        rc.weightx = 0.5;
        recordPanel.add(deleteRecordButton, rc);
        rc.gridwidth = 1;
        rc.fill = GridBagConstraints.NONE;
        rc.weightx = 0;

        rc.gridy = 4;
        rc.gridx = 0;
        rc.gridwidth = 4;
        rc.fill = GridBagConstraints.HORIZONTAL;
        recordPanel.add(recordBalanceLabel, rc);
        rc.gridwidth = 1;
        rc.fill = GridBagConstraints.NONE;

        rc.gridy = 5;
        rc.gridx = 0;
        rc.gridwidth = 4;
        rc.fill = GridBagConstraints.BOTH;
        rc.weightx = 1.0;
        rc.weighty = 1.0;
        recordPanel.add(new JScrollPane(recordTable), rc);
        rc.gridwidth = 1;
        rc.weightx = 0;
        rc.weighty = 0;
        rc.fill = GridBagConstraints.NONE;

        // Add action buttons row
        rc.gridy = 6;
        rc.gridx = 0;
        rc.gridwidth = 4;
        rc.fill = GridBagConstraints.HORIZONTAL;
        rc.weightx = 1.0;
        
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        JButton statementButton = new JButton("Generate Statement");
        JButton emailButton = new JButton("Send Email");
        JButton smsButton = new JButton("Send SMS");
        JButton backupButton = new JButton("Backup DB");
        JButton restoreButton = new JButton("Restore DB");
        actionPanel.add(statementButton);
        actionPanel.add(emailButton);
        actionPanel.add(smsButton);
        actionPanel.add(backupButton);
        actionPanel.add(restoreButton);
        recordPanel.add(actionPanel, rc);
        
        rc.gridwidth = 1;
        rc.fill = GridBagConstraints.NONE;
        rc.weightx = 0;

        addRecordButton.addActionListener(e -> addStatementRecord());
        deleteRecordButton.addActionListener(e -> deleteSelectedRecord());
        addTypeButton.addActionListener(e -> addCustomRecordType());
        keywordButton.addActionListener(e -> manageRecordTypeKeywords());
        statementButton.addActionListener(e -> generateStatement());
        emailButton.addActionListener(e -> sendEmailToCustomer());
        smsButton.addActionListener(e -> sendSmsToCustomer());
        backupButton.addActionListener(e -> backupDatabase());
        restoreButton.addActionListener(e -> restoreDatabase());

        JPanel revenuePanel = new JPanel(new BorderLayout(6, 6));
        revenuePanel.setBorder(BorderFactory.createTitledBorder("Revenue Summary"));
        revenuePanel.add(new JScrollPane(revenueArea), BorderLayout.CENTER);

        JPanel employeesPanel = createEmployeesPanel();

        JPanel navigationPanel = new JPanel(new GridLayout(0, 1, 12, 12));
        navigationPanel.setBorder(BorderFactory.createTitledBorder("View Options"));
        JButton customerDetailsButton = new JButton("Customer Details");
        JButton customersButton = new JButton("Customers");
        JButton statementsButton = new JButton("Statements");
        JButton revenueSummaryButton = new JButton("Revenue Summary");
        JButton pdfButton = new JButton("PDF");
        JButton employeesButton = new JButton("Employees");
        customerDetailsButton.setFont(customerDetailsButton.getFont().deriveFont(Font.BOLD, 14f));
        customersButton.setFont(customersButton.getFont().deriveFont(Font.BOLD, 14f));
        statementsButton.setFont(statementsButton.getFont().deriveFont(Font.BOLD, 14f));
        revenueSummaryButton.setFont(revenueSummaryButton.getFont().deriveFont(Font.BOLD, 14f));
        pdfButton.setFont(pdfButton.getFont().deriveFont(Font.BOLD, 14f));
        employeesButton.setFont(employeesButton.getFont().deriveFont(Font.BOLD, 14f));
        if (canAccessScreen("CustomerDetails")) {
            navigationPanel.add(customerDetailsButton);
        }
        if (canAccessScreen("Customers")) {
            navigationPanel.add(customersButton);
        }
        if (canAccessScreen("Statements")) {
            navigationPanel.add(statementsButton);
        }
        if (canAccessScreen("RevenueSummary")) {
            navigationPanel.add(revenueSummaryButton);
        }
        if (canAccessScreen("PDF")) {
            navigationPanel.add(pdfButton);
        }
        if (canAccessScreen("Employees")) {
            navigationPanel.add(employeesButton);
        }

        cardPanel.add(formPanel, "CustomerDetails");
        cardPanel.add(tablePanel, "Customers");
        cardPanel.add(recordPanel, "Statements");
        cardPanel.add(revenuePanel, "RevenueSummary");
        cardPanel.add(createPdfPanel(), "PDF");
        cardPanel.add(employeesPanel, "Employees");

        JPanel contentPanel = new JPanel(new BorderLayout(8, 8));
        contentPanel.add(cardPanel, BorderLayout.CENTER);
        miniTabsPanel.setBorder(BorderFactory.createTitledBorder("Quick Switch"));
        contentPanel.add(miniTabsPanel, BorderLayout.EAST);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigationPanel, contentPanel);
        splitPane.setDividerLocation(220);
        JPanel rootPanel = new JPanel(new BorderLayout());
        if (companyProfile != null || employeeProfile != null || exitCompanyViewAction != null || logoutAction != null) {
            JPanel topBar = new JPanel(new BorderLayout());
            topBar.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            String contextLabel = companyProfile != null ? "Company: " + companyProfile.getCompanyName()
                    : employeeProfile != null ? "Employee: " + employeeProfile.getFullName() + " - " + employeeProfile.getCompanyName()
                    : "Company View";
            JLabel companyLabel = new JLabel(contextLabel);
            companyLabel.setFont(companyLabel.getFont().deriveFont(Font.BOLD, 14f));
            topBar.add(companyLabel, BorderLayout.WEST);
            JPanel topBarButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            if (exitCompanyViewAction != null) {
                JButton exitCompanyViewButton = new JButton(employeeProfile != null ? "Exit Employee View" : "Exit Company View");
                exitCompanyViewButton.addActionListener(e -> exitCompanyViewAction.run());
                topBarButtons.add(exitCompanyViewButton);
            }
            if (logoutAction != null) {
                JButton logoutButton = new JButton("Logout");
                logoutButton.addActionListener(e -> logoutAction.run());
                topBarButtons.add(logoutButton);
            }
            topBar.add(topBarButtons, BorderLayout.EAST);
            rootPanel.add(topBar, BorderLayout.NORTH);
        }
        rootPanel.add(splitPane, BorderLayout.CENTER);
        getContentPane().add(rootPanel, BorderLayout.CENTER);

        customerDetailsButton.addActionListener(e -> showScreen("CustomerDetails"));
        customersButton.addActionListener(e -> showScreen("Customers"));
        statementsButton.addActionListener(e -> showScreen("Statements"));
        revenueSummaryButton.addActionListener(e -> showScreen("RevenueSummary"));
        pdfButton.addActionListener(e -> showScreen("PDF"));
        employeesButton.addActionListener(e -> showScreen("Employees"));

        detailSearchButton.addActionListener(e -> searchCustomerInDetails());
        prevCustomerButton.addActionListener(e -> showPreviousCustomer());
        nextCustomerButton.addActionListener(e -> showNextCustomer());

        // Load record types from database
        loadRecordTypesFromDatabase();
        showScreen(getDefaultAccessibleScreen());

        saveButton.addActionListener(e -> saveCustomer());
        clearButton.addActionListener(e -> clearForm());
        searchButton.addActionListener(e -> loadFilteredCustomers());
        clearFilterButton.addActionListener(e -> resetFilters());
        dayFilterCombo.addActionListener(e -> loadFilteredCustomers());

        customerTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent event) {
                if (!event.getValueIsAdjusting()) {
                    populateFormFromSelection();
                }
            }
        });

        customerTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && customerTable.getSelectedRow() != -1) {
                    int selectedRow = customerTable.convertRowIndexToModel(customerTable.getSelectedRow());
                    String customerId = Objects.toString(customerTable.getModel().getValueAt(selectedRow, 0), "");
                    openStatementForCustomer(customerId);
                }
            }
        });

        applyCustomerEditPermissions(saveButton, clearButton);
        applyStatementEditPermissions(recordDateField, recordTypeCombo, recordAmountField, addRecordButton, deleteRecordButton, addTypeButton, keywordButton);
    }

    private void showScreen(String screenName) {
        if (!canAccessScreen(screenName)) {
            screenName = getDefaultAccessibleScreen();
        }
        if ("Employees".equals(screenName)) {
            refreshEmployeesScreen();
        }
        CardLayout layout = (CardLayout) cardPanel.getLayout();
        layout.show(cardPanel, screenName);
        
        // Reload PDF settings and refresh PDF panel when switching to PDF tab
        if ("PDF".equals(screenName)) {
            pdfSettings = loadPdfSettings();
            // Trigger repaint to show updated settings in preview
            cardPanel.repaint();
        }
        
        if ("Statements".equals(screenName) && activeRecordCustomerId != null && !activeRecordCustomerId.isBlank()) {
            loadStatementRecordsForCustomer(activeRecordCustomerId);
        }
        updateMiniTabs(screenName);
    }

    private void updateMiniTabs(String currentScreen) {
        miniTabsPanel.removeAll();
        miniTabsPanel.setLayout(new GridLayout(0, 1, 6, 6));

        if (canAccessScreen("CustomerDetails") && !"CustomerDetails".equals(currentScreen)) {
            JButton tab = new JButton("Customer Details");
            tab.addActionListener(e -> showScreen("CustomerDetails"));
            miniTabsPanel.add(tab);
        }
        if (canAccessScreen("Customers") && !"Customers".equals(currentScreen)) {
            JButton tab = new JButton("Customers");
            tab.addActionListener(e -> showScreen("Customers"));
            miniTabsPanel.add(tab);
        }
        if (canAccessScreen("RevenueSummary") && !"RevenueSummary".equals(currentScreen)) {
            JButton tab = new JButton("Revenue Summary");
            tab.addActionListener(e -> showScreen("RevenueSummary"));
            miniTabsPanel.add(tab);
        }
        if (canAccessScreen("Statements") && !"Statements".equals(currentScreen)) {
            JButton tab = new JButton("Statements");
            tab.addActionListener(e -> showScreen("Statements"));
            miniTabsPanel.add(tab);
        }
        if (canAccessScreen("PDF") && !"PDF".equals(currentScreen)) {
            JButton tab = new JButton("PDF");
            tab.addActionListener(e -> showScreen("PDF"));
            miniTabsPanel.add(tab);
        }
        if (canAccessScreen("Employees") && !"Employees".equals(currentScreen)) {
            JButton tab = new JButton("Employees");
            tab.addActionListener(e -> showScreen("Employees"));
            miniTabsPanel.add(tab);
        }

        miniTabsPanel.revalidate();
        miniTabsPanel.repaint();
    }

    private void refreshDetailCustomerList() {
        customersById.clear();
        customersById.addAll(filterAccessibleCustomers(dbManager.getAllCustomersOrderedById()));
        if (customersById.isEmpty()) {
            currentCustomerIndex = -1;
        } else if (currentCustomerIndex < 0 || currentCustomerIndex >= customersById.size()) {
            currentCustomerIndex = 0;
        }
        updateNavigationButtons();
    }

    private void showCustomerByIndex(int index) {
        if (customersById.isEmpty() || index < 0 || index >= customersById.size()) {
            return;
        }
        currentCustomerIndex = index;
        populateFieldsWithCustomer(customersById.get(index));
        updateNavigationButtons();
    }

    private void selectCustomerById(String customerId) {
        refreshDetailCustomerList();
        for (int i = 0; i < customersById.size(); i++) {
            if (customerId.equals(customersById.get(i).getId())) {
                showCustomerByIndex(i);
                return;
            }
        }
    }

    private void showPreviousCustomer() {
        if (customersById.isEmpty()) {
            return;
        }
        int previousIndex = currentCustomerIndex <= 0 ? customersById.size() - 1 : currentCustomerIndex - 1;
        showCustomerByIndex(previousIndex);
    }

    private void showNextCustomer() {
        if (customersById.isEmpty()) {
            return;
        }
        int nextIndex = currentCustomerIndex >= customersById.size() - 1 ? 0 : currentCustomerIndex + 1;
        showCustomerByIndex(nextIndex);
    }

    private void updateNavigationButtons() {
        boolean enabled = !customersById.isEmpty();
        prevCustomerButton.setEnabled(enabled);
        nextCustomerButton.setEnabled(enabled);
    }

    private void populateFieldsWithCustomer(Customer customer) {
        idField.setText(customer.getId());
        firstNameField.setText(customer.getFirstName());
        lastNameField.setText(customer.getLastName());
        addressField.setText(customer.getAddress());
        cityField.setText(customer.getCity());
        stateField.setText(customer.getState());
        zipField.setText(customer.getZip());
        phoneField.setText(customer.getPhone());
        emailField.setText(customer.getEmail());
        dayCombo.setSelectedItem(customer.getServiceDay());
        amountField.setText("$" + customer.getAmountCharged().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        notesArea.setText(customer.getNotes());
    }

    private void searchCustomerInDetails() {
        String query = detailSearchField.getText().trim();
        if (query.isBlank()) {
            JOptionPane.showMessageDialog(this, "Please enter a first or last name to search.", "Search Required", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Customer> matches = dbManager.searchCustomersByName(query);
        matches = filterAccessibleCustomers(matches);
        if (matches.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No customer found for that search.", "Not Found", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        selectCustomerById(matches.get(0).getId());
        showScreen("CustomerDetails");
    }

    private void loadCustomers(List<Customer> customers) {
        tableModel.setRowCount(0);
        for (Customer customer : filterAccessibleCustomers(customers)) {
            String amountDisplay = "$" + customer.getAmountCharged().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            tableModel.addRow(new Object[]{
                    customer.getId(),
                    customer.getFirstName(),
                    customer.getLastName(),
                    customer.getServiceDay(),
                    amountDisplay,
                    customer.getPhone(),
                    customer.getEmail()
            });
        }
    }

    private void loadFilteredCustomers() {
        String search = searchField.getText().trim();
        String selectedDay = Objects.toString(dayFilterCombo.getSelectedItem(), "All");

        List<Customer> customers;
        if (search.isBlank()) {
            customers = dbManager.getAllCustomers();
        } else {
            customers = dbManager.searchCustomers(search);
        }

        customers = filterAccessibleCustomers(customers);

        if (!"All".equals(selectedDay)) {
            customers.removeIf(customer -> !selectedDay.equals(customer.getServiceDay()));
        }

        loadCustomers(customers);
    }

    private void resetFilters() {
        searchField.setText("");
        dayFilterCombo.setSelectedIndex(0);
        loadCustomers(filterAccessibleCustomers(dbManager.getAllCustomers()));
    }

    private void populateFormFromSelection() {
        int selectedRow = customerTable.getSelectedRow();
        if (selectedRow < 0) {
            return;
        }
        String customerId = Objects.toString(tableModel.getValueAt(selectedRow, 0), "");
        if (!customerId.isBlank()) {
            Customer customer = dbManager.getCustomerById(customerId);
            if (customer != null && canAccessCustomer(customer)) {
                populateFieldsWithCustomer(customer);
                selectCustomerById(customerId);
                updateCustomerBalanceLabel(customerId);
                activeRecordCustomerId = customerId;
                return;
            }
        }
    }

    private boolean canAccessScreen(String screenName) {
        if (employeeProfile == null) {
            return true;
        }
        switch (screenName) {
            case "CustomerDetails":
                return employeeProfile.isCanViewCustomerDetails();
            case "Customers":
                return employeeProfile.isCanViewCustomers();
            case "Statements":
                return employeeProfile.isCanViewStatements();
            case "RevenueSummary":
                return employeeProfile.isCanViewRevenueSummary();
            case "PDF":
                return employeeProfile.isCanViewPdf();
            case "Employees":
                return false;
            default:
                return false;
        }
    }

    private String getDefaultAccessibleScreen() {
        String[] screens = {"CustomerDetails", "Customers", "Statements", "RevenueSummary", "PDF", "Employees"};
        for (String screen : screens) {
            if (canAccessScreen(screen)) {
                return screen;
            }
        }
        return "CustomerDetails";
    }

    private boolean canEditCustomerData() {
        return employeeProfile == null || employeeProfile.isCanEditCustomers();
    }

    private boolean canEditStatementData() {
        return employeeProfile == null || employeeProfile.isCanEditStatements();
    }

    private boolean canEditPdfData() {
        return employeeProfile == null || employeeProfile.isCanEditPdf();
    }

    private void applyCustomerEditPermissions(JButton saveButton, JButton clearButton) {
        if (canEditCustomerData()) {
            return;
        }
        saveButton.setEnabled(false);
        clearButton.setEnabled(false);
        firstNameField.setEditable(false);
        lastNameField.setEditable(false);
        addressField.setEditable(false);
        cityField.setEditable(false);
        stateField.setEditable(false);
        zipField.setEditable(false);
        phoneField.setEditable(false);
        emailField.setEditable(false);
        dayCombo.setEnabled(false);
        amountField.setEditable(false);
        notesArea.setEditable(false);
    }

    private void applyStatementEditPermissions(JTextField dateField, JComboBox<String> typeCombo, JTextField amountField,
                                               JButton addRecordButton, JButton deleteRecordButton, JButton addTypeButton, JButton keywordButton) {
        if (canEditStatementData()) {
            return;
        }
        dateField.setEditable(false);
        typeCombo.setEnabled(false);
        amountField.setEditable(false);
        addRecordButton.setEnabled(false);
        deleteRecordButton.setEnabled(false);
        addTypeButton.setEnabled(false);
        keywordButton.setEnabled(false);
    }

    private JPanel createEmployeesPanel() {
        JPanel employeesPanel = new JPanel(new BorderLayout(8, 8));
        employeesPanel.setBorder(BorderFactory.createTitledBorder("Employees"));

        employeeScreenLabel.setFont(employeeScreenLabel.getFont().deriveFont(Font.BOLD, 16f));
        JPanel headerPanel = new JPanel(new BorderLayout(8, 8));
        headerPanel.add(employeeScreenLabel, BorderLayout.NORTH);

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton searchButton = new JButton("Search");
        JButton clearButton = new JButton("Clear");
        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(employeeSearchField);
        searchPanel.add(searchButton);
        searchPanel.add(clearButton);
        searchButton.addActionListener(e -> refreshEmployeesScreen());
        clearButton.addActionListener(e -> {
            employeeSearchField.setText("");
            refreshEmployeesScreen();
        });
        employeeSearchField.addActionListener(e -> refreshEmployeesScreen());
        headerPanel.add(searchPanel, BorderLayout.SOUTH);
        employeesPanel.add(headerPanel, BorderLayout.NORTH);

        employeeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        employeesPanel.add(new JScrollPane(employeeList), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton addButton = new JButton("Add Employee");
        JButton editButton = new JButton("Edit Employee");
        JButton deleteButton = new JButton("Delete Employee");
        JButton customerViewButton = new JButton("Customer View");
        JButton refreshButton = new JButton("Refresh");

        addButton.addActionListener(e -> openEmployeeEditor(null));
        editButton.addActionListener(e -> openEmployeeEditor(employeeList.getSelectedValue()));
        deleteButton.addActionListener(e -> deleteSelectedEmployee());
        customerViewButton.addActionListener(e -> openEmployeeCustomerView());
        refreshButton.addActionListener(e -> refreshEmployeesScreen());

        buttonPanel.add(addButton);
        buttonPanel.add(editButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(customerViewButton);
        buttonPanel.add(refreshButton);
        employeesPanel.add(buttonPanel, BorderLayout.SOUTH);

        return employeesPanel;
    }

    private void refreshEmployeesScreen() {
        employeeListModel.clear();
        if (companyProfile == null || companyProfile.getId() == null) {
            employeeScreenLabel.setText("Employees are only available for company accounts.");
            return;
        }
        employeeScreenLabel.setText("Employees for " + companyProfile.getCompanyName());
        String query = employeeSearchField.getText().trim().toLowerCase(Locale.ROOT);
        for (EmployeeProfile employee : masterDatabaseManager.getEmployeesForCompany(companyProfile.getId())) {
            if (query.isBlank() || matchesEmployeeSearch(employee, query)) {
                employeeListModel.addElement(employee);
            }
        }
    }

    private boolean matchesEmployeeSearch(EmployeeProfile employee, String query) {
        return String.valueOf(employee.getEmployeeId()).contains(query)
                || valueOrEmpty(employee.getFirstName()).toLowerCase(Locale.ROOT).contains(query)
                || valueOrEmpty(employee.getLastName()).toLowerCase(Locale.ROOT).contains(query)
                || valueOrEmpty(employee.getPhone()).toLowerCase(Locale.ROOT).contains(query)
                || valueOrEmpty(employee.getEmail()).toLowerCase(Locale.ROOT).contains(query)
                || valueOrEmpty(employee.getUsername()).toLowerCase(Locale.ROOT).contains(query);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private void openEmployeeEditor(EmployeeProfile selectedEmployee) {
        if (companyProfile == null || companyProfile.getId() == null) {
            JOptionPane.showMessageDialog(this, "Employee management is only available for company accounts.", "Employees", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        EmployeeProfile employee = selectedEmployee == null ? new EmployeeProfile() : cloneEmployee(selectedEmployee);
        if (selectedEmployee == null) {
            employee.setCompanyId(companyProfile.getId());
            employee.setCompanyName(companyProfile.getCompanyName());
            employee.setCompanyDatabasePath(dbManager.getDatabasePath());
            employee.setCanViewCustomerDetails(true);
            employee.setCanViewCustomers(true);
            employee.setCanViewStatements(true);
            employee.setCanViewRevenueSummary(false);
            employee.setCanViewPdf(false);
            employee.setCanEditPdf(false);
            employee.setCanEditCustomers(false);
            employee.setCanEditStatements(false);
        }
        EmployeeEditorDialog editor = new EmployeeEditorDialog(this, companyProfile, employee, dbManager.getAllCustomersOrderedById());
        editor.setVisible(true);
        if (editor.isSaved()) {
            masterDatabaseManager.saveEmployee(editor.getEmployeeProfile());
            refreshEmployeesScreen();
        }
    }

    private void deleteSelectedEmployee() {
        EmployeeProfile selected = employeeList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select an employee first.", "Employees", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete employee '" + selected.getFullName() + "'?",
                "Delete Employee", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            masterDatabaseManager.deleteEmployee(selected.getEmployeeId(), companyProfile.getId());
            refreshEmployeesScreen();
        }
    }

    private void openEmployeeCustomerView() {
        EmployeeProfile selected = employeeList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select an employee first.", "Customer View", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        DatabaseManager employeeDatabase = new DatabaseManager(selected.getCompanyDatabasePath());
        final PoolAppFrame[] previewFrameHolder = new PoolAppFrame[1];
        Runnable returnToCompanyView = () -> {
            if (previewFrameHolder[0] != null) {
                previewFrameHolder[0].dispose();
            }
            setVisible(true);
            showScreen("Employees");
            refreshEmployeesScreen();
        };

        previewFrameHolder[0] = new PoolAppFrame(employeeDatabase, null, selected, returnToCompanyView, returnToCompanyView);
        previewFrameHolder[0].setBounds(getBounds());
        previewFrameHolder[0].setExtendedState(getExtendedState());
        setVisible(false);
        previewFrameHolder[0].setVisible(true);
    }

    private void reconcileStatementRecordAmountsForCurrentTypeRules() {
        for (String type : dbManager.getDistinctStatementRecordTypes()) {
            dbManager.normalizeStatementRecordAmountsForType(type, classifyRecordType(type) == RecordTypeCategory.DEBIT);
        }
        if (activeRecordCustomerId != null && !activeRecordCustomerId.isBlank()) {
            loadStatementRecordsForCustomer(activeRecordCustomerId);
        }
        refreshRevenueSummary();
        updateCustomerBalanceLabel(idField.getText().trim());
    }

    private List<Customer> filterAccessibleCustomers(List<Customer> customers) {
        if (employeeProfile == null) {
            return new ArrayList<>(customers);
        }
        List<Customer> filtered = new ArrayList<>();
        for (Customer customer : customers) {
            if (canAccessCustomer(customer)) {
                filtered.add(customer);
            }
        }
        return filtered;
    }

    private boolean canAccessCustomer(Customer customer) {
        if (employeeProfile == null || customer == null) {
            return true;
        }
        Set<String> allowedCustomerIds = new LinkedHashSet<>(employeeProfile.getAllowedCustomerIds());
        Set<String> allowedDays = new LinkedHashSet<>(employeeProfile.getAllowedDays());
        if (allowedCustomerIds.isEmpty() && allowedDays.isEmpty()) {
            return true;
        }
        return allowedCustomerIds.contains(customer.getId()) || allowedDays.contains(customer.getServiceDay());
    }

    private void saveCustomer() {
        if (!canEditCustomerData()) {
            JOptionPane.showMessageDialog(this, "You have view-only access for customer data.", "Access Restricted", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Customer customer = buildCustomerFromForm();
            if (customer.getId() == null || customer.getId().isBlank()) {
                dbManager.addCustomer(customer);
            } else {
                Customer existing = dbManager.getCustomerById(customer.getId());
                if (existing == null) {
                    dbManager.addCustomer(customer);
                } else {
                    dbManager.updateCustomer(customer);
                }
            }
            loadFilteredCustomers();
            refreshRevenueSummary();
            refreshDetailCustomerList();
            clearForm();
            JOptionPane.showMessageDialog(this, "Customer record saved successfully.", "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Unable to save customer: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Customer buildCustomerFromForm() {
        String id = idField.getText().trim();
        String firstName = firstNameField.getText().trim();
        String lastName = lastNameField.getText().trim();
        String address = addressField.getText().trim();
        String city = cityField.getText().trim();
        String state = stateField.getText().trim();
        String zip = zipField.getText().trim();
        String serviceDay = Objects.toString(dayCombo.getSelectedItem(), "Monday");
        String notes = notesArea.getText().trim();

        if (firstName.isBlank() || lastName.isBlank()) {
            throw new IllegalArgumentException("First name and last name are required.");
        }

        formatPhoneField();
        formatAmountField();

        String phone = phoneField.getText().trim();
        String email = emailField.getText().trim();

        if (email.isBlank() && phone.isBlank()) {
            int response = JOptionPane.showConfirmDialog(this, "No email or phone number entered. Continue?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (response != JOptionPane.YES_OPTION) {
                throw new IllegalArgumentException("Customer contact information is required.");
            }
        }

        if (!email.isBlank() && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Email must be a valid address.");
        }

        BigDecimal amount;
        try {
            String rawAmount = amountField.getText().trim().replaceAll("[^0-9.]", "");
            amount = new BigDecimal(rawAmount.isBlank() ? "0" : rawAmount).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new IllegalArgumentException("Amount must be a valid number.");
        }

        return new Customer(id, firstName, lastName, address, city, state, zip, phone, email, serviceDay, amount, notes);
    }

    private void clearForm() {
        if (!canEditCustomerData() && employeeProfile != null) {
            JOptionPane.showMessageDialog(this, "You have view-only access for customer data.", "Access Restricted", JOptionPane.WARNING_MESSAGE);
            return;
        }
        firstNameField.setText("");
        lastNameField.setText("");
        addressField.setText("");
        cityField.setText("");
        stateField.setText("");
        zipField.setText("");
        phoneField.setText("");
        emailField.setText("");
        dayCombo.setSelectedIndex(0);
        amountField.setText("$0.00");
        notesArea.setText("");
        customerTable.clearSelection();
        currentCustomerIndex = -1;
        updateNavigationButtons();
        setNewCustomerId();
    }

    private Customer getSelectedCustomerFromTable() {
        int selectedRow = customerTable.getSelectedRow();
        if (selectedRow < 0) {
            throw new IllegalStateException("Select a customer from the table first.");
        }
        String id = Objects.toString(tableModel.getValueAt(selectedRow, 0), "");
        Customer customer = dbManager.getCustomerById(id);
        if (customer == null) {
            throw new IllegalStateException("Selected customer record not found.");
        }
        return customer;
    }

    private void generateStatement() {
        try {
            Customer customer = getCustomerFromFormOrSelection();
            List<StatementRecord> records = dbManager.getStatementRecords(customer.getId());
            
            // Create file chooser for PDF location
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File(buildStatementFileName(customer) + ".pdf"));
            fileChooser.setDialogTitle("Save Statement PDF");
            
            if (fileChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return; // User cancelled
            }
            
            File pdfFile = fileChooser.getSelectedFile();
            if (!pdfFile.getName().toLowerCase().endsWith(".pdf")) {
                pdfFile = new File(pdfFile.getAbsolutePath() + ".pdf");
            }
            
            createStatementPDF(customer, records, pdfFile);
            
            JOptionPane.showMessageDialog(this, "Statement PDF saved successfully to:\n" + pdfFile.getAbsolutePath(), "PDF Generated", JOptionPane.INFORMATION_MESSAGE);
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Unable to generate statement PDF: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String buildStatementFileName(Customer customer) {
        String lastName = customer.getLastName() == null ? "" : customer.getLastName().trim();
        String firstName = customer.getFirstName() == null ? "" : customer.getFirstName().trim();

        String baseName;
        if (!lastName.isBlank() && !firstName.isBlank()) {
            baseName = lastName + "_" + firstName + "_POOL_STATEMENT";
        } else {
            baseName = customer.getFullName() + "_POOL_STATEMENT";
        }

        return baseName.toUpperCase().replaceAll("[^A-Z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private PdfSettings loadPdfSettings() {
        PdfSettings settings = new PdfSettings();
        Map<String, String> savedSettings = dbManager.getAllPdfSettings();

        // Load all settings from database, use defaults if not found
        settings.setTitleFont(savedSettings.getOrDefault("titleFont", settings.getTitleFont()));
        settings.setTitleSize(Integer.parseInt(savedSettings.getOrDefault("titleSize", String.valueOf(settings.getTitleSize()))));
        settings.setHeaderFont(savedSettings.getOrDefault("headerFont", settings.getHeaderFont()));
        settings.setHeaderSize(Integer.parseInt(savedSettings.getOrDefault("headerSize", String.valueOf(settings.getHeaderSize()))));
        settings.setBodyFont(savedSettings.getOrDefault("bodyFont", settings.getBodyFont()));
        settings.setBodySize(Integer.parseInt(savedSettings.getOrDefault("bodySize", String.valueOf(settings.getBodySize()))));
        settings.setFooterFont(savedSettings.getOrDefault("footerFont", settings.getFooterFont()));
        settings.setFooterSize(Integer.parseInt(savedSettings.getOrDefault("footerSize", String.valueOf(settings.getFooterSize()))));

        settings.setTitleColor(savedSettings.getOrDefault("titleColor", settings.getTitleColor()));
        settings.setHeaderColor(savedSettings.getOrDefault("headerColor", settings.getHeaderColor()));
        settings.setBodyColor(savedSettings.getOrDefault("bodyColor", settings.getBodyColor()));
        settings.setFooterColor(savedSettings.getOrDefault("footerColor", settings.getFooterColor()));

        settings.setMarginLeft(Float.parseFloat(savedSettings.getOrDefault("marginLeft", String.valueOf(settings.getMarginLeft()))));
        settings.setMarginTop(Float.parseFloat(savedSettings.getOrDefault("marginTop", String.valueOf(settings.getMarginTop()))));

        settings.setCompanyName(savedSettings.getOrDefault("companyName", settings.getCompanyName()));
        settings.setCompanyAddress(savedSettings.getOrDefault("companyAddress", settings.getCompanyAddress()));
        settings.setCompanyPhone(savedSettings.getOrDefault("companyPhone", settings.getCompanyPhone()));
        settings.setCompanyEmail(savedSettings.getOrDefault("companyEmail", settings.getCompanyEmail()));

        settings.setLogoPath(savedSettings.getOrDefault("logoPath", settings.getLogoPath()));
        settings.setShowLogo(Boolean.parseBoolean(savedSettings.getOrDefault("showLogo", String.valueOf(settings.isShowLogo()))));
        settings.setLogoX(Float.parseFloat(savedSettings.getOrDefault("logoX", String.valueOf(settings.getLogoX()))));
        settings.setLogoY(Float.parseFloat(savedSettings.getOrDefault("logoY", String.valueOf(settings.getLogoY()))));
        settings.setLogoWidth(Float.parseFloat(savedSettings.getOrDefault("logoWidth", String.valueOf(settings.getLogoWidth()))));
        settings.setLogoHeight(Float.parseFloat(savedSettings.getOrDefault("logoHeight", String.valueOf(settings.getLogoHeight()))));

        settings.setShowTableBorders(Boolean.parseBoolean(savedSettings.getOrDefault("showTableBorders", String.valueOf(settings.isShowTableBorders()))));
        settings.setTableBorderColor(savedSettings.getOrDefault("tableBorderColor", settings.getTableBorderColor()));

        settings.setFooterText(savedSettings.getOrDefault("footerText", settings.getFooterText()));
        settings.setShowGenerationDate(Boolean.parseBoolean(savedSettings.getOrDefault("showGenerationDate", String.valueOf(settings.isShowGenerationDate()))));

        settings.setPageSize(savedSettings.getOrDefault("pageSize", settings.getPageSize()));
        settings.setLandscape(Boolean.parseBoolean(savedSettings.getOrDefault("landscape", String.valueOf(settings.isLandscape()))));
        
        // Load position offsets
        settings.setCompanyInfoOffsetX(Float.parseFloat(savedSettings.getOrDefault("companyInfoOffsetX", String.valueOf(settings.getCompanyInfoOffsetX()))));
        settings.setCompanyInfoOffsetY(Float.parseFloat(savedSettings.getOrDefault("companyInfoOffsetY", String.valueOf(settings.getCompanyInfoOffsetY()))));
        settings.setTitleOffsetX(Float.parseFloat(savedSettings.getOrDefault("titleOffsetX", String.valueOf(settings.getTitleOffsetX()))));
        settings.setTitleOffsetY(Float.parseFloat(savedSettings.getOrDefault("titleOffsetY", String.valueOf(settings.getTitleOffsetY()))));
        settings.setHeaderOffsetX(Float.parseFloat(savedSettings.getOrDefault("headerOffsetX", String.valueOf(settings.getHeaderOffsetX()))));
        settings.setHeaderOffsetY(Float.parseFloat(savedSettings.getOrDefault("headerOffsetY", String.valueOf(settings.getHeaderOffsetY()))));
        settings.setBodyOffsetX(Float.parseFloat(savedSettings.getOrDefault("bodyOffsetX", String.valueOf(settings.getBodyOffsetX()))));
        settings.setBodyOffsetY(Float.parseFloat(savedSettings.getOrDefault("bodyOffsetY", String.valueOf(settings.getBodyOffsetY()))));
        settings.setTableOffsetX(Float.parseFloat(savedSettings.getOrDefault("tableOffsetX", String.valueOf(settings.getTableOffsetX()))));
        settings.setTableOffsetY(Float.parseFloat(savedSettings.getOrDefault("tableOffsetY", String.valueOf(settings.getTableOffsetY()))));
        settings.setFooterOffsetX(Float.parseFloat(savedSettings.getOrDefault("footerOffsetX", String.valueOf(settings.getFooterOffsetX()))));
        settings.setFooterOffsetY(Float.parseFloat(savedSettings.getOrDefault("footerOffsetY", String.valueOf(settings.getFooterOffsetY()))));

        return settings;
    }

    private void savePdfSettings(PdfSettings settings) {
        dbManager.savePdfSetting("titleFont", settings.getTitleFont());
        dbManager.savePdfSetting("titleSize", String.valueOf(settings.getTitleSize()));
        dbManager.savePdfSetting("headerFont", settings.getHeaderFont());
        dbManager.savePdfSetting("headerSize", String.valueOf(settings.getHeaderSize()));
        dbManager.savePdfSetting("bodyFont", settings.getBodyFont());
        dbManager.savePdfSetting("bodySize", String.valueOf(settings.getBodySize()));
        dbManager.savePdfSetting("footerFont", settings.getFooterFont());
        dbManager.savePdfSetting("footerSize", String.valueOf(settings.getFooterSize()));

        dbManager.savePdfSetting("titleColor", settings.getTitleColor());
        dbManager.savePdfSetting("headerColor", settings.getHeaderColor());
        dbManager.savePdfSetting("bodyColor", settings.getBodyColor());
        dbManager.savePdfSetting("footerColor", settings.getFooterColor());

        dbManager.savePdfSetting("marginLeft", String.valueOf(settings.getMarginLeft()));
        dbManager.savePdfSetting("marginTop", String.valueOf(settings.getMarginTop()));

        dbManager.savePdfSetting("companyName", settings.getCompanyName());
        dbManager.savePdfSetting("companyAddress", settings.getCompanyAddress());
        dbManager.savePdfSetting("companyPhone", settings.getCompanyPhone());
        dbManager.savePdfSetting("companyEmail", settings.getCompanyEmail());

        dbManager.savePdfSetting("logoPath", settings.getLogoPath());
        dbManager.savePdfSetting("showLogo", String.valueOf(settings.isShowLogo()));
        dbManager.savePdfSetting("logoX", String.valueOf(settings.getLogoX()));
        dbManager.savePdfSetting("logoY", String.valueOf(settings.getLogoY()));
        dbManager.savePdfSetting("logoWidth", String.valueOf(settings.getLogoWidth()));
        dbManager.savePdfSetting("logoHeight", String.valueOf(settings.getLogoHeight()));

        dbManager.savePdfSetting("showTableBorders", String.valueOf(settings.isShowTableBorders()));
        dbManager.savePdfSetting("tableBorderColor", settings.getTableBorderColor());

        dbManager.savePdfSetting("footerText", settings.getFooterText());
        dbManager.savePdfSetting("showGenerationDate", String.valueOf(settings.isShowGenerationDate()));

        dbManager.savePdfSetting("pageSize", settings.getPageSize());
        dbManager.savePdfSetting("landscape", String.valueOf(settings.isLandscape()));
        
        // Save position offsets
        dbManager.savePdfSetting("companyInfoOffsetX", String.valueOf(settings.getCompanyInfoOffsetX()));
        dbManager.savePdfSetting("companyInfoOffsetY", String.valueOf(settings.getCompanyInfoOffsetY()));
        dbManager.savePdfSetting("titleOffsetX", String.valueOf(settings.getTitleOffsetX()));
        dbManager.savePdfSetting("titleOffsetY", String.valueOf(settings.getTitleOffsetY()));
        dbManager.savePdfSetting("headerOffsetX", String.valueOf(settings.getHeaderOffsetX()));
        dbManager.savePdfSetting("headerOffsetY", String.valueOf(settings.getHeaderOffsetY()));
        dbManager.savePdfSetting("bodyOffsetX", String.valueOf(settings.getBodyOffsetX()));
        dbManager.savePdfSetting("bodyOffsetY", String.valueOf(settings.getBodyOffsetY()));
        dbManager.savePdfSetting("tableOffsetX", String.valueOf(settings.getTableOffsetX()));
        dbManager.savePdfSetting("tableOffsetY", String.valueOf(settings.getTableOffsetY()));
        dbManager.savePdfSetting("footerOffsetX", String.valueOf(settings.getFooterOffsetX()));
        dbManager.savePdfSetting("footerOffsetY", String.valueOf(settings.getFooterOffsetY()));

    }

    private JPanel createPdfPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createTitledBorder("PDF Settings & Preview"));

        // Create preview panel on the right
        JPanel previewContentPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawPdfPreview(g2d, getWidth(), getHeight(), (JPanel) this);
            }
        };
        previewContentPanel.setBackground(Color.WHITE);
        previewContentPanel.setBorder(BorderFactory.createTitledBorder("Live Preview - Click element to select"));
        previewContentPanel.setCursor(new Cursor(canEditPdfData() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        
        // Track selected element and element bounds
        previewContentPanel.putClientProperty("selectedElement", -1);
        previewContentPanel.putClientProperty("elementBounds", new java.util.ArrayList<java.awt.Rectangle>());
        previewContentPanel.putClientProperty("elementNames", new java.util.ArrayList<String>());
        
        // Add mouse listener for element selection and dragging
        if (canEditPdfData()) {
            previewContentPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    @SuppressWarnings("unchecked")
                    java.util.ArrayList<java.awt.Rectangle> bounds = (java.util.ArrayList<java.awt.Rectangle>) previewContentPanel.getClientProperty("elementBounds");
                    @SuppressWarnings("unchecked")
                    java.util.ArrayList<String> elementNames = (java.util.ArrayList<String>) previewContentPanel.getClientProperty("elementNames");
                    String selectedName = null;

                    if (bounds != null && elementNames != null) {
                        for (int i = 0; i < bounds.size(); i++) {
                            if (bounds.get(i).contains(e.getPoint())) {
                                selectedName = elementNames.get(i);
                                break;
                            }
                        }
                    }

                    previewContentPanel.putClientProperty("selectedElementName", selectedName);
                    previewContentPanel.putClientProperty("dragStartX", e.getX());
                    previewContentPanel.putClientProperty("dragStartY", e.getY());
                    previewContentPanel.repaint();
                }
            });

            previewContentPanel.addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    String selectedName = (String) previewContentPanel.getClientProperty("selectedElementName");
                    Integer startX = (Integer) previewContentPanel.getClientProperty("dragStartX");
                    Integer startY = (Integer) previewContentPanel.getClientProperty("dragStartY");

                    if (selectedName != null && startX != null && startY != null) {
                        int deltaX = (e.getX() - startX) / 5;
                        int deltaY = (e.getY() - startY) / 5;
                        updateElementOffset(selectedName, deltaX, deltaY);
                        previewContentPanel.putClientProperty("dragStartX", e.getX());
                        previewContentPanel.putClientProperty("dragStartY", e.getY());
                        previewContentPanel.repaint();
                    }
                }
            });
        }

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Fonts section
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("=== FONTS ==="), gbc);

        gbc.gridwidth = 1; gbc.gridy = 1;
        panel.add(new JLabel("Title Font:"), gbc);
        gbc.gridx = 1;
        JComboBox<String> titleFontCombo = new JComboBox<>(new String[]{"TIMES_BOLD", "HELVETICA_BOLD", "COURIER_BOLD"});
        titleFontCombo.setSelectedItem(pdfSettings.getTitleFont());
        titleFontCombo.addActionListener(e -> previewContentPanel.repaint());
        panel.add(titleFontCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        panel.add(new JLabel("Title Size:"), gbc);
        gbc.gridx = 1;
        JSpinner titleSizeSpinner = new JSpinner(new SpinnerNumberModel(pdfSettings.getTitleSize(), 8, 72, 1));
        titleSizeSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(titleSizeSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        panel.add(new JLabel("Header Font:"), gbc);
        gbc.gridx = 1;
        JComboBox<String> headerFontCombo = new JComboBox<>(new String[]{"TIMES_BOLD", "HELVETICA_BOLD", "COURIER_BOLD"});
        headerFontCombo.setSelectedItem(pdfSettings.getHeaderFont());
        panel.add(headerFontCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        panel.add(new JLabel("Header Size:"), gbc);
        gbc.gridx = 1;
        JSpinner headerSizeSpinner = new JSpinner(new SpinnerNumberModel(pdfSettings.getHeaderSize(), 8, 72, 1));
        panel.add(headerSizeSpinner, gbc);

        // Colors section
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2;
        panel.add(new JLabel("=== COLORS ==="), gbc);

        gbc.gridwidth = 1; gbc.gridy = 6;
        panel.add(new JLabel("Title Color:"), gbc);
        gbc.gridx = 1;
        JTextField titleColorField = new JTextField(pdfSettings.getTitleColor(), 7);
        titleColorField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { previewContentPanel.repaint(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { previewContentPanel.repaint(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { previewContentPanel.repaint(); }
        });
        panel.add(titleColorField, gbc);

        gbc.gridx = 0; gbc.gridy = 7;
        panel.add(new JLabel("Header Color:"), gbc);
        gbc.gridx = 1;
        JTextField headerColorField = new JTextField(pdfSettings.getHeaderColor(), 7);
        panel.add(headerColorField, gbc);

        // Company info section
        gbc.gridx = 0; gbc.gridy = 8; gbc.gridwidth = 2;
        panel.add(new JLabel("=== COMPANY INFO ==="), gbc);

        gbc.gridwidth = 1; gbc.gridy = 9;
        panel.add(new JLabel("Company Name:"), gbc);
        gbc.gridx = 1;
        JTextField companyNameField = new JTextField(pdfSettings.getCompanyName(), 20);
        panel.add(companyNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 10;
        panel.add(new JLabel("Company Address:"), gbc);
        gbc.gridx = 1;
        JTextField companyAddressField = new JTextField(pdfSettings.getCompanyAddress(), 20);
        panel.add(companyAddressField, gbc);

        gbc.gridx = 0; gbc.gridy = 11;
        panel.add(new JLabel("Company Phone:"), gbc);
        gbc.gridx = 1;
        JTextField companyPhoneField = new JTextField(pdfSettings.getCompanyPhone(), 15);
        panel.add(companyPhoneField, gbc);

        gbc.gridx = 0; gbc.gridy = 12;
        panel.add(new JLabel("Company Email:"), gbc);
        gbc.gridx = 1;
        JTextField companyEmailField = new JTextField(pdfSettings.getCompanyEmail(), 20);
        panel.add(companyEmailField, gbc);

        // Logo section
        gbc.gridx = 0; gbc.gridy = 13; gbc.gridwidth = 2;
        panel.add(new JLabel("=== LOGO ==="), gbc);

        gbc.gridwidth = 1; gbc.gridy = 14;
        panel.add(new JLabel("Logo Path:"), gbc);
        gbc.gridx = 1;
        JTextField logoPathField = new JTextField(pdfSettings.getLogoPath(), 15);
        JCheckBox showLogoCheck = new JCheckBox("Show Logo", pdfSettings.isShowLogo());
        JButton browseLogoButton = new JButton("Browse");
        browseLogoButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Image files", "png", "jpg", "jpeg", "gif"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                logoPathField.setText(chooser.getSelectedFile().getAbsolutePath());
                showLogoCheck.setSelected(true);
                previewContentPanel.repaint();
            }
        });
        JPanel logoPanel = new JPanel(new BorderLayout());
        logoPanel.add(logoPathField, BorderLayout.CENTER);
        logoPanel.add(browseLogoButton, BorderLayout.EAST);
        panel.add(logoPanel, gbc);

        gbc.gridx = 0; gbc.gridy = 15;
        showLogoCheck.addActionListener(e -> previewContentPanel.repaint());
        panel.add(showLogoCheck, gbc);

        gbc.gridx = 0; gbc.gridy = 16;
        panel.add(new JLabel("Logo Width:"), gbc);
        gbc.gridx = 1;
        JSpinner logoWidthSpinner = new JSpinner(new SpinnerNumberModel((int) pdfSettings.getLogoWidth(), 20, 400, 5));
        logoWidthSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(logoWidthSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 17;
        panel.add(new JLabel("Logo Height:"), gbc);
        gbc.gridx = 1;
        JSpinner logoHeightSpinner = new JSpinner(new SpinnerNumberModel((int) pdfSettings.getLogoHeight(), 20, 300, 5));
        logoHeightSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(logoHeightSpinner, gbc);

        // Footer section
        gbc.gridx = 0; gbc.gridy = 18; gbc.gridwidth = 2;
        panel.add(new JLabel("=== FOOTER ==="), gbc);

        gbc.gridwidth = 1; gbc.gridy = 19;
        panel.add(new JLabel("Footer Text:"), gbc);
        gbc.gridx = 1;
        JTextField footerTextField = new JTextField(pdfSettings.getFooterText(), 20);
        panel.add(footerTextField, gbc);

        gbc.gridx = 0; gbc.gridy = 20;
        JCheckBox showGenerationDateCheck = new JCheckBox("Show Generation Date", pdfSettings.isShowGenerationDate());
        panel.add(showGenerationDateCheck, gbc);

        // Position Offsets section
        gbc.gridx = 0; gbc.gridy = 21; gbc.gridwidth = 2;
        panel.add(new JLabel("=== POSITION OFFSETS ==="), gbc);

        gbc.gridwidth = 1; gbc.gridy = 22;
        panel.add(new JLabel("Company Info - X:"), gbc);
        gbc.gridx = 1;
        JSpinner companyInfoOffsetXSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getCompanyInfoOffsetX(), -200, 200, 5));
        companyInfoOffsetXSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(companyInfoOffsetXSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 23;
        panel.add(new JLabel("Company Info - Y:"), gbc);
        gbc.gridx = 1;
        JSpinner companyInfoOffsetYSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getCompanyInfoOffsetY(), -200, 200, 5));
        companyInfoOffsetYSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(companyInfoOffsetYSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 24;
        panel.add(new JLabel("Title - X:"), gbc);
        gbc.gridx = 1;
        JSpinner titleOffsetXSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getTitleOffsetX(), -200, 200, 5));
        titleOffsetXSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(titleOffsetXSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 25;
        panel.add(new JLabel("Title - Y:"), gbc);
        gbc.gridx = 1;
        JSpinner titleOffsetYSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getTitleOffsetY(), -200, 200, 5));
        titleOffsetYSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(titleOffsetYSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 26;
        panel.add(new JLabel("Header - X:"), gbc);
        gbc.gridx = 1;
        JSpinner headerOffsetXSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getHeaderOffsetX(), -200, 200, 5));
        headerOffsetXSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(headerOffsetXSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 27;
        panel.add(new JLabel("Header - Y:"), gbc);
        gbc.gridx = 1;
        JSpinner headerOffsetYSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getHeaderOffsetY(), -200, 200, 5));
        headerOffsetYSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(headerOffsetYSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 28;
        panel.add(new JLabel("Body - X:"), gbc);
        gbc.gridx = 1;
        JSpinner bodyOffsetXSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getBodyOffsetX(), -200, 200, 5));
        bodyOffsetXSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(bodyOffsetXSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 29;
        panel.add(new JLabel("Body - Y:"), gbc);
        gbc.gridx = 1;
        JSpinner bodyOffsetYSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getBodyOffsetY(), -200, 200, 5));
        bodyOffsetYSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(bodyOffsetYSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 30;
        panel.add(new JLabel("Table - X:"), gbc);
        gbc.gridx = 1;
        JSpinner tableOffsetXSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getTableOffsetX(), -200, 200, 5));
        tableOffsetXSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(tableOffsetXSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 31;
        panel.add(new JLabel("Table - Y:"), gbc);
        gbc.gridx = 1;
        JSpinner tableOffsetYSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getTableOffsetY(), -200, 200, 5));
        tableOffsetYSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(tableOffsetYSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 32;
        panel.add(new JLabel("Footer - X:"), gbc);
        gbc.gridx = 1;
        JSpinner footerOffsetXSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getFooterOffsetX(), -200, 200, 5));
        footerOffsetXSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(footerOffsetXSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 33;
        panel.add(new JLabel("Footer - Y:"), gbc);
        gbc.gridx = 1;
        JSpinner footerOffsetYSpinner = new JSpinner(new SpinnerNumberModel((int)pdfSettings.getFooterOffsetY(), -200, 200, 5));
        footerOffsetYSpinner.addChangeListener(e -> previewContentPanel.repaint());
        panel.add(footerOffsetYSpinner, gbc);

        // Buttons
        gbc.gridx = 0; gbc.gridy = 34; gbc.gridwidth = 2;
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton saveButton = new JButton("Save Settings");
        JButton resetButton = new JButton("Reset to Defaults");
        buttonPanel.add(saveButton);
        buttonPanel.add(resetButton);
        panel.add(buttonPanel, gbc);

        saveButton.addActionListener(e -> {
            // Update settings from panel
            pdfSettings.setTitleFont((String) titleFontCombo.getSelectedItem());
            pdfSettings.setTitleSize((Integer) titleSizeSpinner.getValue());
            pdfSettings.setHeaderFont((String) headerFontCombo.getSelectedItem());
            pdfSettings.setHeaderSize((Integer) headerSizeSpinner.getValue());
            pdfSettings.setTitleColor(titleColorField.getText());
            pdfSettings.setHeaderColor(headerColorField.getText());
            pdfSettings.setCompanyName(companyNameField.getText());
            pdfSettings.setCompanyAddress(companyAddressField.getText());
            pdfSettings.setCompanyPhone(companyPhoneField.getText());
            pdfSettings.setCompanyEmail(companyEmailField.getText());
            pdfSettings.setLogoPath(logoPathField.getText());
            pdfSettings.setShowLogo(showLogoCheck.isSelected());
            pdfSettings.setLogoWidth((Integer) logoWidthSpinner.getValue());
            pdfSettings.setLogoHeight((Integer) logoHeightSpinner.getValue());
            pdfSettings.setFooterText(footerTextField.getText());
            pdfSettings.setShowGenerationDate(showGenerationDateCheck.isSelected());
            
            // Update position offsets
            pdfSettings.setCompanyInfoOffsetX((Integer) companyInfoOffsetXSpinner.getValue());
            pdfSettings.setCompanyInfoOffsetY((Integer) companyInfoOffsetYSpinner.getValue());
            pdfSettings.setTitleOffsetX((Integer) titleOffsetXSpinner.getValue());
            pdfSettings.setTitleOffsetY((Integer) titleOffsetYSpinner.getValue());
            pdfSettings.setHeaderOffsetX((Integer) headerOffsetXSpinner.getValue());
            pdfSettings.setHeaderOffsetY((Integer) headerOffsetYSpinner.getValue());
            pdfSettings.setBodyOffsetX((Integer) bodyOffsetXSpinner.getValue());
            pdfSettings.setBodyOffsetY((Integer) bodyOffsetYSpinner.getValue());
            pdfSettings.setTableOffsetX((Integer) tableOffsetXSpinner.getValue());
            pdfSettings.setTableOffsetY((Integer) tableOffsetYSpinner.getValue());
            pdfSettings.setFooterOffsetX((Integer) footerOffsetXSpinner.getValue());
            pdfSettings.setFooterOffsetY((Integer) footerOffsetYSpinner.getValue());

            // Save to database
            savePdfSettings(pdfSettings);
            JOptionPane.showMessageDialog(this, "PDF settings saved successfully!", "Settings Saved", JOptionPane.INFORMATION_MESSAGE);
        });

        resetButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to reset all PDF settings to defaults?",
                "Reset Settings", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                PdfSettings resetSettings = new PdfSettings(); // Reset to defaults
                savePdfSettings(resetSettings);
                JOptionPane.showMessageDialog(this, "PDF settings reset to defaults!", "Settings Reset", JOptionPane.INFORMATION_MESSAGE);
                // Refresh the panel by recreating it
                mainPanel.removeAll();
                mainPanel.add(createPdfPanel());
                mainPanel.revalidate();
                mainPanel.repaint();
            }
        });

        if (!canEditPdfData()) {
            setContainerEnabled(panel, false);
        }

        if (!canEditPdfData()) {
            setContainerEnabled(panel, false);
        }

        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Create split pane with settings on left and preview on right
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, previewContentPanel);
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.5);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        return mainPanel;
    }

    private void setContainerEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                setContainerEnabled(child, enabled);
            }
        }
    }

    private void updateElementOffset(String elementName, int deltaX, int deltaY) {
        switch (elementName) {
            case "CompanyInfo":
                pdfSettings.setCompanyInfoOffsetX(pdfSettings.getCompanyInfoOffsetX() + deltaX);
                pdfSettings.setCompanyInfoOffsetY(pdfSettings.getCompanyInfoOffsetY() + deltaY);
                break;
            case "Title":
                pdfSettings.setTitleOffsetX(pdfSettings.getTitleOffsetX() + deltaX);
                pdfSettings.setTitleOffsetY(pdfSettings.getTitleOffsetY() + deltaY);
                break;
            case "Header":
                pdfSettings.setHeaderOffsetX(pdfSettings.getHeaderOffsetX() + deltaX);
                pdfSettings.setHeaderOffsetY(pdfSettings.getHeaderOffsetY() + deltaY);
                break;
            case "Body":
                pdfSettings.setBodyOffsetX(pdfSettings.getBodyOffsetX() + deltaX);
                pdfSettings.setBodyOffsetY(pdfSettings.getBodyOffsetY() + deltaY);
                break;
            case "Table":
                pdfSettings.setTableOffsetX(pdfSettings.getTableOffsetX() + deltaX);
                pdfSettings.setTableOffsetY(pdfSettings.getTableOffsetY() + deltaY);
                break;
            case "Footer":
                pdfSettings.setFooterOffsetX(pdfSettings.getFooterOffsetX() + deltaX);
                pdfSettings.setFooterOffsetY(pdfSettings.getFooterOffsetY() + deltaY);
                break;
        }
    }

    private void drawPdfPreview(Graphics2D g2d, int width, int height, JPanel previewPanel) {
        try {
            float pageWidthPoints = "A4".equals(pdfSettings.getPageSize()) ? PDRectangle.A4.getWidth() : PDRectangle.LETTER.getWidth();
            float pageHeightPoints = "A4".equals(pdfSettings.getPageSize()) ? PDRectangle.A4.getHeight() : PDRectangle.LETTER.getHeight();
            float scale = Math.min(width / pageWidthPoints, height / pageHeightPoints) * 0.95f;
            int scaledWidth = (int) (pageWidthPoints * scale);
            int scaledHeight = (int) (pageHeightPoints * scale);
            int startX = (width - scaledWidth) / 2;
            int startY = (height - scaledHeight) / 2;

            // Initialize element bounds tracking
            @SuppressWarnings("unchecked")
            java.util.ArrayList<java.awt.Rectangle> elementBounds = (java.util.ArrayList<java.awt.Rectangle>) previewPanel.getClientProperty("elementBounds");
            @SuppressWarnings("unchecked")
            java.util.ArrayList<String> elementNames = (java.util.ArrayList<String>) previewPanel.getClientProperty("elementNames");
            String selectedElementName = (String) previewPanel.getClientProperty("selectedElementName");
            
            if (elementBounds == null) {
                elementBounds = new java.util.ArrayList<>();
                elementNames = new java.util.ArrayList<>();
            } else {
                elementBounds.clear();
                elementNames.clear();
            }

            // Draw page background
            g2d.setColor(Color.WHITE);
            g2d.fillRect(startX, startY, scaledWidth, scaledHeight);
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(1));
            g2d.drawRect(startX, startY, scaledWidth, scaledHeight);

            // Draw margins and content preview
            float marginLeft = pdfSettings.getMarginLeft() * scale;
            float marginTop = pdfSettings.getMarginTop() * scale;
            float contentX = startX + marginLeft + (pdfSettings.getCompanyInfoOffsetX() * scale);
            float contentY = startY + marginTop + (pdfSettings.getCompanyInfoOffsetY() * scale);

            // Draw logo at top-left when enabled.
            if (pdfSettings.isShowLogo() && !pdfSettings.getLogoPath().isBlank()) {
                int logoX = (int) contentX;
                int logoY = (int) contentY;
                int logoWidth = Math.max(10, (int) (pdfSettings.getLogoWidth() * scale));
                int logoHeight = Math.max(10, (int) (pdfSettings.getLogoHeight() * scale));
                try {
                    ImageIcon icon = new ImageIcon(pdfSettings.getLogoPath());
                    Image image = icon.getImage();
                    if (image != null && icon.getIconWidth() > 0) {
                        g2d.drawImage(image, logoX, logoY, logoWidth, logoHeight, null);
                    } else {
                        g2d.setColor(new Color(220, 220, 220));
                        g2d.fillRect(logoX, logoY, logoWidth, logoHeight);
                        g2d.setColor(Color.DARK_GRAY);
                        g2d.drawRect(logoX, logoY, logoWidth, logoHeight);
                        g2d.drawString("LOGO", logoX + 6, logoY + (logoHeight / 2));
                    }
                } catch (Exception e) {
                    g2d.setColor(new Color(220, 220, 220));
                    g2d.fillRect(logoX, logoY, logoWidth, logoHeight);
                    g2d.setColor(Color.DARK_GRAY);
                    g2d.drawRect(logoX, logoY, logoWidth, logoHeight);
                    g2d.drawString("LOGO", logoX + 6, logoY + (logoHeight / 2));
                }
                contentY += logoHeight + (6 * scale);
            }

            // Draw company name if available
            if (!pdfSettings.getCompanyName().isBlank()) {
                g2d.setFont(new Font("SansSerif", Font.BOLD, (int) (pdfSettings.getTitleSize() * scale / 2)));
                try {
                    Color titleColor = Color.decode(pdfSettings.getTitleColor());
                    g2d.setColor(titleColor);
                } catch (NumberFormatException e) {
                    g2d.setColor(Color.BLACK);
                }
                FontMetrics fm = g2d.getFontMetrics();
                int textWidth = fm.stringWidth(pdfSettings.getCompanyName());
                int textHeight = fm.getHeight();
                g2d.drawString(pdfSettings.getCompanyName(), (int) contentX, (int) contentY);
                elementBounds.add(new java.awt.Rectangle((int) contentX - 2, (int) (contentY - textHeight + 2), textWidth + 4, textHeight + 4));
                elementNames.add("CompanyInfo");
                contentY += (pdfSettings.getTitleSize() * scale / 2) + 5;
            }

            // Draw company address if available
            if (!pdfSettings.getCompanyAddress().isBlank()) {
                g2d.setFont(new Font("SansSerif", Font.PLAIN, (int) (pdfSettings.getHeaderSize() * scale / 2)));
                try {
                    Color headerColor = Color.decode(pdfSettings.getHeaderColor());
                    g2d.setColor(headerColor);
                } catch (NumberFormatException e) {
                    g2d.setColor(Color.BLACK);
                }
                g2d.drawString(pdfSettings.getCompanyAddress(), (int) contentX, (int) contentY);
                contentY += (pdfSettings.getHeaderSize() * scale / 2) + 5;
            }

            // Draw title with offset
            float titleX = contentX + (pdfSettings.getTitleOffsetX() * scale);
            float titleY = contentY + (pdfSettings.getTitleOffsetY() * scale);
            g2d.setFont(new Font("SansSerif", Font.BOLD, (int) (pdfSettings.getTitleSize() * scale / 2)));
            try {
                Color titleColor = Color.decode(pdfSettings.getTitleColor());
                g2d.setColor(titleColor);
            } catch (NumberFormatException e) {
                g2d.setColor(Color.BLACK);
            }
            FontMetrics fm = g2d.getFontMetrics();
            int titleWidth = fm.stringWidth("Pool Service Statement");
            int titleHeight = fm.getHeight();
            g2d.drawString("Pool Service Statement", (int) titleX, (int) titleY);
            elementBounds.add(new java.awt.Rectangle((int) titleX - 2, (int) (titleY - titleHeight + 2), titleWidth + 4, titleHeight + 4));
            elementNames.add("Title");
            float titleY2 = titleY + (pdfSettings.getTitleSize() * scale / 2) + 10;

            // Draw sample header with offset
            float headerX = contentX + (pdfSettings.getHeaderOffsetX() * scale);
            float headerY = titleY2 + (pdfSettings.getHeaderOffsetY() * scale);
            g2d.setFont(new Font("SansSerif", Font.BOLD, (int) (pdfSettings.getHeaderSize() * scale / 2)));
            try {
                Color headerColor = Color.decode(pdfSettings.getHeaderColor());
                g2d.setColor(headerColor);
            } catch (NumberFormatException e) {
                g2d.setColor(Color.BLACK);
            }
            fm = g2d.getFontMetrics();
            int headerWidth = fm.stringWidth("Customer Information:");
            int headerHeight = fm.getHeight();
            g2d.drawString("Customer Information:", (int) headerX, (int) headerY);
            elementBounds.add(new java.awt.Rectangle((int) headerX - 2, (int) (headerY - headerHeight + 2), headerWidth + 4, headerHeight + 4));
            elementNames.add("Header");
            float bodyStartY = headerY + (pdfSettings.getHeaderSize() * scale / 2) + 5;

            // Draw sample body text with offset
            float bodyX = contentX + (pdfSettings.getBodyOffsetX() * scale);
            float bodyY = bodyStartY + (pdfSettings.getBodyOffsetY() * scale);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, (int) (pdfSettings.getBodySize() * scale / 2)));
            try {
                Color bodyColor = Color.decode(pdfSettings.getBodyColor());
                g2d.setColor(bodyColor);
            } catch (NumberFormatException e) {
                g2d.setColor(Color.BLACK);
            }
            g2d.drawString("Name: John Doe", (int) bodyX, (int) bodyY);
            fm = g2d.getFontMetrics();
            int bodyWidth = fm.stringWidth("Name: John Doe") + fm.stringWidth("Address: 123 Sample Street") / 2;
            bodyY += (pdfSettings.getBodySize() * scale / 2) + 3;
            g2d.drawString("Address: 123 Sample Street", (int) bodyX, (int) bodyY);
            bodyY += (pdfSettings.getBodySize() * scale / 2) + 3;
            g2d.drawString("Phone: (555) 123-4567", (int) bodyX, (int) bodyY);
            int bodyHeight = (int) (bodyY - bodyStartY + (pdfSettings.getBodySize() * scale / 2));
            elementBounds.add(new java.awt.Rectangle((int) bodyX - 2, (int) (bodyStartY - fm.getHeight() + 2), bodyWidth + 4, bodyHeight + 4));
            elementNames.add("Body");
            float tableStartY = bodyY + (pdfSettings.getBodySize() * scale / 2) + 10;

            // Draw table header if borders enabled, with offset
            if (pdfSettings.isShowTableBorders()) {
                float tableX = contentX + (pdfSettings.getTableOffsetX() * scale);
                float tableY = tableStartY + (pdfSettings.getTableOffsetY() * scale);
                g2d.setFont(new Font("SansSerif", Font.BOLD, (int) (pdfSettings.getBodySize() * scale / 2)));
                try {
                    Color borderColor = Color.decode(pdfSettings.getTableBorderColor());
                    g2d.setColor(borderColor);
                } catch (NumberFormatException e) {
                    g2d.setColor(Color.BLACK);
                }
                g2d.setStroke(new BasicStroke(0.5f));
                float tableWidth = 300 * scale;
                float rowHeight = pdfSettings.getBodySize() * scale / 2 + 3;
                g2d.drawRect((int) tableX, (int) tableY, (int) tableWidth, (int) (rowHeight * 3));
                g2d.drawString("Date", (int) tableX + 5, (int) (tableY + rowHeight / 2));
                g2d.drawString("Type", (int) (tableX + 100 * scale) + 5, (int) (tableY + rowHeight / 2));
                g2d.drawString("Amount", (int) (tableX + 200 * scale) + 5, (int) (tableY + rowHeight / 2));
                elementBounds.add(new java.awt.Rectangle((int) tableX - 2, (int) tableY - 2, (int) tableWidth + 4, (int) (rowHeight * 3) + 4));
                elementNames.add("Table");
            }

            // Draw footer if available, with offset
            if (!pdfSettings.getFooterText().isBlank() || pdfSettings.isShowGenerationDate()) {
                g2d.setFont(new Font("SansSerif", Font.PLAIN, (int) (pdfSettings.getFooterSize() * scale / 2)));
                try {
                    Color footerColor = Color.decode(pdfSettings.getFooterColor());
                    g2d.setColor(footerColor);
                } catch (NumberFormatException e) {
                    g2d.setColor(Color.BLACK);
                }
                float footerY = startY + scaledHeight - 10 + (pdfSettings.getFooterOffsetY() * scale);
                float footerX = startX + marginLeft + (pdfSettings.getFooterOffsetX() * scale);
                fm = g2d.getFontMetrics();
                int footerWidth = 200;
                int footerHeight = fm.getHeight() * 2;
                if (!pdfSettings.getFooterText().isBlank()) {
                    g2d.drawString(pdfSettings.getFooterText(), (int) footerX, (int) footerY);
                }
                if (pdfSettings.isShowGenerationDate()) {
                    g2d.drawString("Generated on " + LocalDate.now(), (int) footerX, (int) (footerY - 10));
                }
                elementBounds.add(new java.awt.Rectangle((int) footerX - 2, (int) (footerY - footerHeight + 2), footerWidth + 4, footerHeight + 4));
                elementNames.add("Footer");
            }

            // Store element bounds for hit detection
            if (previewPanel != null) {
                previewPanel.putClientProperty("elementBounds", elementBounds);
                previewPanel.putClientProperty("elementNames", elementNames);
            }

            // Draw selection box around selected element
            if (selectedElementName != null) {
                int selectedIdx = elementNames.indexOf(selectedElementName);
                if (selectedIdx >= 0 && selectedIdx < elementBounds.size()) {
                    java.awt.Rectangle bounds = elementBounds.get(selectedIdx);
                    g2d.setColor(new Color(0, 102, 204)); // Blue
                    g2d.setStroke(new BasicStroke(2));
                    g2d.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
                    
                    // Draw resize handles (small squares at corners)
                    int handleSize = 6;
                    g2d.setColor(new Color(0, 102, 204));
                    g2d.fillRect(bounds.x - handleSize/2, bounds.y - handleSize/2, handleSize, handleSize); // Top-left
                    g2d.fillRect(bounds.x + bounds.width - handleSize/2, bounds.y - handleSize/2, handleSize, handleSize); // Top-right
                    g2d.fillRect(bounds.x - handleSize/2, bounds.y + bounds.height - handleSize/2, handleSize, handleSize); // Bottom-left
                    g2d.fillRect(bounds.x + bounds.width - handleSize/2, bounds.y + bounds.height - handleSize/2, handleSize, handleSize); // Bottom-right
                }
            }

        } catch (Exception e) {
            g2d.setColor(Color.BLACK);
            g2d.drawString("Preview error: " + e.getMessage(), 20, 30);
        }
    }

    private void createStatementPDF(Customer customer, List<StatementRecord> records, File pdfFile) throws IOException {
        try (PDDocument document = new PDDocument()) {
            // Set page size and orientation based on settings
            PDRectangle pageSize = PDRectangle.LETTER;
            if ("A4".equals(pdfSettings.getPageSize())) {
                pageSize = PDRectangle.A4;
            }
            PDPage page = new PDPage(pageSize);
            if (pdfSettings.isLandscape()) {
                page.setRotation(90);
            }
            document.addPage(page);
            
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                float marginLeft = pdfSettings.getMarginLeft();
                float marginRight = pdfSettings.getMarginRight();
                float marginTop = pdfSettings.getMarginTop();
                float pageWidth = pageSize.getWidth();
                float pageHeight = pageSize.getHeight();
                float contentWidth = pageWidth - marginLeft - marginRight;
                float contentLeft = marginLeft;
                
                // Company header with logo
                float yPosition = pageHeight - marginTop;
                
                if (pdfSettings.isShowLogo() && !pdfSettings.getLogoPath().isBlank()) {
                    try {
                        File logoFile = new File(pdfSettings.getLogoPath());
                        if (logoFile.exists()) {
                            PDImageXObject logo = PDImageXObject.createFromFile(pdfSettings.getLogoPath(), document);
                            float logoX = marginLeft;
                            float logoY = yPosition - pdfSettings.getLogoHeight();
                            contentStream.drawImage(logo, logoX, logoY, pdfSettings.getLogoWidth(), pdfSettings.getLogoHeight());
                            yPosition = logoY - 8;
                        }
                    } catch (Exception e) {
                        // Logo loading failed, continue without logo
                    }
                }
                
                // Company information
                if (!pdfSettings.getCompanyName().isBlank()) {
                    PDFont titleFont = getFont(pdfSettings.getTitleFont());
                    contentStream.setFont(titleFont, pdfSettings.getTitleSize());
                    contentStream.setNonStrokingColor(pdfSettings.getTitleColorAsColor());
                    drawCenteredText(contentStream, titleFont, pdfSettings.getTitleSize(), pdfSettings.getCompanyName(), contentLeft, contentWidth, yPosition);
                    yPosition -= pdfSettings.getTitleSize() + 5;
                }
                
                if (!pdfSettings.getCompanyAddress().isBlank()) {
                    PDFont headerFont = getFont(pdfSettings.getHeaderFont());
                    contentStream.setFont(headerFont, pdfSettings.getHeaderSize());
                    contentStream.setNonStrokingColor(pdfSettings.getHeaderColorAsColor());
                    drawCenteredText(contentStream, headerFont, pdfSettings.getHeaderSize(), pdfSettings.getCompanyAddress(), contentLeft, contentWidth, yPosition);
                    yPosition -= pdfSettings.getHeaderSize() + 5;
                }
                
                if (!pdfSettings.getCompanyPhone().isBlank()) {
                    PDFont bodyFont = getFont(pdfSettings.getBodyFont());
                    contentStream.setFont(bodyFont, pdfSettings.getBodySize());
                    contentStream.setNonStrokingColor(pdfSettings.getBodyColorAsColor());
                    drawCenteredText(contentStream, bodyFont, pdfSettings.getBodySize(), "Phone: " + pdfSettings.getCompanyPhone(), contentLeft, contentWidth, yPosition);
                    yPosition -= pdfSettings.getBodySize() + 5;
                }
                
                if (!pdfSettings.getCompanyEmail().isBlank()) {
                    PDFont bodyFont = getFont(pdfSettings.getBodyFont());
                    contentStream.setFont(bodyFont, pdfSettings.getBodySize());
                    contentStream.setNonStrokingColor(pdfSettings.getBodyColorAsColor());
                    drawCenteredText(contentStream, bodyFont, pdfSettings.getBodySize(), "Email: " + pdfSettings.getCompanyEmail(), contentLeft, contentWidth, yPosition);
                    yPosition -= pdfSettings.getBodySize() + 5;
                }
                
                // Statement title
                yPosition -= 20;
                PDFont titleFont = getFont(pdfSettings.getTitleFont());
                contentStream.setFont(titleFont, pdfSettings.getTitleSize());
                contentStream.setNonStrokingColor(pdfSettings.getTitleColorAsColor());
                drawCenteredText(contentStream, titleFont, pdfSettings.getTitleSize(), "Pool Service Statement", contentLeft, contentWidth, yPosition);
                yPosition -= pdfSettings.getTitleSize() + 10;
                
                // Customer Information
                PDFont headerFont = getFont(pdfSettings.getHeaderFont());
                contentStream.setFont(headerFont, pdfSettings.getHeaderSize());
                contentStream.setNonStrokingColor(pdfSettings.getHeaderColorAsColor());
                drawCenteredText(contentStream, headerFont, pdfSettings.getHeaderSize(), "Customer Information:", contentLeft, contentWidth, yPosition);
                yPosition -= pdfSettings.getHeaderSize() + 5;
                
                PDFont bodyFont = getFont(pdfSettings.getBodyFont());
                contentStream.setFont(bodyFont, pdfSettings.getBodySize());
                contentStream.setNonStrokingColor(pdfSettings.getBodyColorAsColor());
                
                drawCenteredText(contentStream, bodyFont, pdfSettings.getBodySize(), "Name: " + customer.getFullName(), contentLeft, contentWidth, yPosition);
                yPosition -= pdfSettings.getBodySize() + 3;
                
                drawCenteredText(contentStream, bodyFont, pdfSettings.getBodySize(), "Address: " + customer.getAddress(), contentLeft, contentWidth, yPosition);
                yPosition -= pdfSettings.getBodySize() + 3;
                
                if (!customer.getCity().isBlank() || !customer.getState().isBlank() || !customer.getZip().isBlank()) {
                    String cityStateZip = customer.getCity();
                    if (!customer.getState().isBlank()) {
                        cityStateZip += (cityStateZip.isBlank() ? "" : ", ") + customer.getState();
                    }
                    if (!customer.getZip().isBlank()) {
                        cityStateZip += (cityStateZip.isBlank() ? "" : " ") + customer.getZip();
                    }
                    drawCenteredText(contentStream, bodyFont, pdfSettings.getBodySize(), "City/State/Zip: " + cityStateZip, contentLeft, contentWidth, yPosition);
                    yPosition -= pdfSettings.getBodySize() + 3;
                }
                
                if (!customer.getPhone().isBlank()) {
                    drawCenteredText(contentStream, bodyFont, pdfSettings.getBodySize(), "Phone: " + customer.getPhone(), contentLeft, contentWidth, yPosition);
                    yPosition -= pdfSettings.getBodySize() + 3;
                }
                
                if (!customer.getEmail().isBlank()) {
                    drawCenteredText(contentStream, bodyFont, pdfSettings.getBodySize(), "Email: " + customer.getEmail(), contentLeft, contentWidth, yPosition);
                    yPosition -= pdfSettings.getBodySize() + 3;
                }
                
                // Statement Records
                yPosition -= 15;
                contentStream.setFont(headerFont, pdfSettings.getHeaderSize());
                contentStream.setNonStrokingColor(pdfSettings.getHeaderColorAsColor());
                drawCenteredText(contentStream, headerFont, pdfSettings.getHeaderSize(), "Statement Records:", contentLeft, contentWidth, yPosition);
                yPosition -= pdfSettings.getHeaderSize() + 5;
                
                // Table headers
                contentStream.setFont(bodyFont, pdfSettings.getBodySize());
                contentStream.setNonStrokingColor(pdfSettings.getBodyColorAsColor());
                float tableWidth = contentWidth;
                float tableX = contentLeft;
                float dateColX = tableX + 5;
                float typeColX = tableX + (tableWidth * 0.35f);
                float amountColX = tableX + (tableWidth * 0.75f);
                
                if (pdfSettings.isShowTableBorders()) {
                    contentStream.setStrokingColor(pdfSettings.getTableBorderColorAsColor());
                    contentStream.setLineWidth(0.5f);
                    
                    // Draw table borders
                    float tableStartY = yPosition + 5;
                    float tableEndY = yPosition - (records.size() * (pdfSettings.getBodySize() + 2)) - 10;
                    contentStream.moveTo(tableX, tableStartY);
                    contentStream.lineTo(tableX + tableWidth, tableStartY);
                    contentStream.stroke();
                    
                    contentStream.moveTo(tableX, tableEndY);
                    contentStream.lineTo(tableX + tableWidth, tableEndY);
                    contentStream.stroke();
                    
                    // Vertical lines
                    contentStream.moveTo(tableX, tableStartY);
                    contentStream.lineTo(tableX, tableEndY);
                    contentStream.stroke();
                    
                    contentStream.moveTo(tableX + (tableWidth * 0.33f), tableStartY);
                    contentStream.lineTo(tableX + (tableWidth * 0.33f), tableEndY);
                    contentStream.stroke();
                    
                    contentStream.moveTo(tableX + (tableWidth * 0.72f), tableStartY);
                    contentStream.lineTo(tableX + (tableWidth * 0.72f), tableEndY);
                    contentStream.stroke();
                    
                    contentStream.moveTo(tableX + tableWidth, tableStartY);
                    contentStream.lineTo(tableX + tableWidth, tableEndY);
                    contentStream.stroke();
                }
                
                contentStream.beginText();
                contentStream.newLineAtOffset(dateColX, yPosition);
                contentStream.showText("Date");
                contentStream.endText();
                
                contentStream.beginText();
                contentStream.newLineAtOffset(typeColX, yPosition);
                contentStream.showText("Type");
                contentStream.endText();
                
                contentStream.beginText();
                contentStream.newLineAtOffset(amountColX, yPosition);
                contentStream.showText("Amount");
                contentStream.endText();
                
                yPosition -= pdfSettings.getBodySize() + 5;
                
                BigDecimal totalBalance = BigDecimal.ZERO;
                
                for (StatementRecord record : records) {
                    if (yPosition < 100) {
                        // If we're running out of space, we could add another page here
                        // For now, we'll just continue (records might get cut off)
                        break;
                    }
                    
                    contentStream.beginText();
                    contentStream.newLineAtOffset(dateColX, yPosition);
                    contentStream.showText(record.getDate());
                    contentStream.endText();
                    
                    contentStream.beginText();
                    contentStream.newLineAtOffset(typeColX, yPosition);
                    contentStream.showText(record.getType());
                    contentStream.endText();
                    
                    String amountStr = (record.getAmount().signum() < 0 ? "-" : "") + "$" + record.getAmount().abs().setScale(2, java.math.RoundingMode.HALF_UP);
                    contentStream.beginText();
                    contentStream.newLineAtOffset(amountColX, yPosition);
                    contentStream.showText(amountStr);
                    contentStream.endText();
                    
                    totalBalance = totalBalance.add(record.getAmount());
                    yPosition -= pdfSettings.getBodySize() + 2;
                }
                
                // Total Balance
                yPosition -= 10;
                contentStream.setFont(headerFont, pdfSettings.getHeaderSize());
                contentStream.setNonStrokingColor(pdfSettings.getHeaderColorAsColor());
                drawCenteredText(contentStream, headerFont, pdfSettings.getHeaderSize(), "Total Balance: $" + totalBalance.setScale(2, java.math.RoundingMode.HALF_UP), contentLeft, contentWidth, yPosition);
                
                // Footer
                PDFont footerFont = getFont(pdfSettings.getFooterFont());
                contentStream.setFont(footerFont, pdfSettings.getFooterSize());
                contentStream.setNonStrokingColor(pdfSettings.getFooterColorAsColor());
                
                if (!pdfSettings.getFooterText().isBlank()) {
                    drawCenteredText(contentStream, footerFont, pdfSettings.getFooterSize(), pdfSettings.getFooterText(), contentLeft, contentWidth, 50);
                }
                
                if (pdfSettings.isShowGenerationDate()) {
                    drawCenteredText(contentStream, footerFont, pdfSettings.getFooterSize(), "Generated on " + LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy")), contentLeft, contentWidth, 30);
                }
            }
            
            document.save(pdfFile);
        }
    }

    private void drawCenteredText(PDPageContentStream contentStream, PDFont font, float fontSize,
                                  String text, float contentLeft, float contentWidth, float y) throws IOException {
        contentStream.beginText();
        contentStream.newLineAtOffset(getCenteredTextX(font, fontSize, text, contentLeft, contentWidth), y);
        contentStream.showText(text);
        contentStream.endText();
    }

    private float getCenteredTextX(PDFont font, float fontSize, String text,
                                   float contentLeft, float contentWidth) throws IOException {
        float textWidth = font.getStringWidth(text) / 1000f * fontSize;
        return contentLeft + Math.max(0, (contentWidth - textWidth) / 2f);
    }
    
    private PDType1Font getFont(String fontName) {
        switch (fontName) {
            case "TIMES_BOLD": return new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
            case "TIMES_ROMAN": return new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            case "HELVETICA_BOLD": return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            case "HELVETICA": return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            case "COURIER_BOLD": return new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
            case "COURIER": return new PDType1Font(Standard14Fonts.FontName.COURIER);
            default: return new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
        }
    }

    private void backupDatabase() {
        Path dbPath = dbManager.getDatabasePath();
        File defaultBackup = new File(dbPath.getParent().toFile(), "customer_backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".db");
        JFileChooser chooser = new JFileChooser(dbPath.getParent().toFile());
        chooser.setDialogTitle("Save Database Backup");
        chooser.setSelectedFile(defaultBackup);
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite Database", "db"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase().endsWith(".db")) {
            target = new File(target.getAbsolutePath() + ".db");
        }
        try {
            Files.copy(dbPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            JOptionPane.showMessageDialog(this, "Database backup saved to:\n" + target.getAbsolutePath(), "Backup Complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Unable to save database backup: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreDatabase() {
        Path dbPath = dbManager.getDatabasePath();
        JFileChooser chooser = new JFileChooser(dbPath.getParent().toFile());
        chooser.setDialogTitle("Select Database Backup to Restore");
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite Database", "db"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (!selected.exists()) {
            JOptionPane.showMessageDialog(this, "The selected backup file does not exist.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Path currentBackup = dbPath.resolveSibling("customer_restore_backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".db");
            Files.copy(dbPath, currentBackup, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(selected.toPath(), dbPath, StandardCopyOption.REPLACE_EXISTING);
            JOptionPane.showMessageDialog(this, "Database restored successfully. Previous database backed up as:\n" + currentBackup.toAbsolutePath(), "Restore Complete", JOptionPane.INFORMATION_MESSAGE);
            loadCustomers(filterAccessibleCustomers(dbManager.getAllCustomers()));
            refreshRevenueSummary();
            refreshDetailCustomerList();
            clearForm();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Unable to restore database: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sendEmailToCustomer() {
        File statementPdf = null;
        try {
            Customer customer = getCustomerFromFormOrSelection();
            if (customer.getEmail().isBlank()) {
                throw new IllegalArgumentException("Customer does not have an email address.");
            }
            String senderEmail = getStatementSenderEmail();
            if (senderEmail.isBlank()) {
                throw new IllegalStateException("Sender email is not configured.");
            }
            statementPdf = createTemporaryStatementPdf(customer);
            String statement = buildStatement(customer, getStatementSenderName(), senderEmail, getStatementSenderPhone());
            emailService.sendEmail(customer.getEmail(), "Pool Service Statement for " + customer.getFullName(), statement,
                    senderEmail, getStatementSenderName(), statementPdf);
            JOptionPane.showMessageDialog(this, "Email sent successfully.", "Email", JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable error) {
            String message = error.getMessage();
            JOptionPane.showMessageDialog(this, "Unable to send email: "
                    + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message),
                    "Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (statementPdf != null && statementPdf.exists()) {
                statementPdf.delete();
            }
        }
    }

    private void sendSmsToCustomer() {
        try {
            Customer customer = getCustomerFromFormOrSelection();
            if (customer.getPhone().isBlank()) {
                throw new IllegalArgumentException("Customer does not have a phone number.");
            }
            String senderPhone = getStatementSenderPhone();
            if (senderPhone.isBlank()) {
                throw new IllegalStateException("Sender phone is not configured.");
            }
            String statement = buildStatement(customer, getStatementSenderName(), getStatementSenderEmail(), senderPhone);
            smsService.sendSms(customer.getPhone(), statement);
            JOptionPane.showMessageDialog(this, "SMS sent successfully.", "SMS", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Unable to send SMS: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Customer getCustomerFromFormOrSelection() {
        if (!idField.getText().trim().isBlank()) {
            return buildCustomerFromForm();
        }
        return getSelectedCustomerFromTable();
    }

    private File createTemporaryStatementPdf(Customer customer) throws IOException {
        String prefix = buildStatementFileName(customer);
        if (prefix.length() < 3) {
            prefix = "STATEMENT";
        }
        File pdfFile = Files.createTempFile(prefix + "_", ".pdf").toFile();
        createStatementPDF(customer, dbManager.getStatementRecords(customer.getId()), pdfFile);
        return pdfFile;
    }

    private String buildStatement(Customer customer, String senderName, String senderEmail, String senderPhone) {
        return "Pool Service Statement\n"
                + "==========================\n"
                + "Sent By: " + senderName + "\n"
                + "Sender Phone: " + senderPhone + "\n"
                + "Sender Email: " + senderEmail + "\n"
                + "Customer ID: " + customer.getId() + "\n"
                + "Name: " + customer.getFullName() + "\n"
                + "Address: " + customer.getAddress() + "\n"
                + "City: " + customer.getCity() + "\n"
                + "State: " + customer.getState() + "\n"
                + "Zip Code: " + customer.getZip() + "\n"
                + "Phone: " + customer.getPhone() + "\n"
                + "Email: " + customer.getEmail() + "\n"
                + "Service Day: " + customer.getServiceDay() + "\n"
                + "Amount Charged: $" + customer.getAmountCharged().setScale(2, java.math.RoundingMode.HALF_UP) + "\n"
                + "Notes: " + customer.getNotes() + "\n"
                + "\nThank you for choosing our pool service!";
    }

    private String getStatementSenderName() {
        if (employeeProfile != null) {
            return employeeProfile.getFullName();
        }
        if (companyProfile != null) {
            return companyProfile.getCompanyName();
        }
        return "Pool Service";
    }

    private String getStatementSenderEmail() {
        if (employeeProfile != null) {
            return valueOrEmpty(employeeProfile.getEmail());
        }
        if (companyProfile != null && companyProfile.getEmail() != null && !companyProfile.getEmail().isBlank()) {
            return companyProfile.getEmail().trim();
        }
        return pdfSettings == null ? "" : valueOrEmpty(pdfSettings.getCompanyEmail());
    }

    private String getStatementSenderPhone() {
        if (employeeProfile != null) {
            return valueOrEmpty(employeeProfile.getPhone());
        }
        if (pdfSettings != null && pdfSettings.getCompanyPhone() != null && !pdfSettings.getCompanyPhone().isBlank()) {
            return pdfSettings.getCompanyPhone().trim();
        }
        return companyProfile == null ? "" : valueOrEmpty(companyProfile.getPhone());
    }

    private void setNewCustomerId() {
        idField.setText(dbManager.getNextCustomerId());
    }

    private void formatPhoneField() {
        String digits = phoneField.getText().replaceAll("\\D", "");
        if (digits.length() > 10) {
            digits = digits.substring(0, 10);
        }
        if (digits.length() == 10) {
            phoneField.setText(digits.replaceFirst("(\\d{3})(\\d{3})(\\d{4})", "$1-$2-$3"));
        } else {
            phoneField.setText(digits);
        }
    }

    private void formatRecordAmountField() {
        String raw = recordAmountField.getText().trim().replaceAll("[^0-9.-]+", "");
        if (raw.isBlank() || raw.equals("-") || raw.equals("+")) {
            raw = "0";
        }
        try {
            BigDecimal amount = new BigDecimal(raw).setScale(2, java.math.RoundingMode.HALF_UP);
            recordAmountField.setText("$" + amount.toPlainString());
        } catch (Exception ignored) {
            recordAmountField.setText("$0.00");
        }
    }

    private void openStatementForCustomer(String customerId) {
        Customer customer = dbManager.getCustomerById(customerId);
        if (customer == null) {
            JOptionPane.showMessageDialog(this, "Customer record not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!canAccessCustomer(customer)) {
            JOptionPane.showMessageDialog(this, "You do not have access to that customer.", "Access Restricted", JOptionPane.WARNING_MESSAGE);
            return;
        }
        activeRecordCustomerId = customer.getId();
        recordCustomerLabel.setText("Customer: " + customer.getFullName());
        recordDateField.setText(LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy")));
        recordTypeCombo.setSelectedIndex(0);
        recordAmountField.setText("$0.00");
        loadStatementRecordsForCustomer(customer.getId());
        showScreen("Statements");
    }

    private void addStatementRecord() {
        if (!canEditStatementData()) {
            JOptionPane.showMessageDialog(this, "You have view-only access for statement data.", "Access Restricted", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (activeRecordCustomerId == null || activeRecordCustomerId.isBlank()) {
            JOptionPane.showMessageDialog(this, "Select a customer first by double-clicking a row.", "No Customer", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        formatRecordAmountField();
        String date = recordDateField.getText().trim();
        String type = extractRawRecordType(recordTypeCombo.getSelectedItem());
        
        // If type is Check, prompt for check number
        if ("Check".equalsIgnoreCase(type)) {
            String checkNumber = promptForCheckNumber();
            if (checkNumber == null) {
                // User cancelled
                return;
            }
            type = "Check: #" + checkNumber;
        }
        
        String rawAmount = recordAmountField.getText().trim().replaceAll("[^0-9.-]+", "");
        if (date.isBlank() || rawAmount.isBlank() || rawAmount.equals("-") || rawAmount.equals("+")) {
            JOptionPane.showMessageDialog(this, "Enter both date and amount for the record.", "Validation", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Amount must be a valid number.", "Validation", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (classifyRecordType(type) == RecordTypeCategory.DEBIT) {
            amount = amount.negate();
        }
        dbManager.addStatementRecord(activeRecordCustomerId, date, type, amount);
        loadStatementRecordsForCustomer(activeRecordCustomerId);
        recordAmountField.setText("$0.00");
    }

    private String promptForCheckNumber() {
        while (true) {
            String input = JOptionPane.showInputDialog(this, "Enter check number (3-12 digits):", "Check Number", JOptionPane.PLAIN_MESSAGE);
            if (input == null) {
                // User cancelled
                return null;
            }
            
            input = input.trim();
            
            // Validate: only numbers, 3-12 digits
            if (!input.matches("^[0-9]{3,12}$")) {
                JOptionPane.showMessageDialog(this, "Check number must contain 3-12 digits only.", "Invalid Check Number", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            
            return input;
        }
    }

    private void addCustomRecordType() {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) recordTypeCombo.getModel();
        
        // Create dialog
        JDialog dialog = new JDialog(this, "Manage Record Types", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setSize(350, 300);
        dialog.setLocationRelativeTo(this);
        
        // Create list model and list from raw DB types (without category prefixes)
        DefaultListModel<String> listModel = new DefaultListModel<>();
        List<String> dbTypes = dbManager.getRecordTypes();
        dbTypes.sort(String.CASE_INSENSITIVE_ORDER);
        for (String type : dbTypes) {
            listModel.addElement(type);
        }
        
        JList<String> typeList = new JList<>(listModel);
        typeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(typeList);
        
        // Create buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        
        JButton addButton = new JButton("Add");
        JButton editButton = new JButton("Edit");
        JButton deleteButton = new JButton("Delete");
        JButton applyButton = new JButton("Apply");
        JButton cancelButton = new JButton("Cancel");
        
        editButton.setEnabled(false);
        deleteButton.setEnabled(false);
        
        typeList.addListSelectionListener(e -> {
            boolean isSelected = typeList.getSelectedIndex() >= 0;
            editButton.setEnabled(isSelected);
            deleteButton.setEnabled(isSelected);
        });
        
        addButton.addActionListener(e -> {
            String newType = JOptionPane.showInputDialog(dialog, "Enter a new record type label:", "Add Type", JOptionPane.PLAIN_MESSAGE);
            if (newType != null) {
                newType = newType.trim();
                if (newType.isBlank()) {
                    JOptionPane.showMessageDialog(dialog, "Type label cannot be blank.", "Invalid", JOptionPane.WARNING_MESSAGE);
                } else if (listModel.contains(newType)) {
                    JOptionPane.showMessageDialog(dialog, "Type already exists.", "Duplicate", JOptionPane.WARNING_MESSAGE);
                } else {
                    listModel.addElement(newType);
                }
            }
        });
        
        editButton.addActionListener(e -> {
            int selectedIndex = typeList.getSelectedIndex();
            if (selectedIndex >= 0) {
                String oldType = listModel.getElementAt(selectedIndex);
                String newType = JOptionPane.showInputDialog(dialog, "Edit record type label:", oldType, JOptionPane.PLAIN_MESSAGE);
                if (newType != null) {
                    newType = newType.trim();
                    if (newType.isBlank()) {
                        JOptionPane.showMessageDialog(dialog, "Type label cannot be blank.", "Invalid", JOptionPane.WARNING_MESSAGE);
                    } else if (!newType.equals(oldType) && listModel.contains(newType)) {
                        JOptionPane.showMessageDialog(dialog, "Type already exists.", "Duplicate", JOptionPane.WARNING_MESSAGE);
                    } else {
                        listModel.setElementAt(newType, selectedIndex);
                    }
                }
            }
        });
        
        deleteButton.addActionListener(e -> {
            int selectedIndex = typeList.getSelectedIndex();
            if (selectedIndex >= 0) {
                String type = listModel.getElementAt(selectedIndex);
                int confirm = JOptionPane.showConfirmDialog(dialog, "Delete type '" + type + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    listModel.removeElementAt(selectedIndex);
                }
            }
        });
        
        applyButton.addActionListener(e -> {
            try {
                // Get original types from database
                List<String> originalTypes = dbManager.getRecordTypes();
                
                // Get new types from the list
                List<String> newTypes = new ArrayList<>();
                for (int i = 0; i < listModel.getSize(); i++) {
                    newTypes.add(listModel.getElementAt(i));
                }
                
                // Find types to delete (in original but not in new)
                for (String originalType : originalTypes) {
                    if (!newTypes.contains(originalType)) {
                        dbManager.deleteRecordType(originalType);
                    }
                }
                
                // Find types to add (in new but not in original)
                for (String newType : newTypes) {
                    if (!originalTypes.contains(newType)) {
                        dbManager.addRecordType(newType);
                    }
                }
                
                // Update combo box model with new list
                populateRecordTypeCombo(model, newTypes);
                reconcileStatementRecordAmountsForCurrentTypeRules();
                
                JOptionPane.showMessageDialog(dialog, "Record types saved successfully.", "Saved", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Error saving record types: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        
        cancelButton.addActionListener(e -> dialog.dispose());
        
        buttonPanel.add(addButton);
        buttonPanel.add(editButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(new JSeparator(SwingConstants.VERTICAL));
        buttonPanel.add(applyButton);
        buttonPanel.add(cancelButton);
        
        JPanel contentPanel = new JPanel(new BorderLayout(8, 8));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        contentPanel.add(new JLabel("Record Types:"), BorderLayout.NORTH);
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.setContentPane(contentPanel);
        dialog.setVisible(true);
    }

    private void loadRecordTypesFromDatabase() {
        try {
            dbManager.ensureDefaultTypes();
            List<String> types = dbManager.getRecordTypes();
            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) recordTypeCombo.getModel();
            populateRecordTypeCombo(model, types);
            if (!types.isEmpty()) {
                recordTypeCombo.setSelectedIndex(0);
            }
        } catch (Exception e) {
            // Fallback to default types if database fails
            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) recordTypeCombo.getModel();
            populateRecordTypeCombo(model, List.of("Bill", "Payment"));
        }
    }

    private enum RecordTypeCategory {
        DEBIT,
        CREDIT
    }

    private RecordTypeCategory classifyRecordType(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return RecordTypeCategory.DEBIT;
        }

        // Credit-like names increase the customer balance in the UI workflow.
        if (containsAnyKeyword(value, creditTypeKeywords)) {
            return RecordTypeCategory.CREDIT;
        }

        // Debit-like names reduce the customer balance in the UI workflow.
        if (containsAnyKeyword(value, debitTypeKeywords)) {
            return RecordTypeCategory.DEBIT;
        }

        return RecordTypeCategory.DEBIT;
    }

    private boolean containsAnyKeyword(String value, List<String> keywords) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && value.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void loadRecordTypeKeywords() {
        creditTypeKeywords = loadKeywordList(
                "recordTypeCreditKeywords",
                List.of("payment", "paid", "credit", "refund", "discount", "adjustment", "rebate", "deposit", "return", "transfer in", "check", "cheque")
        );
        debitTypeKeywords = loadKeywordList(
                "recordTypeDebitKeywords",
                List.of("cash", "bill", "charge", "service", "invoice", "fee", "debit", "purchase", "due", "owed", "repair", "labor", "parts", "materials")
        );
    }

    private List<String> loadKeywordList(String settingKey, List<String> defaults) {
        String defaultValue = String.join(", ", defaults);
        String savedValue = dbManager.getPdfSetting(settingKey, defaultValue);
        return parseKeywordList(savedValue, defaults);
    }

    private List<String> parseKeywordList(String value, List<String> defaults) {
        List<String> keywords = new ArrayList<>();
        if (value != null) {
            String[] parts = value.split("[,\n]");
            for (String part : parts) {
                String keyword = part.trim().toLowerCase(Locale.ROOT);
                if (!keyword.isBlank() && !keywords.contains(keyword)) {
                    keywords.add(keyword);
                }
            }
        }
        if (keywords.isEmpty()) {
            for (String keyword : defaults) {
                String normalized = keyword.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isBlank() && !keywords.contains(normalized)) {
                    keywords.add(normalized);
                }
            }
        }
        return keywords;
    }

    private String joinKeywordList(List<String> keywords) {
        return String.join(", ", keywords);
    }

    private void saveRecordTypeKeywords() {
        dbManager.savePdfSetting("recordTypeCreditKeywords", joinKeywordList(creditTypeKeywords));
        dbManager.savePdfSetting("recordTypeDebitKeywords", joinKeywordList(debitTypeKeywords));
    }

    private EmployeeProfile cloneEmployee(EmployeeProfile source) {
        EmployeeProfile employee = new EmployeeProfile();
        employee.setEmployeeId(source.getEmployeeId());
        employee.setCompanyId(source.getCompanyId());
        employee.setCompanyName(source.getCompanyName());
        employee.setCompanyDatabasePath(source.getCompanyDatabasePath());
        employee.setFirstName(source.getFirstName());
        employee.setLastName(source.getLastName());
        employee.setPhone(source.getPhone());
        employee.setEmail(source.getEmail());
        employee.setUsername(source.getUsername());
        employee.setPassword(source.getPassword());
        employee.setCanViewCustomerDetails(source.isCanViewCustomerDetails());
        employee.setCanViewCustomers(source.isCanViewCustomers());
        employee.setCanViewStatements(source.isCanViewStatements());
        employee.setCanViewRevenueSummary(source.isCanViewRevenueSummary());
        employee.setCanViewPdf(source.isCanViewPdf());
        employee.setCanEditPdf(source.isCanEditPdf());
        employee.setCanEditCustomers(source.isCanEditCustomers());
        employee.setCanEditStatements(source.isCanEditStatements());
        employee.setAllowedCustomerIds(new ArrayList<>(source.getAllowedCustomerIds()));
        employee.setAllowedDays(new ArrayList<>(source.getAllowedDays()));
        return employee;
    }

    private void manageRecordTypeKeywords() {
        if (!canEditStatementData()) {
            JOptionPane.showMessageDialog(this, "You have view-only access for statement data.", "Access Restricted", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JDialog dialog = new JDialog(this, "Manage Type Keywords", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setSize(520, 360);
        dialog.setLocationRelativeTo(this);

        JTextArea creditArea = new JTextArea(joinKeywordList(creditTypeKeywords), 6, 34);
        JTextArea debitArea = new JTextArea(joinKeywordList(debitTypeKeywords), 6, 34);
        creditArea.setLineWrap(true);
        creditArea.setWrapStyleWord(true);
        debitArea.setLineWrap(true);
        debitArea.setWrapStyleWord(true);

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel("Credit keywords (comma or newline separated):"), gbc);
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 0.5;
        formPanel.add(new JScrollPane(creditArea), gbc);

        gbc.gridy = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(new JLabel("Debit keywords (comma or newline separated):"), gbc);
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 0.5;
        formPanel.add(new JScrollPane(debitArea), gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("Save");
        JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);

        saveButton.addActionListener(e -> {
            List<String> newCreditKeywords = parseKeywordList(creditArea.getText(), creditTypeKeywords);
            List<String> newDebitKeywords = parseKeywordList(debitArea.getText(), debitTypeKeywords);
            creditTypeKeywords = newCreditKeywords;
            debitTypeKeywords = newDebitKeywords;
            saveRecordTypeKeywords();
            loadRecordTypesFromDatabase();
            reconcileStatementRecordAmountsForCurrentTypeRules();
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> dialog.dispose());

        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(formPanel, BorderLayout.CENTER);
        rootPanel.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setContentPane(rootPanel);
        dialog.setVisible(true);
    }

    private String toDisplayRecordType(String rawType) {
        RecordTypeCategory category = classifyRecordType(rawType);
        return (category == RecordTypeCategory.DEBIT ? DEBIT_PREFIX : CREDIT_PREFIX) + rawType;
    }

    private String extractRawRecordType(Object selectedItem) {
        String value = Objects.toString(selectedItem, "Bill").trim();
        if (value.startsWith(DEBIT_PREFIX)) {
            return value.substring(DEBIT_PREFIX.length()).trim();
        }
        if (value.startsWith(CREDIT_PREFIX)) {
            return value.substring(CREDIT_PREFIX.length()).trim();
        }
        return value;
    }

    private void populateRecordTypeCombo(DefaultComboBoxModel<String> model, List<String> rawTypes) {
        model.removeAllElements();

        List<String> debitTypes = new ArrayList<>();
        List<String> creditTypes = new ArrayList<>();

        for (String rawType : rawTypes) {
            if (rawType == null || rawType.isBlank()) {
                continue;
            }
            if (classifyRecordType(rawType) == RecordTypeCategory.DEBIT) {
                debitTypes.add(rawType);
            } else {
                creditTypes.add(rawType);
            }
        }

        debitTypes.sort(String.CASE_INSENSITIVE_ORDER);
        creditTypes.sort(String.CASE_INSENSITIVE_ORDER);

        for (String type : debitTypes) {
            model.addElement(toDisplayRecordType(type));
        }
        for (String type : creditTypes) {
            model.addElement(toDisplayRecordType(type));
        }
    }

    private void deleteSelectedRecord() {
        if (!canEditStatementData()) {
            JOptionPane.showMessageDialog(this, "You have view-only access for statement data.", "Access Restricted", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (activeRecordCustomerId == null || activeRecordCustomerId.isBlank()) {
            JOptionPane.showMessageDialog(this, "Select a customer first by double-clicking a row.", "No Customer", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int selectedRow = recordTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Select a statement record to delete.", "Select Record", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelIndex = recordTable.convertRowIndexToModel(selectedRow);
        if (modelIndex >= 0 && modelIndex < currentStatementRecords.size()) {
            int confirm = JOptionPane.showConfirmDialog(this, "Delete selected record?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                StatementRecord record = currentStatementRecords.get(modelIndex);
                dbManager.deleteStatementRecord(record.getId());
                loadStatementRecordsForCustomer(activeRecordCustomerId);
            }
        }
    }

    private void loadStatementRecordsForCustomer(String customerId) {
        recordTableModel.setRowCount(0);
        if (customerId == null || customerId.isBlank()) {
            recordCustomerLabel.setText("Customer: (select a customer first)");
            recordBalanceLabel.setText("Current Balance: $0.00");
            currentStatementRecords = new ArrayList<>();
            return;
        }
        Customer customer = dbManager.getCustomerById(customerId);
        if (customer != null && !canAccessCustomer(customer)) {
            recordCustomerLabel.setText("Customer: (access restricted)");
            recordBalanceLabel.setText("Current Balance: $0.00");
            currentStatementRecords = new ArrayList<>();
            return;
        }
        if (customer != null) {
            recordCustomerLabel.setText("Customer: " + customer.getFullName());
        }
        currentStatementRecords = dbManager.getStatementRecords(customerId);
        BigDecimal balance = BigDecimal.ZERO;
        for (StatementRecord record : currentStatementRecords) {
            BigDecimal lineAmount = record.getAmount();
            balance = balance.add(lineAmount);
            String displayAmount = (lineAmount.signum() < 0 ? "$-" : "$" ) + lineAmount.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            recordTableModel.addRow(new Object[]{record.getDate(), record.getType(), displayAmount});
        }
        recordBalanceLabel.setText("Current Balance: $" + balance.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        updateCustomerBalanceLabel(customerId);
    }

    private void updateCustomerBalanceLabel(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            customerBalanceLabel.setText("Current Balance: $0.00");
            return;
        }
        Customer customer = dbManager.getCustomerById(customerId);
        if (customer != null && !canAccessCustomer(customer)) {
            customerBalanceLabel.setText("Current Balance: $0.00");
            return;
        }
        BigDecimal balance = dbManager.getStatementBalance(customerId);
        customerBalanceLabel.setText("Current Balance: $" + balance.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    private void formatAmountField() {
        String text = amountField.getText().trim();
        if (text.isBlank()) {
            amountField.setText("$0.00");
            return;
        }
        try {
            BigDecimal amount = new BigDecimal(text.replaceAll("[^0-9.]", "")).setScale(2, java.math.RoundingMode.HALF_UP);
            amountField.setText("$" + amount.toPlainString());
        } catch (Exception e) {
            amountField.setText("$0.00");
        }
    }

    private void refreshRevenueSummary() {
        List<Customer> accessibleCustomers = filterAccessibleCustomers(dbManager.getAllCustomers());
        Map<String, BigDecimal> revenueByDay = new java.util.LinkedHashMap<>();
        for (String day : DAYS) {
            revenueByDay.put(day, BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        }
        BigDecimal totalRevenue = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        for (Customer customer : accessibleCustomers) {
            BigDecimal amount = customer.getAmountCharged().setScale(2, java.math.RoundingMode.HALF_UP);
            String day = customer.getServiceDay() == null ? "Unknown" : customer.getServiceDay();
            revenueByDay.put(day, revenueByDay.getOrDefault(day, BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP)).add(amount));
            totalRevenue = totalRevenue.add(amount);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Daily Revenue by Group:\n");
        builder.append("----------------------\n");
        for (String day : DAYS) {
            BigDecimal revenue = revenueByDay.getOrDefault(day, BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
            builder.append(String.format("%s: $%s\n", day, revenue));
        }
        builder.append("\nTotal Revenue: $").append(totalRevenue.setScale(2, java.math.RoundingMode.HALF_UP)).append("\n");
        revenueArea.setText(builder.toString());
    }

    private static class EmployeeEditorDialog extends JDialog {
        private final EmployeeProfile employeeProfile;
        private final JTextField firstNameField;
        private final JTextField lastNameField;
        private final JTextField phoneField;
        private final JTextField emailField;
        private final JTextField usernameField;
        private final JTextField currentPasswordField;
        private final JPasswordField passwordField;
        private final JPasswordField confirmPasswordField;
        private final JCheckBox customerDetailsCheck;
        private final JCheckBox customersCheck;
        private final JCheckBox statementsCheck;
        private final JCheckBox revenueCheck;
        private final JCheckBox pdfCheck;
        private final JCheckBox editPdfCheck;
        private final JCheckBox editCustomersCheck;
        private final JCheckBox editStatementsCheck;
        private final JList<String> customerList;
        private final List<Customer> availableCustomers;
        private final JList<String> dayList;
        private boolean saved;

        EmployeeEditorDialog(JFrame parent, CompanyProfile companyProfile, EmployeeProfile employeeProfile, List<Customer> availableCustomers) {
            super(parent, employeeProfile.getEmployeeId() == null ? "Add Employee" : "Edit Employee", true);
            this.employeeProfile = employeeProfile;
            this.availableCustomers = new ArrayList<>(availableCustomers);
            this.saved = false;

            setSize(760, 560);
            setLocationRelativeTo(parent);
            setLayout(new BorderLayout(10, 10));

            firstNameField = new JTextField(valueOrEmpty(employeeProfile.getFirstName()), 20);
            lastNameField = new JTextField(valueOrEmpty(employeeProfile.getLastName()), 20);
            phoneField = new JTextField(valueOrEmpty(employeeProfile.getPhone()), 20);
            emailField = new JTextField(valueOrEmpty(employeeProfile.getEmail()), 20);
            usernameField = new JTextField(valueOrEmpty(employeeProfile.getUsername()), 20);
            currentPasswordField = new JTextField(valueOrEmpty(employeeProfile.getPassword()), 20);
            currentPasswordField.setEditable(false);
            passwordField = new JPasswordField(20);
            confirmPasswordField = new JPasswordField(20);

            customerDetailsCheck = new JCheckBox("Customer Details", employeeProfile.isCanViewCustomerDetails());
            customersCheck = new JCheckBox("Customers", employeeProfile.isCanViewCustomers());
            statementsCheck = new JCheckBox("Statements", employeeProfile.isCanViewStatements());
            revenueCheck = new JCheckBox("Revenue Summary", employeeProfile.isCanViewRevenueSummary());
            pdfCheck = new JCheckBox("PDF", employeeProfile.isCanViewPdf());
            editPdfCheck = new JCheckBox("Can Edit PDF Settings", employeeProfile.isCanEditPdf());
            editCustomersCheck = new JCheckBox("Can Edit Customer Data", employeeProfile.isCanEditCustomers());
            editStatementsCheck = new JCheckBox("Can Edit Statement Data", employeeProfile.isCanEditStatements());

            DefaultListModel<String> customerModel = new DefaultListModel<>();
            for (Customer customer : this.availableCustomers) {
                customerModel.addElement(customer.getId() + " - " + customer.getFullName() + " (" + customer.getServiceDay() + ")");
            }
            customerList = new JList<>(customerModel);
            customerList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

            DefaultListModel<String> dayModel = new DefaultListModel<>();
            for (String day : DAYS) {
                dayModel.addElement(day);
            }
            dayList = new JList<>(dayModel);
            dayList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

            preselectCustomers();
            preselectDays();

            JPanel formPanel = new JPanel(new GridBagLayout());
            formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(6, 6, 6, 6);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            addField(formPanel, gbc, 0, "Employee ID:", new JLabel(employeeProfile.getEmployeeId() == null ? "Auto-generated" : String.valueOf(employeeProfile.getEmployeeId())));
            addField(formPanel, gbc, 1, "Company:", new JLabel(companyProfile.getCompanyName()));
            addField(formPanel, gbc, 2, "First Name:", firstNameField);
            addField(formPanel, gbc, 3, "Last Name:", lastNameField);
            addField(formPanel, gbc, 4, "Phone:", phoneField);
            addField(formPanel, gbc, 5, "Email:", emailField);
            addField(formPanel, gbc, 6, "Username:", usernameField);
            addField(formPanel, gbc, 7, "Current Password:", currentPasswordField);
            addField(formPanel, gbc, 8, employeeProfile.getEmployeeId() == null ? "Password:" : "New Password:", passwordField);
            addField(formPanel, gbc, 9, employeeProfile.getEmployeeId() == null ? "Confirm Password:" : "Confirm New Password:", confirmPasswordField);

            JPanel permissionsPanel = new JPanel(new GridLayout(0, 1, 4, 4));
            permissionsPanel.setBorder(BorderFactory.createTitledBorder("Views they can see"));
            permissionsPanel.add(customerDetailsCheck);
            permissionsPanel.add(customersCheck);
            permissionsPanel.add(statementsCheck);
            permissionsPanel.add(revenueCheck);
            permissionsPanel.add(pdfCheck);
            permissionsPanel.add(editPdfCheck);
            permissionsPanel.add(editCustomersCheck);
            permissionsPanel.add(editStatementsCheck);

            JPanel assignmentPanel = new JPanel(new GridLayout(1, 2, 10, 10));
            assignmentPanel.setBorder(BorderFactory.createTitledBorder("Assignments"));
            assignmentPanel.add(wrapWithTitle("Specific Customers", new JScrollPane(customerList)));
            assignmentPanel.add(wrapWithTitle("Allowed Days", new JScrollPane(dayList)));

            JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
            centerPanel.add(permissionsPanel, BorderLayout.NORTH);
            centerPanel.add(assignmentPanel, BorderLayout.CENTER);

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton saveButton = new JButton("Save Employee");
            JButton cancelButton = new JButton("Cancel");
            buttonPanel.add(saveButton);
            buttonPanel.add(cancelButton);

            saveButton.addActionListener(e -> saveEmployee());
            cancelButton.addActionListener(e -> dispose());

            JPanel contentPanel = new JPanel(new BorderLayout(10, 10));
            contentPanel.add(formPanel, BorderLayout.NORTH);
            contentPanel.add(centerPanel, BorderLayout.CENTER);

            JScrollPane contentScrollPane = new JScrollPane(contentPanel);
            contentScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            contentScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

            add(contentScrollPane, BorderLayout.CENTER);
            add(buttonPanel, BorderLayout.SOUTH);
        }

        private void addField(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent component) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            panel.add(new JLabel(label), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            panel.add(component, gbc);
        }

        private JPanel wrapWithTitle(String title, JComponent component) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createTitledBorder(title));
            panel.add(component, BorderLayout.CENTER);
            return panel;
        }

        private void preselectCustomers() {
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < availableCustomers.size(); i++) {
                if (employeeProfile.getAllowedCustomerIds().contains(availableCustomers.get(i).getId())) {
                    indexes.add(i);
                }
            }
            customerList.setSelectedIndices(indexes.stream().mapToInt(Integer::intValue).toArray());
        }

        private void preselectDays() {
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < DAYS.length; i++) {
                if (employeeProfile.getAllowedDays().contains(DAYS[i])) {
                    indexes.add(i);
                }
            }
            dayList.setSelectedIndices(indexes.stream().mapToInt(Integer::intValue).toArray());
        }

        private void saveEmployee() {
            String firstName = firstNameField.getText().trim();
            String lastName = lastNameField.getText().trim();
            String phone = phoneField.getText().trim();
            String email = emailField.getText().trim();
            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            String confirmPassword = new String(confirmPasswordField.getPassword());

            if (firstName.isBlank() || lastName.isBlank() || username.isBlank()) {
                JOptionPane.showMessageDialog(this, "First name, last name, and username are required.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (!customerDetailsCheck.isSelected() && !customersCheck.isSelected() && !statementsCheck.isSelected()
                    && !revenueCheck.isSelected() && !pdfCheck.isSelected()) {
                JOptionPane.showMessageDialog(this, "Choose at least one view permission.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (editCustomersCheck.isSelected() && !customerDetailsCheck.isSelected() && !customersCheck.isSelected()) {
                JOptionPane.showMessageDialog(this, "Customer edit permission requires Customer Details or Customers view access.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (editStatementsCheck.isSelected() && !statementsCheck.isSelected()) {
                JOptionPane.showMessageDialog(this, "Statement edit permission requires Statements view access.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (editPdfCheck.isSelected() && !pdfCheck.isSelected()) {
                JOptionPane.showMessageDialog(this, "PDF edit permission requires PDF view access.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (employeeProfile.getEmployeeId() == null || !password.isBlank() || !confirmPassword.isBlank()) {
                if (password.isBlank() || confirmPassword.isBlank()) {
                    JOptionPane.showMessageDialog(this, "Enter and confirm the employee password.", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (!password.equals(confirmPassword)) {
                    JOptionPane.showMessageDialog(this, "Employee passwords do not match.", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                employeeProfile.setPassword(password);
            }

            employeeProfile.setFirstName(firstName);
            employeeProfile.setLastName(lastName);
            employeeProfile.setPhone(phone);
            employeeProfile.setEmail(email);
            employeeProfile.setUsername(username);
            employeeProfile.setCanViewCustomerDetails(customerDetailsCheck.isSelected());
            employeeProfile.setCanViewCustomers(customersCheck.isSelected());
            employeeProfile.setCanViewStatements(statementsCheck.isSelected());
            employeeProfile.setCanViewRevenueSummary(revenueCheck.isSelected());
            employeeProfile.setCanViewPdf(pdfCheck.isSelected());
            employeeProfile.setCanEditPdf(editPdfCheck.isSelected());
            employeeProfile.setCanEditCustomers(editCustomersCheck.isSelected());
            employeeProfile.setCanEditStatements(editStatementsCheck.isSelected());

            List<String> customerIds = new ArrayList<>();
            for (int index : customerList.getSelectedIndices()) {
                customerIds.add(availableCustomers.get(index).getId());
            }
            employeeProfile.setAllowedCustomerIds(customerIds);
            employeeProfile.setAllowedDays(new ArrayList<>(dayList.getSelectedValuesList()));

            saved = true;
            dispose();
        }

        private String valueOrEmpty(String value) {
            return value == null ? "" : value;
        }

        boolean isSaved() {
            return saved;
        }

        EmployeeProfile getEmployeeProfile() {
            return employeeProfile;
        }
    }

    private static class DigitFilter extends DocumentFilter {
        private final int maxLength;

        public DigitFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
            if (string == null) {
                return;
            }
            String filtered = string.replaceAll("\\D", "");
            if ((fb.getDocument().getLength() + filtered.length()) <= maxLength) {
                super.insertString(fb, offset, filtered, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
            if (text == null) {
                return;
            }
            String filtered = text.replaceAll("\\D", "");
            if ((fb.getDocument().getLength() - length + filtered.length()) <= maxLength) {
                super.replace(fb, offset, length, filtered, attrs);
            }
        }
    }

    private static class CurrencyFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
            if (string == null) {
                return;
            }
            String filtered = string.replaceAll("[^0-9.-]", "");
            String newValue = fb.getDocument().getText(0, fb.getDocument().getLength());
            newValue = newValue.substring(0, offset) + filtered + newValue.substring(offset);
            if (isValidCurrency(newValue)) {
                super.insertString(fb, offset, filtered, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
            if (text == null) {
                return;
            }
            String filtered = text.replaceAll("[^0-9.-]", "");
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String newValue = current.substring(0, offset) + filtered + current.substring(offset + length);
            if (isValidCurrency(newValue)) {
                super.replace(fb, offset, length, filtered, attrs);
            }
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws javax.swing.text.BadLocationException {
            super.remove(fb, offset, length);
        }

        private boolean isValidCurrency(String value) {
            if (value.isBlank()) {
                return true;
            }
            if (value.indexOf('-') > 0) {
                return false;
            }
            if (value.indexOf('-') != value.lastIndexOf('-')) {
                return false;
            }
            if (value.indexOf('.') != value.lastIndexOf('.')) {
                return false;
            }
            if (value.startsWith(".")) {
                return false;
            }
            if (value.equals("-")) {
                return true;
            }
            if (value.startsWith("-")) {
                value = value.substring(1);
            }
            if (value.contains(".")) {
                int decimals = value.length() - value.indexOf('.') - 1;
                return decimals <= 2;
            }
            return true;
        }
    }
}
