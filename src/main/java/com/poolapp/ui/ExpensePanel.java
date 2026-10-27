package com.poolapp.ui;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.ExpenseScanner;
import com.poolapp.service.StatementPdfReader;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/** Local PDF expense matching with company-scoped, persisted keywords. */
public final class ExpensePanel extends JPanel {
    private final DatabaseManager database;
    private final StatementPdfReader pdfReader = new StatementPdfReader();
    private final ExpenseScanner scanner = new ExpenseScanner();
    private final JButton choosePdf = new JButton("Choose PDF");
    private final JButton addKeyword = new JButton("Add");
    private final JButton removeKeyword = new JButton("Remove");
    private final JTextField keywordInput = new JTextField(22);
    private final JLabel documentLabel = new JLabel("No PDF loaded");
    private final JLabel status = new JLabel("Choose a PDF to find matching expenses. PDFs are read locally.");
    private final JLabel matchedCount = new JLabel("Matched lines: 0");
    private final JLabel grandTotal = new JLabel("Grand total: $0.00");
    private final KeywordTableModel keywordModel = new KeywordTableModel();
    private final JTable keywordTable = new JTable(keywordModel);
    private final ExpenseKeywordSelection selectionStore;
    private final String selectionScope;
    private final java.util.Set<String> checked = new java.util.LinkedHashSet<>();
    private String pendingAdded;
    private final ExpenseTableModel resultModel = new ExpenseTableModel();
    private final JTable resultTable = new JTable(resultModel);
    private final TotalsTableModel totalsModel = new TotalsTableModel();
    private final JTable totalsTable = new JTable(totalsModel);
    private List<String> keywords = List.of();
    private List<ExpenseScanner.SourceLine> sourceLines;
    private List<ExpenseScanner.Match> matches = List.of();
    private boolean keywordsLoaded;
    private boolean keywordSaveBusy;
    private long scanGeneration;

    public ExpensePanel(DatabaseManager database) {
        this(database, "default", new ExpenseKeywordSelection());
    }

