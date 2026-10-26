package com.poolapp.ui;

import com.poolapp.db.DatabaseManager;
import com.poolapp.db.SalesQuoteStore;
import com.poolapp.model.Customer;
import com.poolapp.model.SalesQuote;
import com.poolapp.service.SalesQuotePdf;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class SalesQuotesPanel extends JPanel {
    private final DatabaseManager database;
    private final SalesQuoteStore store;
    private final Predicate<Customer> accessible;
    private final boolean canConvert;
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final JTextField search = new JTextField(20);
    private final JComboBox<String> day = new JComboBox<>(new String[]{
            "All", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"});
    private final DefaultListModel<Customer> customerModel = new DefaultListModel<>();
    private final JList<Customer> customers = new JList<>(customerModel);
    private final JComboBox<Choice> history = new JComboBox<>();
    private final JLabel heading = new JLabel(" ");
    private final JLabel number = new JLabel("Number assigned when saved");
    private final JLabel total = new JLabel("Total: 0.00");
    private final JLabel status = new JLabel(" ");
    private final JTextField quoteDate = new JTextField(SalesQuote.today(), 10);
    private final JTextField lineDate = new JTextField(SalesQuote.today(), 10);
    private final JComboBox<String> type = new JComboBox<>();
    private final JTextArea description = new JTextArea(2, 28);
    private final JTextField amount = new JTextField(12);
    private final JTextArea notes = new JTextArea(4, 30);
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Date", "Type", "Description", "Signed amount"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(tableModel);
    private final List<SalesQuote.Line> lines = new ArrayList<>();
    private final List<JComponent> editors = new ArrayList<>();
    private final JButton save = new JButton("Save");
    private final JButton delete = new JButton("Delete");
    private final JButton convert = new JButton("Convert to Statement");
    private final JButton pdf = new JButton("Export PDF");
    private final JButton newQuote = new JButton("New Quote");
    private final JButton back = new JButton("Back to Customers");
    private Customer customer;
    private SalesQuote selected;
    private Map<String, String> settings = Map.of();
    private boolean rebuilding;
    private boolean busy;
    private long generation;
    private int historyOffset;
    private int historyTotal;
    private final JButton previousHistory = new JButton("Previous 50");
    private final JButton nextHistory = new JButton("Next 50");
    private final JLabel historyPage = new JLabel(" ");
    private int editingLine = -1;

    public SalesQuotesPanel(DatabaseManager database, Predicate<Customer> accessible, boolean canConvert) {
        super(new BorderLayout(8, 8));
        this.database = database; this.store = new SalesQuoteStore(database);
        this.accessible = accessible; this.canConvert = canConvert;
        setBorder(BorderFactory.createTitledBorder("Sales Quotes"));
        pages.add(customerPage(), "customers");
        pages.add(quotePage(), "quote");
        add(pages, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
    }

    public void refresh() {
        if (busy) return;
        if (customer == null) searchCustomers();
        else if (!hasChanges()) loadCustomer(customer);
    }

    private JPanel customerPage() {
        JPanel page = new JPanel(new BorderLayout(6, 6));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton find = new JButton("Search");
        filters.add(new JLabel("Customer:")); filters.add(search); filters.add(new JLabel("Day:"));
        filters.add(day); filters.add(find);
        page.add(filters, BorderLayout.NORTH); page.add(new JScrollPane(customers), BorderLayout.CENTER);
        JButton open = new JButton("Open Customer Quotes"); page.add(open, BorderLayout.SOUTH);
        customers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        customers.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Customer c) label.setText(c.getId() + " - " + c.getFullName() + " (" + c.getServiceDay() + ")");
                return label;
            }
        });
        find.addActionListener(e -> searchCustomers()); search.addActionListener(e -> searchAcrossDays());
        day.addActionListener(e -> searchCustomers());
        open.addActionListener(e -> {
            if (!busy && customers.getSelectedValue() != null) loadCustomer(customers.getSelectedValue());
        });
        customers.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) open.doClick();
            }
        });
        return page;
    }

    private JPanel quotePage() {
        JPanel page = new JPanel(new BorderLayout(6, 6));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 17));
        form.add(heading);
        form.add(row("Saved quotes:", history));
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.LEFT));
        paging.add(previousHistory); paging.add(historyPage); paging.add(nextHistory);
        form.add(paging);
        previousHistory.addActionListener(e -> changeHistoryPage(-50));
        nextHistory.addActionListener(e -> changeHistoryPage(50));
        form.add(number);
        form.add(row("Quote date (MM/DD/YYYY):", quoteDate));
        form.add(new JLabel("Notes:")); form.add(new JScrollPane(notes));
        notes.setLineWrap(true); notes.setWrapStyleWord(true);
        form.add(new JLabel("Items — DEBIT / charge = negative; CREDIT / payment = positive"));
        JScrollPane rows = new JScrollPane(table);
        rows.setPreferredSize(new Dimension(650, 210)); form.add(rows);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        form.add(row("Line date (MM/DD/YYYY):", lineDate));
        form.add(row("Type:", type));
        form.add(new JLabel("Description:")); form.add(new JScrollPane(description));
        description.setLineWrap(true); description.setWrapStyleWord(true);
        form.add(row("Amount (positive magnitude):", amount));
        JPanel lineButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addLine = new JButton("Add / Update Line");
        JButton editLine = new JButton("Edit Selected");
        JButton removeLine = new JButton("Remove Selected");
        JButton clearLine = new JButton("Clear Line Form");
        lineButtons.add(addLine); lineButtons.add(editLine); lineButtons.add(removeLine); lineButtons.add(clearLine);
        form.add(lineButtons); form.add(total);
        form.add(new JLabel("Advance payment is required. Fixed payment terms are included in the PDF."));
        JScrollPane scroll = new JScrollPane(form); scroll.getVerticalScrollBar().setUnitIncrement(18);
        page.add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(0, 3, 6, 6));
        for (JButton b : List.of(back, newQuote, save, delete, pdf)) buttons.add(b);
        if (canConvert) buttons.add(convert);
        page.add(buttons, BorderLayout.SOUTH);
        editors.addAll(List.of(quoteDate, notes, lineDate, type, description, amount, addLine, editLine, removeLine, clearLine));
        addLine.addActionListener(e -> updateLine());
        editLine.addActionListener(e -> editLine());
        removeLine.addActionListener(e -> {
            int index = table.getSelectedRow();
            if (index >= 0) { lines.remove(index); clearLine(); renderLines(); }
        });
        clearLine.addActionListener(e -> clearLine());
        history.addActionListener(e -> {
            if (rebuilding || busy) return;
            Choice choice = (Choice) history.getSelectedItem();
            if (choice != null && (selected == null || choice.summary == null || choice.summary.id() != selected.quoteId())) {
                if (discardChanges()) loadChoice(choice);
                else selectHistory(selected);
            }
        });
        newQuote.addActionListener(e -> { if (!busy && discardChanges()) { selectHistory(null); populate(null); } });
        back.addActionListener(e -> {
            if (!busy && discardChanges()) {
                customer = null; selected = null; cards.show(pages, "customers"); searchCustomers();
            }
        });
        save.addActionListener(e -> save(false));
        convert.addActionListener(e -> save(true));
        delete.addActionListener(e -> delete());
        pdf.addActionListener(e -> export());
        return page;
    }

    private static JPanel row(String label, JComponent component) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.add(new JLabel(label), BorderLayout.WEST); row.add(component, BorderLayout.CENTER); return row;
    }

    private void searchCustomers() {
        if (busy) return;
        String query = search.getText().trim(), selectedDay = (String) day.getSelectedItem();
        work("Searching customers…", () -> {
            List<Customer> result = query.isEmpty() ? database.getAllCustomers() : database.searchCustomers(query);
            return result.stream().filter(accessible)
                    .filter(c -> "All".equals(selectedDay) || c.getServiceDay().equalsIgnoreCase(selectedDay)).toList();
        }, result -> { customerModel.clear(); result.forEach(customerModel::addElement); });
    }

    private void searchAcrossDays() {
        if (busy) return;
        if (!"All".equals(day.getSelectedItem())) {
            day.setSelectedItem("All");
        } else {
            searchCustomers();
        }
    }

    private record Loaded(SalesQuoteStore.Page quotes, List<String> types, Map<String, String> settings) { }

    private void loadCustomer(Customer target) {
        historyOffset = 0;
        work("Loading quotations…", () -> new Loaded(store.page(target.getId(), 50, 0), database.getRecordTypes(),
                database.getAllPdfSettings()), loaded -> {
            customer = target; settings = loaded.settings;
            heading.setText(target.getFullName() + " — " + target.getId());
            type.removeAllItems(); loaded.types.forEach(type::addItem);
            if (type.getItemCount() == 0) { type.addItem("Bill"); type.addItem("Payment"); }
            rebuildHistory(loaded.quotes);
            populate(null);
            cards.show(pages, "quote");
        });
    }

    private record Choice(SalesQuoteStore.Summary summary) {
        @Override public String toString() {
            return summary == null ? "New quotation" : summary.number() + " — " + summary.date()
                    + (summary.converted() ? " — CONVERTED (read-only)" : "");
        }
    }

    private void rebuildHistory(SalesQuoteStore.Page page) {
        rebuilding = true;
        history.removeAllItems(); history.addItem(new Choice(null));
        page.rows().forEach(q -> history.addItem(new Choice(q)));
        historyTotal = page.total();
        historyPage.setText((historyTotal == 0 ? 0 : historyOffset + 1) + "–"
                + Math.min(historyOffset + 50, historyTotal) + " of " + historyTotal);
        rebuilding = false;
        updateEnabled();
    }

    private void changeHistoryPage(int delta) {
        if (busy || customer == null || !discardChanges()) return;
        int offset = Math.max(0, historyOffset + delta);
        String id = customer.getId();
        work("Loading quotation page…", () -> store.page(id, 50, offset), page -> {
            historyOffset = offset; rebuildHistory(page); populate(null);
        });
    }

    private void loadChoice(Choice choice) {
        if (choice.summary == null) { populate(null); return; }
        String id = customer.getId();
        work("Loading quotation details…", () -> store.get(id, choice.summary.id()), this::populate);
    }

    private void selectHistory(SalesQuote quote) {
        rebuilding = true;
        for (int i = 0; i < history.getItemCount(); i++)
            if ((quote == null && history.getItemAt(i).summary == null)
                    || (quote != null && history.getItemAt(i).summary != null
                    && history.getItemAt(i).summary.id() == quote.quoteId())) { history.setSelectedIndex(i); break; }
        rebuilding = false;
    }

    private void populate(SalesQuote quote) {
        selected = quote;
        number.setText(quote == null ? "Number assigned when saved" : quote.quoteNumber()
                + (quote.converted() ? " — Converted " + quote.convertedAt() + " (read-only)" : ""));
        quoteDate.setText(quote == null ? SalesQuote.today() : quote.quoteDate());
        notes.setText(quote == null ? "" : quote.notes());
        lines.clear(); if (quote != null) lines.addAll(quote.lines());
        clearLine(); renderLines(); updateEnabled();
    }

    private void renderLines() {
        tableModel.setRowCount(0);
        BigDecimal sum = BigDecimal.ZERO;
        for (SalesQuote.Line line : lines) {
            tableModel.addRow(new Object[]{line.date(), line.type(), line.description(),
                    (line.amount().signum() > 0 ? "CREDIT +" : "DEBIT ") + line.amount().setScale(2).toPlainString()});
            sum = sum.add(line.amount());
        }
        total.setText("Signed total: " + sum.setScale(2).toPlainString() + "   (" + lines.size() + "/200 lines)");
    }

    private void updateLine() {
        if (busy || (selected != null && selected.converted())) return;
        try {
            if (editingLine < 0 && lines.size() >= 200) throw new IllegalArgumentException("At most 200 lines are allowed.");
            BigDecimal value = new BigDecimal(amount.getText().trim());
            if (value.signum() <= 0) throw new IllegalArgumentException("Enter a positive amount; the type determines its sign.");
            String selectedType = (String) type.getSelectedItem();
            SalesQuote.Line line = new SalesQuote.Line(lineDate.getText().trim(), selectedType,
                    description.getText(), SalesQuote.signedAmount(selectedType, value, settings));
            if (editingLine >= 0) lines.set(editingLine, line); else lines.add(line);
            clearLine(); renderLines();
        } catch (RuntimeException e) { error(e); }
    }

    private void editLine() {
        int index = table.getSelectedRow();
        if (index < 0) return;
        SalesQuote.Line line = lines.get(index); editingLine = index;
        boolean exists = false;
        for (int i = 0; i < type.getItemCount(); i++) if (type.getItemAt(i).equals(line.type())) exists = true;
        if (!exists) type.addItem(line.type());
        lineDate.setText(line.date()); type.setSelectedItem(line.type());
        description.setText(line.description()); amount.setText(line.amount().abs().toPlainString());
    }

    private void clearLine() {
        editingLine = -1; lineDate.setText(SalesQuote.today()); description.setText(""); amount.setText("");
        table.clearSelection();
    }

    private SalesQuote capture() {
        if (!amount.getText().isBlank() || !description.getText().isBlank())
            throw new IllegalArgumentException("Add / Update the pending line, or clear the line form, before saving.");
        return new SalesQuote(selected == null ? 0 : selected.quoteId(), selected == null ? "" : selected.quoteNumber(),
                customer.getId(), quoteDate.getText().trim(), notes.getText(),
                selected == null ? SalesQuote.DEFAULT_TERMS : selected.terms(), lines,
                selected == null ? null : selected.convertedAt(), selected == null ? "" : selected.createdAt(),
                selected == null ? "" : selected.updatedAt());
    }

    private boolean hasChanges() {
        if (customer == null || (selected != null && selected.converted())) return false;
        if (!amount.getText().isBlank() || !description.getText().isBlank()) return true;
        if (selected == null) return !lines.isEmpty() || !notes.getText().isBlank()
                || !quoteDate.getText().equals(SalesQuote.today());
        return !selected.lines().equals(lines) || !selected.notes().equals(notes.getText())
                || !selected.quoteDate().equals(quoteDate.getText().trim());
    }

    private boolean discardChanges() {
        return !hasChanges() || JOptionPane.showConfirmDialog(this, "Discard unsaved quotation changes?",
                "Unsaved quotation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private record Saved(SalesQuote quote, SalesQuoteStore.Page page, int offset, String warning) { }

    private Saved withPage(SalesQuote quote, int offset) {
        try { return new Saved(quote, store.page(quote.customerId(), 50, offset), offset, null); }
        catch (RuntimeException e) { return new Saved(quote, null, offset, e.getMessage()); }
    }

    private void showSaved(Saved saved) {
        historyOffset = saved.offset();
        SalesQuoteStore.Page page = saved.page();
        if (page == null) {
            List<SalesQuoteStore.Summary> summaries = new ArrayList<>();
            summaries.add(new SalesQuoteStore.Summary(saved.quote().quoteId(), saved.quote().quoteNumber(),
                    saved.quote().quoteDate(), saved.quote().converted()));
            page = new SalesQuoteStore.Page(summaries, Math.max(1, historyTotal));
        }
        rebuildHistory(page);
        selectHistory(saved.quote()); populate(saved.quote());
        if (saved.warning() != null) status.setText("Quotation saved. Refresh history to reload the page: " + saved.warning());
    }

    private void save(boolean toStatement) {
        if (busy || (toStatement && !canConvert)) return;
        try {
            SalesQuote draft = capture();
            if (toStatement && JOptionPane.showConfirmDialog(this,
                    "Save this quotation and add all its signed lines to the statement?\n"
                            + "This can happen only once. The quotation will become permanently read-only.",
                    "Convert to Statement", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            int offset = draft.quoteId() == 0 ? 0 : historyOffset;
            work(toStatement ? "Saving before conversion…" : "Saving quotation…",
                    () -> withPage(store.save(draft), offset), saved -> {
                showSaved(saved);
                if (toStatement) convertSaved(saved.quote());
            });
        } catch (RuntimeException e) { error(e); }
    }

    private void convertSaved(SalesQuote quote) {
        int offset = historyOffset;
        work("Converting saved quotation…", () -> withPage(store.convert(quote.customerId(), quote.quoteId()), offset), this::showSaved);
    }

    private void delete() {
        if (busy || selected == null || selected.converted()) return;
        if (JOptionPane.showConfirmDialog(this, "Permanently delete " + selected.quoteNumber() + "?",
                "Delete quotation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        String id = customer.getId(); long quoteId = selected.quoteId();
        work("Deleting quotation…", () -> { store.delete(id, quoteId); return store.page(id, 50, 0); },
                page -> { historyOffset = 0; rebuildHistory(page); populate(null); });
    }

    private void export() {
        if (busy || selected == null) return;
        if (hasChanges()) { error(new IllegalStateException("Save changes before exporting the quotation.")); return; }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(SalesQuotePdf.filename(customer, selected)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (!target.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
            target = Path.of(target + ".pdf");
        if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(this, "Replace existing PDF?",
                "Export quotation", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        Path output = target; Customer owner = customer; SalesQuote quote = selected;
        work("Exporting PDF…", () -> {
            SalesQuotePdf.export(output, owner, quote, database.getAllPdfSettings()); return output;
        }, path -> status.setText("PDF saved: " + path));
    }

    private void updateEnabled() {
        boolean editable = !busy && (selected == null || !selected.converted());
        editors.forEach(c -> c.setEnabled(editable));
        save.setEnabled(editable); delete.setEnabled(editable && selected != null);
        convert.setEnabled(editable && canConvert); pdf.setEnabled(!busy && selected != null);
        history.setEnabled(!busy); back.setEnabled(!busy); newQuote.setEnabled(!busy);
        previousHistory.setEnabled(!busy && historyOffset > 0);
        nextHistory.setEnabled(!busy && historyOffset + 50 < historyTotal);
    }

    private <T> void work(String message, Callable<T> task, Consumer<T> completed) {
        if (busy) return;
        long taskGeneration = ++generation;
        busy = true; updateEnabled(); status.setText(message);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                if (taskGeneration != generation) return;
                busy = false;
                try { T result = get(); status.setText("Ready"); completed.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); error(e); }
                catch (ExecutionException e) { error(e.getCause()); }
                catch (RuntimeException e) { error(e); }
                finally { updateEnabled(); }
            }
        }.execute();
    }

    private void error(Throwable error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        status.setText(message);
        JOptionPane.showMessageDialog(this, message, "Sales quotation error", JOptionPane.ERROR_MESSAGE);
    }
}
