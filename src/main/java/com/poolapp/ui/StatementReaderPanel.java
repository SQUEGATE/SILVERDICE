package com.poolapp.ui;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.StatementReaderStore;
import com.poolapp.model.Customer;
import com.poolapp.service.StatementPdfReader;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

/** Desktop statement reader. PDFs are extracted locally; only bounded text rows reach the API. */
public final class StatementReaderPanel extends JPanel {
    private final DatabaseManager database;
    private final StatementReaderStore store;
    private final Predicate<Customer> accessible;
    private final boolean canImport;
    private final StatementPdfReader reader = new StatementPdfReader();
    private final JButton upload = new JButton("Choose Statement PDF");
    private final JButton importSelected = new JButton("Import Selected Payments");
    private final JLabel documentLabel = new JLabel("No statement loaded");
    private final JLabel status = new JLabel("Choose a searchable statement PDF to begin.");
    private final JComboBox<String> customerChoices = new JComboBox<>();
    private final ReaderTableModel tableModel = new ReaderTableModel();
    private final JTable table = new JTable(tableModel);
    private final Map<String, String> customerIdsByDisplay = new HashMap<>();
    private final Map<String, String> customerDisplayById = new HashMap<>();
    private List<Customer> customers = List.of();
    private StatementPdfReader.Extraction extraction;
    private long generation;
    private boolean busy;