    public ExpensePanel(DatabaseManager database, String selectionScope, ExpenseKeywordSelection selectionStore) {
        super(new BorderLayout(8, 8));
        this.database = database;
        this.selectionScope = selectionScope;
        this.selectionStore = selectionStore;
        setBorder(BorderFactory.createTitledBorder("Expenses"));

        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filePanel.add(choosePdf);
        filePanel.add(documentLabel);

        JPanel keywordPanel = new JPanel(new BorderLayout(6, 6));
        keywordPanel.setBorder(BorderFactory.createTitledBorder("Company-wide keywords"));
        JPanel keywordControls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        keywordControls.add(new JLabel("Keyword (up to 80 characters):"));
        keywordControls.add(keywordInput);
        keywordControls.add(addKeyword);
        keywordControls.add(removeKeyword);
        keywordPanel.add(keywordControls, BorderLayout.NORTH);
        keywordPanel.add(new JLabel("Check the saved keywords to search for. Select a row and click Remove to delete it for everyone."), BorderLayout.SOUTH);
        keywordTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keywordTable.getColumnModel().getColumn(0).setPreferredWidth(70);
        keywordTable.getColumnModel().getColumn(0).setMaxWidth(90);
        keywordTable.getColumnModel().getColumn(1).setPreferredWidth(600);
        JScrollPane keywordScroll = new JScrollPane(keywordTable);
        keywordScroll.setPreferredSize(new Dimension(700, 110));
        keywordPanel.add(keywordScroll, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout(4, 4));
        north.add(filePanel, BorderLayout.NORTH);
        north.add(keywordPanel, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        resultTable.setRowHeight(58);
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(62);
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(55);
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(620);
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(115);
        resultTable.getColumnModel().getColumn(3).setCellRenderer(new WrappedTextRenderer());
        JScrollPane resultScroll = new JScrollPane(resultTable);
        resultScroll.setPreferredSize(new Dimension(950, 330));
        JPanel results = new JPanel(new BorderLayout(4, 4));
        results.add(matchedCount, BorderLayout.NORTH);
        results.add(resultScroll, BorderLayout.CENTER);

        totalsTable.setFillsViewportHeight(true);
        totalsTable.setPreferredScrollableViewportSize(new Dimension(950, 110));
        JScrollPane totalsScroll = new JScrollPane(totalsTable);
        JPanel summary = new JPanel(new BorderLayout(4, 4));
        summary.setBorder(BorderFactory.createTitledBorder("Totals by keyword"));
        summary.add(totalsScroll, BorderLayout.CENTER);
        grandTotal.setFont(grandTotal.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        summary.add(grandTotal, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, results, summary);
        split.setResizeWeight(0.76);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        choosePdf.addActionListener(event -> choosePdf());
        addKeyword.addActionListener(event -> addKeyword());
        keywordInput.addActionListener(event -> addKeyword());
        removeKeyword.addActionListener(event -> removeKeyword());
        keywordTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) removeKeyword.setEnabled(keywordsLoaded && !keywordSaveBusy && keywordTable.getSelectedRow() >= 0);
        });
        updateKeywordControls();
        loadKeywords();
    }

    public void refresh() {
        if (keywordsLoaded) rescanLoadedPdf();
    }

    private void loadKeywords() {
        updateKeywordControls();
        status.setText("Loading saved expense keywords...");
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                return database.getExpenseKeywords();
            }
            @Override protected void done() {
                try {
                    keywords = get();
                    keywordsLoaded = true;
                    checked.clear();
                    checked.addAll(selectionStore.load(selectionScope, keywords));
                    renderKeywords();
                    status.setText("Saved keywords loaded. Check the keywords to search for, then choose a PDF.");
                    updateKeywordControls();
                    rescanLoadedPdf();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("Loading saved expense keywords was interrupted.");
                } catch (ExecutionException e) {
                    showError(message(e.getCause(), "Unable to load saved expense keywords."));
                }
            }
        }.execute();
    }

    private void choosePdf() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Expense PDF");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files", "pdf"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        choosePdf.setEnabled(false);
        status.setText("Extracting text locally. The PDF will not be uploaded...");
        new SwingWorker<PdfData, Void>() {
            @Override protected PdfData doInBackground() throws Exception {
                StatementPdfReader.TextExtraction extraction = pdfReader.extractTextDocument(file.toPath());
                return new PdfData(extraction.rows(), extraction.pageCount());
            }
            @Override protected void done() {
                choosePdf.setEnabled(true);
                try {
                    PdfData data = get();
                    sourceLines = data.rows().stream().map(row -> new ExpenseScanner.SourceLine(row.page(), row.text())).toList();
                    documentLabel.setText(file.getName() + " - " + data.pageCount() + " page(s)");
                    if (keywordsLoaded) rescanLoadedPdf();
                    else status.setText("PDF loaded. Waiting for saved keywords...");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("PDF extraction was interrupted.");
                } catch (ExecutionException e) {
                    showError(message(e.getCause(), "Unable to extract this PDF."));
                }
            }
        }.execute();
    }

    private void addKeyword() {
        if (!keywordsLoaded || keywordSaveBusy) return;
        String entered = keywordInput.getText();
        if (entered == null || entered.trim().isEmpty()) {
            status.setText("Enter a keyword to add.");
            return;
        }
        List<String> candidate = new ArrayList<>(keywords);
        candidate.add(entered);
        try {
            candidate = new ArrayList<>(scanner.normalizeKeywords(candidate));
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        if (candidate.equals(keywords)) {
            keywordInput.setText("");
            status.setText("That keyword is already saved.");
            return;
        }
        pendingAdded = entered.trim().replaceAll("\\s+", " ");
        saveKeywords(candidate, true);
    }

    private void removeKeyword() {
        if (!keywordsLoaded || keywordSaveBusy) return;
        int selectedRow = keywordTable.getSelectedRow();
        String selected = selectedRow < 0 ? null : keywords.get(keywordTable.convertRowIndexToModel(selectedRow));
        if (selected == null) {
            status.setText("Select a saved keyword to remove.");
            return;
        }
        List<String> candidate = new ArrayList<>(keywords);
        candidate.remove(selected);
        saveKeywords(candidate, false);
    }

    private void saveKeywords(List<String> candidate, boolean clearInput) {
        keywordSaveBusy = true;
        updateKeywordControls();
        status.setText("Saving company-wide expense keywords...");
        List<String> requested = List.copyOf(candidate);
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                return database.saveExpenseKeywords(requested);
            }
            @Override protected void done() {
                keywordSaveBusy = false;
                String added = pendingAdded;
                pendingAdded = null;
                try {
                    keywords = get();
                    if (added != null) keywords.stream().filter(k -> k.equalsIgnoreCase(added)).findFirst().ifPresent(checked::add);
                    checked.retainAll(keywords);
                    selectionStore.save(selectionScope, checked);
                    renderKeywords();
                    if (clearInput) keywordInput.setText("");
                    status.setText("Expense keywords saved.");
                    updateKeywordControls();
                    rescanLoadedPdf();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateKeywordControls();
                    showError("Saving expense keywords was interrupted.");
                } catch (ExecutionException e) {
                    updateKeywordControls();
                    showError(message(e.getCause(), "Unable to save expense keywords."));
                }
            }
        }.execute();
    }

    private void rescanLoadedPdf() {
        if (sourceLines == null || !keywordsLoaded) return;
        long task = ++scanGeneration;
        List<ExpenseScanner.SourceLine> lines = sourceLines;
        List<String> savedKeywords = checkedKeywords();
        if (savedKeywords.isEmpty()) {
            ++scanGeneration;
            matches = List.of();
            resultModel.setMatches(matches);
            matchedCount.setText("Matched lines: 0");
            refreshTotals();
            status.setText("Check at least one saved keyword to search this PDF.");
            return;
        }
        status.setText("Scanning extracted text for the checked keywords...");
        new SwingWorker<List<ExpenseScanner.Match>, Void>() {
            @Override protected List<ExpenseScanner.Match> doInBackground() {
                return scanner.scan(lines, savedKeywords);
            }
            @Override protected void done() {
                if (task != scanGeneration) return;
                try {
                    matches = get();
                    resultModel.setMatches(matches);
                    matchedCount.setText("Matched lines: " + matches.size());
                    refreshTotals();
                    status.setText(matches.isEmpty() ? "No lines matched the checked keywords." :
                            "Review matching lines. Lines without an amount are shown as ‘no amount’ and excluded from totals.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("Expense scanning was interrupted.");
                } catch (ExecutionException e) {
                    showError(message(e.getCause(), "Unable to scan the extracted PDF text."));
                }
            }
        }.execute();
    }

    private void renderKeywords() {
        keywordModel.fireTableDataChanged();
    }

    private List<String> checkedKeywords() {
        return keywords.stream().filter(checked::contains).toList();
    }

    private void setChecked(String keyword, boolean value) {
        if (value) checked.add(keyword); else checked.remove(keyword);
        selectionStore.save(selectionScope, checked);
        rescanLoadedPdf();
    }

    private void updateKeywordControls() {
        boolean enabled = keywordsLoaded && !keywordSaveBusy;
        keywordInput.setEnabled(enabled);
        addKeyword.setEnabled(enabled);
        keywordTable.setEnabled(enabled);
        removeKeyword.setEnabled(enabled && keywordTable.getSelectedRow() >= 0);
    }

    private void refreshTotals() {
        ExpenseScanner.Summary summary = scanner.summarize(matches, match -> resultModel.isIncluded(match), checkedKeywords());
        totalsModel.setSummary(summary);
        grandTotal.setText("Grand total: " + formatMoney(summary.grandTotalCents()));
    }

    private void showError(String message) {
        status.setText(message);
        JOptionPane.showMessageDialog(this, message, "Expenses", JOptionPane.ERROR_MESSAGE);
    }

    private static String message(Throwable error, String fallback) {
        return error != null && error.getMessage() != null && !error.getMessage().isBlank() ? error.getMessage() : fallback;
    }

    private static String formatMoney(long cents) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(BigDecimal.valueOf(cents, 2));
    }

    private record PdfData(List<StatementPdfReader.TextRow> rows, int pageCount) { }

    private final class ExpenseTableModel extends AbstractTableModel {
        private final String[] columns = {"Include", "Page", "Keyword", "Line text", "Amount"};
        private final List<Row> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : Object.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Object getValueAt(int row, int column) {
            ExpenseScanner.Match match = rows.get(row).match;
            return switch (column) {
                case 0 -> rows.get(row).included;
                case 1 -> match.page();
                case 2 -> match.keyword();
                case 3 -> match.text();
                case 4 -> match.amountCents() == null ? "no amount" : formatMoney(match.amountCents());
                default -> "";
            };
        }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column != 0) return;
            rows.get(row).included = Boolean.TRUE.equals(value);
            fireTableCellUpdated(row, column);
            refreshTotals();
        }
        void setMatches(List<ExpenseScanner.Match> values) {
            rows.clear();
            for (ExpenseScanner.Match value : values) rows.add(new Row(value));
            fireTableDataChanged();
        }
        boolean isIncluded(ExpenseScanner.Match match) {
            return rows.stream().anyMatch(row -> row.match == match && row.included);
        }
        private final class Row {
            final ExpenseScanner.Match match;
            boolean included = true;
            Row(ExpenseScanner.Match match) { this.match = match; }
        }
    }

    private final class KeywordTableModel extends AbstractTableModel {
        private final String[] columns = {"Search", "Keyword"};
        @Override public int getRowCount() { return keywords.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0 && keywordsLoaded && !keywordSaveBusy; }
        @Override public Object getValueAt(int row, int column) {
            String keyword = keywords.get(row);
            return column == 0 ? checked.contains(keyword) : keyword;
        }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column != 0) return;
            setChecked(keywords.get(row), Boolean.TRUE.equals(value));
            fireTableCellUpdated(row, column);
        }
    }

    private static final class TotalsTableModel extends AbstractTableModel {
        private final String[] columns = {"Keyword", "Count", "Total"};
        private List<java.util.Map.Entry<String, ExpenseScanner.KeywordTotal>> rows = List.of();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            var entry = rows.get(row);
            return switch (column) {
                case 0 -> entry.getKey();
                case 1 -> entry.getValue().count();
                case 2 -> formatMoney(entry.getValue().totalCents());
                default -> "";
            };
        }
        void setSummary(ExpenseScanner.Summary summary) {
            rows = new ArrayList<>(summary.byKeyword().entrySet());
            fireTableDataChanged();
        }
    }

    private static final class WrappedTextRenderer extends JTextArea implements TableCellRenderer {
        WrappedTextRenderer() { setLineWrap(true); setWrapStyleWord(true); setOpaque(true); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focused, int row, int column) {
            setText(value == null ? "" : value.toString());
            setFont(table.getFont());
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            return this;
        }
    }
}