    public StatementReaderPanel(DatabaseManager db, Predicate<Customer> accessible, boolean canImport) {
        super(new BorderLayout(8, 8));
        this.database = db;
        this.store = new StatementReaderStore(db);
        this.accessible = accessible == null ? customer -> true : accessible;
        this.canImport = canImport;
        setBorder(BorderFactory.createTitledBorder("Statement Reader"));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(upload);
        controls.add(documentLabel);
        controls.add(importSelected);
        importSelected.setEnabled(false);
        importSelected.setVisible(canImport);
        add(controls, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setRowHeight(58);
        table.getColumnModel().getColumn(0).setPreferredWidth(55);
        table.getColumnModel().getColumn(1).setPreferredWidth(105);
        table.getColumnModel().getColumn(2).setPreferredWidth(85);
        table.getColumnModel().getColumn(3).setPreferredWidth(210);
        table.getColumnModel().getColumn(4).setPreferredWidth(75);
        table.getColumnModel().getColumn(5).setPreferredWidth(100);
        table.getColumnModel().getColumn(6).setPreferredWidth(620);
        table.getColumnModel().getColumn(6).setCellRenderer(new WrappedTextRenderer());
        table.getColumnModel().getColumn(3).setCellEditor(new CustomerEditor());
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JCheckBox()));
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JCheckBox()));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(1000, 530));
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        upload.addActionListener(event -> choosePdf());
        importSelected.addActionListener(event -> importRows());
    }

    public void refresh() {
        if (busy) return;
        if (extraction == null) {
            setStatus("Choose a searchable statement PDF to begin.");
            return;
        }
        preview(extraction, ++generation, documentLabel.getText());
    }

    private void choosePdf() {
        if (busy) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Statement PDF");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files", "pdf"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        long task = ++generation;
        setBusy(true, "Extracting text locally. The PDF will not be uploaded...");
        new SwingWorker<StatementPdfReader.Extraction, Void>() {
            @Override protected StatementPdfReader.Extraction doInBackground() throws Exception {
                return reader.extract(file.toPath());
            }
            @Override protected void done() {
                if (task != generation) return;
                try {
                    StatementPdfReader.Extraction parsed = get();
                    extraction = new StatementPdfReader.Extraction(parsed.documentHash(), parsed.pageCount(),
                            StatementPdfReader.zelleDepositRows(parsed.rows()));
                    documentLabel.setText(file.getName() + " - " + extraction.pageCount() + " page(s), " + extraction.rows().size() + " row(s)");
                    preview(extraction, task, documentLabel.getText());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failTask("Statement extraction was interrupted.", e);
                } catch (ExecutionException e) {
                    failTask(message(e.getCause(), "Unable to extract this PDF."), e.getCause());
                }
            }
        }.execute();
    }

    private void preview(StatementPdfReader.Extraction document, long task, String label) {
        setBusy(true, "Matching extracted rows against accessible customers...");
        new SwingWorker<PreviewData, Void>() {
            @Override protected PreviewData doInBackground() {
                List<Customer> loaded = database.getAllCustomers().stream().filter(accessible).toList();
                List<StatementReaderStore.PreviewRow> rows = store.preview(document.documentHash(), document.rows(), loaded);
                return new PreviewData(loaded, rows);
            }
            @Override protected void done() {
                if (task != generation) return;
                try {
                    PreviewData data = get();
                    customers = data.customers();
                    buildCustomerChoices(customers);
                    tableModel.setRows(data.rows());
                    setBusy(false, data.rows().isEmpty()
                            ? "No incoming Zelle or Zel deposit rows were found. Other transactions are hidden."
                            : "Only incoming Zelle/Zel deposits are shown. Review dates, customer matches, and duplicate warnings before importing.");
                    importSelected.setEnabled(canImport && tableModel.hasSelectedRows());
                    documentLabel.setText(label);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failTask("Statement preview was interrupted.", e);
                } catch (ExecutionException e) {
                    failTask(message(e.getCause(), "Unable to preview statement rows."), e.getCause());
                }
            }
        }.execute();
    }

    private void importRows() {
        if (busy || !canImport) return;
        tableModel.stopEditing();
        List<StatementReaderStore.PreviewRow> selected = tableModel.selectedForImport();
        if (selected.isEmpty()) {
            setStatus("Select at least one valid deposit row to import.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Import " + selected.size() + " selected payment(s) as \"" + StatementReaderStore.PAYMENT_TYPE
                        + "\"? Deposits will be recorded as negative statement amounts.",
                "Confirm Statement Import", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) return;
        long task = ++generation;
        setBusy(true, "Importing selected payments...");
        new SwingWorker<StatementReaderStore.ImportResult, Void>() {
            @Override protected StatementReaderStore.ImportResult doInBackground() {
                return store.importRows(extraction.documentHash(), selected, customers);
            }
            @Override protected void done() {
                if (task != generation) return;
                try {
                    StatementReaderStore.ImportResult result = get();
                    setStatus("Import complete: " + result.imported() + " imported, " + result.skipped() + " skipped. Refreshing duplicate status...");
                    preview(extraction, task, documentLabel.getText());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failTask("Statement import was interrupted. Retrying this same PDF is safe.", e);
                } catch (ExecutionException e) {
                    failTask(message(e.getCause(), "Unable to import statement rows. Retry the same PDF after resolving the issue."), e.getCause());
                }
            }
        }.execute();
    }

    private void buildCustomerChoices(List<Customer> values) {
        customerIdsByDisplay.clear();
        customerDisplayById.clear();
        for (Customer customer : values) {
            String display = customer.getFullName() + " (" + customer.getId() + ")";
            customerIdsByDisplay.put(display, customer.getId());
            customerDisplayById.put(customer.getId(), display);
        }
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("-- Select customer manually --");
        customerDisplayById.values().forEach(model::addElement);
        customerChoices.setModel(model);
    }

    private void failTask(String message, Throwable cause) {
        setBusy(false, message);
        importSelected.setEnabled(canImport && tableModel.hasSelectedRows());
        JOptionPane.showMessageDialog(this, message, "Statement Reader", JOptionPane.ERROR_MESSAGE);
        if (cause != null) cause.printStackTrace();
    }

    private static String message(Throwable error, String fallback) {
        return error != null && error.getMessage() != null && !error.getMessage().isBlank() ? error.getMessage() : fallback;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        upload.setEnabled(!value);
        importSelected.setEnabled(!value && canImport && tableModel.hasSelectedRows());
        table.setEnabled(!value);
        setStatus(message);
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    private record PreviewData(List<Customer> customers, List<StatementReaderStore.PreviewRow> rows) { }

    private final class CustomerEditor extends DefaultCellEditor {
        private final JComboBox<String> choices;
        CustomerEditor() {
            super(customerChoices);
            choices = customerChoices;
            choices.addItem("-- Select customer manually --");
        }
        @Override public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            choices.setSelectedItem(value == null || value.toString().isBlank()
                    ? "-- Select customer manually --" : value.toString());
            return choices;
        }
    }

    private final class ReaderTableModel extends DefaultTableModel {
        private final List<StatementReaderStore.PreviewRow> rows = new ArrayList<>();
        private final List<Boolean> checked = new ArrayList<>();
        private final List<Boolean> override = new ArrayList<>();
        private final String[] columns = {"Import", "Date", "Amount", "Customer", "Override duplicate", "Status", "Page / raw source / reason"};

        ReaderTableModel() { super(); setColumnIdentifiers(columns); }

        void setRows(List<StatementReaderStore.PreviewRow> values) {
            rows.clear(); rows.addAll(values);
            checked.clear(); override.clear();
            for (StatementReaderStore.PreviewRow row : values) {
                checked.add("ready".equals(row.status()));
                override.add(false);
            }
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows == null ? 0 : rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) {
            return column == 0 || column == 4 ? Boolean.class : String.class;
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            StatementReaderStore.PreviewRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> checked.get(rowIndex);
                case 1 -> row.date();
                case 2 -> row.amount() == null ? "" : row.amount().toPlainString();
                case 3 -> customerDisplayById.getOrDefault(row.customerId(), "");
                case 4 -> override.get(rowIndex);
                case 5 -> row.status().toUpperCase() + ": " + row.reason();
                case 6 -> "Page " + row.page() + ", line " + row.line() + " | " + row.text();
                default -> "";
            };
        }
        @Override public boolean isCellEditable(int row, int column) {
            String state = rows.get(row).status();
            boolean actionable = state.equals("ready") || state.equals("review") || state.equals("duplicate");
            if (column == 0) return actionable && canImport;
            if (!actionable) return false;
            return column == 1 || column == 2 || column == 3 || (column == 4 && state.equals("duplicate"));
        }
        @Override public void setValueAt(Object value, int rowIndex, int columnIndex) {
            StatementReaderStore.PreviewRow row = rows.get(rowIndex);
            switch (columnIndex) {
                case 0 -> checked.set(rowIndex, Boolean.TRUE.equals(value));
                case 1 -> {
                    String valueDate = String.valueOf(value);
                    String normalized = StatementReaderStore.parseDate(valueDate, java.time.LocalDate.now().getYear());
                    replace(rowIndex, row.sourceId(), normalized.isBlank() ? valueDate : normalized, row.amount(),
                            row.customerId(), row.candidateIds(), row.status(), row.reason(), row.reference(), override.get(rowIndex));
                }
                case 2 -> replaceAmount(rowIndex, String.valueOf(value));
                case 3 -> {
                    String id = customerIdsByDisplay.getOrDefault(String.valueOf(value), "");
                    String state = id.isBlank() ? "review" : row.status().equals("duplicate") ? "duplicate" : "ready";
                    replace(rowIndex, row.sourceId(), row.date(), row.amount(), id, row.candidateIds(), state,
                            id.isBlank() ? "Select a customer manually." : "Customer selected manually.", row.reference(), override.get(rowIndex));
                }
                case 4 -> override.set(rowIndex, Boolean.TRUE.equals(value));
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
            importSelected.setEnabled(canImport && hasSelectedRows());
        }

        private void replaceAmount(int index, String value) {
            StatementReaderStore.PreviewRow row = rows.get(index);
            BigDecimal amount = StatementReaderStore.parsePositiveAmount(value);
            replace(index, row.sourceId(), row.date(), amount, row.customerId(), row.candidateIds(),
                    row.status(), row.reason(), row.reference(), override.get(index));
        }

        private void replace(int index, String sourceId, String date, BigDecimal amount, String customerId,
                             List<String> candidates, String state, String reason, String reference, boolean overrideDuplicate) {
            StatementReaderStore.PreviewRow old = rows.get(index);
            rows.set(index, new StatementReaderStore.PreviewRow(sourceId, old.page(), old.line(), old.text(),
                    date, amount, customerId, candidates, state, reason, reference, overrideDuplicate));
        }

        boolean hasSelectedRows() { return !selectedForImport().isEmpty(); }
        void stopEditing() {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
        }
        List<StatementReaderStore.PreviewRow> selectedForImport() {
            List<StatementReaderStore.PreviewRow> selected = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                StatementReaderStore.PreviewRow row = rows.get(i);
                if (!checked.get(i) || row.status().equals("imported") || row.status().equals("ignored")) continue;
                String normalizedDate = StatementReaderStore.parseDate(row.date(), java.time.LocalDate.now().getYear());
                if (row.customerId().isBlank() || normalizedDate.isBlank() || row.amount() == null) continue;
                selected.add(new StatementReaderStore.PreviewRow(row.sourceId(), row.page(), row.line(), row.text(),
                        normalizedDate, row.amount(), row.customerId(), row.candidateIds(), row.status(), row.reason(),
                        row.reference(), override.get(i)));
            }
            return selected;
        }
    }

    private static final class WrappedTextRenderer extends DefaultTableCellRenderer {
        private final JTextArea text = new JTextArea();
        WrappedTextRenderer() {
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setOpaque(true);
            text.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focus, int row, int column) {
            text.setText(value == null ? "" : value.toString());
            text.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            text.setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            return text;
        }
    }
}
