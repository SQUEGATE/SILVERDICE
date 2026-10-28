package com.poolapp.ui;

import com.poolapp.db.DatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.model.VisitRecord;
import com.poolapp.service.VisitPhoto;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class VisitsPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")
            .withZone(ZoneId.systemDefault());
    private final DatabaseManager database;
    private final Predicate<Customer> accessible;
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);
    private final JTextField search = new JTextField(22);
    private final JComboBox<String> day = new JComboBox<>(new String[]{
            "All", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"});
    private final DefaultListModel<Customer> customers = new DefaultListModel<>();
    private final JList<Customer> customerList = new JList<>(customers);
    private final JLabel customerName = new JLabel();
    private final JLabel currentTime = new JLabel();
    private final JLabel savedTime = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JComboBox<VisitChoice> history = new JComboBox<>();
    private final JButton deleteVisitButton = new JButton("Delete Visit");
    private final JSlider ph = slider(VisitRecord.PH_VALUES);
    private final JSlider chlo = slider(VisitRecord.CHLO_VALUES);
    private final JSlider stb = slider(VisitRecord.STB_VALUES);
    private final JSlider salt = saltSlider();
    private final JTextField alk = wholeNumberField();
    private final JTextField cal = wholeNumberField();
    private final JTextArea notes = new JTextArea(5, 30);
    private final JLabel photoPreview = new JLabel("No photo attached.");
    private String photoData = "";
    private final List<VisitRecord> visits = new ArrayList<>();
    private final Timer clock;
    private Customer activeCustomer;
    private boolean rebuildingHistory;
    private boolean busy;
    private long generation;
    private int visitOffset;
    private int visitTotal;
    private final JButton previousHistory = new JButton("Previous 50");
    private final JButton nextHistory = new JButton("Next 50");
    private final JLabel historyPage = new JLabel(" ");
    private VisitRecord loadedVisit;

    public VisitsPanel(DatabaseManager database, Predicate<Customer> accessible) {
        super(new BorderLayout(8, 8));
        this.database = database;
        this.accessible = accessible;
        setBorder(BorderFactory.createTitledBorder("Visits"));
        content.add(customerSearchPage(), "customers");
        content.add(visitPage(), "visit");
        add(content, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        clock = new Timer(1000, e -> updateTime());
        addHierarchyListener(e -> {
            if (isShowing()) {
                updateTime();
                clock.start();
            } else {
                clock.stop();
            }
        });
        updateTime();
    }

    public void refresh() {
        if (busy) return;
        if (activeCustomer == null) searchCustomers();
        else loadVisits(activeCustomer);
    }

    private JPanel customerSearchPage() {
        JPanel page = new JPanel(new BorderLayout(8, 8));
        JPanel filters = new JPanel(new GridBagLayout());
        JButton find = new JButton("Search");
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0;
        gc.gridy = 0;
        filters.add(new JLabel("Search customers:"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        filters.add(search, gc);
        gc.gridx = 2;
        gc.weightx = 0;
        filters.add(find, gc);
        gc.gridx = 0;
        gc.gridy = 1;
        filters.add(new JLabel("Day:"), gc);
        gc.gridx = 1;
        filters.add(day, gc);
        page.add(filters, BorderLayout.NORTH);
        customerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        customerList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof Customer customer) {
                    label.setText(customer.getFullName() + " (" + customer.getServiceDay() + ")");
                }
                return label;
            }
        });
        page.add(new JScrollPane(customerList), BorderLayout.CENTER);
        JButton open = new JButton("Open Customer Visits");
        page.add(open, BorderLayout.SOUTH);
        find.addActionListener(e -> searchCustomers());
        search.addActionListener(e -> searchAcrossDays());
        day.addActionListener(e -> searchCustomers());
        open.addActionListener(e -> openSelectedCustomer());
        customerList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && customerList.getSelectedValue() != null && !busy) openSelectedCustomer();
        });
        return page;
    }

    private JPanel visitPage() {
        JPanel page = new JPanel(new BorderLayout(8, 8));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        customerName.setFont(customerName.getFont().deriveFont(Font.BOLD, 18f));
        form.add(customerName);
        form.add(currentTime);
        form.add(savedTime);
        JPanel past = new JPanel(new BorderLayout(8, 0));
        past.add(new JLabel("Past visits:"), BorderLayout.WEST);
        past.add(history, BorderLayout.CENTER);
        form.add(past);
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.LEFT));
        paging.add(previousHistory); paging.add(historyPage); paging.add(nextHistory);
        form.add(paging);
        previousHistory.addActionListener(e -> loadVisitPage(activeCustomer, Math.max(0, visitOffset - 50)));
        nextHistory.addActionListener(e -> loadVisitPage(activeCustomer, visitOffset + 50));
        history.addActionListener(e -> {
            if (!rebuildingHistory && !busy) loadSelectedVisit();
        });
        addSlider(form, "PH", ph, VisitRecord.PH_VALUES, null);
        addSlider(form, "CHLO", chlo, VisitRecord.CHLO_VALUES, null);
        addSlider(form, "STB", stb, VisitRecord.STB_VALUES, "50-100");
        addSlider(form, "SALT", salt, SALT_CHOICES, null);
        form.add(numberRow("ALK", alk, "70-120"));
        form.add(numberRow("CAL", cal, "250-500"));
        form.add(new JLabel("Notes:"));
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        form.add(new JScrollPane(notes));
        JPanel photo = new JPanel(new BorderLayout(6, 6));
        photo.setBorder(BorderFactory.createTitledBorder("Visit photo (optional)"));
        JPanel photoButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton upload = new JButton("Upload Photo");
        JButton remove = new JButton("Remove Photo");
        photoButtons.add(upload);
        photoButtons.add(remove);
        photo.add(photoButtons, BorderLayout.NORTH);
        photo.add(photoPreview, BorderLayout.CENTER);
        form.add(photo);
        upload.addActionListener(e -> uploadPhoto());
        remove.addActionListener(e -> {
            photoData = "";
            updatePhotoPreview();
        });
        JScrollPane scroll = new JScrollPane(form);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        page.add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton back = new JButton("Back to Customers");
        JButton newVisit = new JButton("New Visit");
        JButton save = new JButton("Save");
        buttons.add(back);
        buttons.add(newVisit);
        buttons.add(save);
        buttons.add(deleteVisitButton);
        page.add(buttons, BorderLayout.SOUTH);
        back.addActionListener(e -> {
            activeCustomer = null;
            pages.show(content, "customers");
            searchCustomers();
        });
        newVisit.addActionListener(e -> history.setSelectedIndex(0));
        save.addActionListener(e -> saveVisit());
        deleteVisitButton.setEnabled(false);
        deleteVisitButton.addActionListener(e -> {
            if (busy || activeCustomer == null) return;
            VisitChoice choice = (VisitChoice) history.getSelectedItem();
            if (choice == null || choice.visit() == null) return;
            if (JOptionPane.showConfirmDialog(this,
                    "Permanently delete this visit and its photo? This cannot be undone.",
                    "Delete Visit", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            VisitRecord visit = choice.visit();
            background("Deleting visit...", () -> {
                database.deleteVisit(visit.customerId(), visit.id());
                return visit.id();
            }, deletedId -> {
                visits.removeIf(v -> v.id() == deletedId);
                visitTotal = Math.max(0, visitTotal - 1);
                rebuildHistory(0);
                status.setText("Visit deleted.");
            });
        });
        return page;
    }

    private void searchCustomers() {
        if (busy) return;
        String query = search.getText().trim();
        String selectedDay = (String) day.getSelectedItem();
        background("Loading customers...", () -> {
            List<Customer> found = query.isEmpty() ? database.getAllCustomers() : database.searchCustomers(query);
            return found.stream().filter(accessible)
                    .filter(c -> "All".equals(selectedDay) || selectedDay.equals(c.getServiceDay())).toList();
        }, found -> {
            customers.clear();
            found.forEach(customers::addElement);
            status.setText(found.size() + " customer(s). Select a customer to open Visits.");
        });
    }

    private void searchAcrossDays() {
        if (busy) return;
        if (!"All".equals(day.getSelectedItem())) {
            day.setSelectedItem("All");
        } else {
            searchCustomers();
        }
    }

    private void openSelectedCustomer() {
        if (busy) return;
        Customer customer = customerList.getSelectedValue();
        if (customer == null) {
            JOptionPane.showMessageDialog(this, "Select a customer first.", "Visits", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        loadVisits(customer);
    }

    private void loadVisits(Customer customer) {
        loadVisitPage(customer, 0);
    }

    private void loadVisitPage(Customer customer, int offset) {
        if (busy || customer == null) return;
        background("Loading visits...", () -> database.getVisitPage(customer.getId(), 50, offset), page -> {
            activeCustomer = customer;
            visitOffset = offset;
            visitTotal = page.getInt("total");
            customerName.setText(customer.getFullName());
            visits.clear();
            org.json.JSONArray rows = page.getJSONArray("rows");
            for (int i = 0; i < rows.length(); i++) visits.add(VisitRecord.fromJson(rows.getJSONObject(i)));
            rebuildHistory(0);
            pages.show(content, "visit");
            status.setText("Ready.");
        });
    }

    private void loadSelectedVisit() {
        VisitChoice choice = (VisitChoice) history.getSelectedItem();
        if (choice == null || choice.visit() == null) { loadedVisit = null; populateVisit(); return; }
        VisitRecord summary = choice.visit();
        loadedVisit = null;
        background("Loading visit details...", () -> database.getVisit(summary.customerId(), summary.id()), detail -> {
            loadedVisit = detail;
            populateVisit();
        });
    }

    private void rebuildHistory(long selectedId) {
        visits.sort(Comparator.comparing(VisitRecord::visitedAt).thenComparingLong(VisitRecord::id).reversed());
        if (visits.size() > 50) visits.subList(50, visits.size()).clear();
        rebuildingHistory = true;
        history.removeAllItems();
        history.addItem(new VisitChoice(null));
        for (VisitRecord visit : visits) {
            VisitChoice choice = new VisitChoice(visit);
            history.addItem(choice);
            if (visit.id() == selectedId) history.setSelectedItem(choice);
        }
        rebuildingHistory = false;
        loadedVisit = null;
        historyPage.setText((visitTotal == 0 ? 0 : visitOffset + 1) + "–"
                + Math.min(visitOffset + 50, visitTotal) + " of " + visitTotal);
        previousHistory.setEnabled(!busy && visitOffset > 0);
        nextHistory.setEnabled(!busy && visitOffset + 50 < visitTotal);
        if (selectedId != 0) {
            loadedVisit = visits.stream().filter(v -> v.id() == selectedId).findFirst().orElse(null);
        }
        populateVisit();
    }

    private void populateVisit() {
        VisitChoice choice = (VisitChoice) history.getSelectedItem();
        VisitRecord visit = loadedVisit;
        deleteVisitButton.setEnabled(!busy && visit != null);
        savedTime.setText(visit == null ? "New visit - date and time recorded when saved."
                : "Visit date and time: " + TIME.format(visit.visitedAt()));
        ph.setValue(VisitRecord.PH_VALUES.indexOf(visit == null ? "7.4" : visit.ph()));
        chlo.setValue(VisitRecord.CHLO_VALUES.indexOf(visit == null ? "3-6" : visit.chlo()));
        stb.setValue(VisitRecord.STB_VALUES.indexOf(visit == null ? "50" : visit.stb()));
        salt.setValue(visit == null || visit.salt() == null ? 0 : VisitRecord.SALT_VALUES.indexOf(visit.salt()) + 1);
        alk.setText(visit == null || visit.alk() == null ? "" : String.valueOf(visit.alk()));
        cal.setText(visit == null || visit.cal() == null ? "" : String.valueOf(visit.cal()));
        notes.setText(visit == null ? "" : visit.notes());
        notes.setCaretPosition(0);
        photoData = visit == null ? "" : visit.photoData();
        updatePhotoPreview();
    }

    private void uploadPhoto() {
        if (busy) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Attach a visit photo");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Photos (PNG, JPEG)", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var file = chooser.getSelectedFile().toPath();
        background("Preparing photo...", () -> {
            try {
                return VisitPhoto.fromFile(file);
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }, data -> {
            photoData = data;
            updatePhotoPreview();
            status.setText("Photo attached. Press Save to save it with this visit.");
        });
    }

    private void updatePhotoPreview() {
        photoPreview.setIcon(null);
        photoPreview.setText(photoData.isEmpty() ? "No photo attached." : "");
        if (photoData.isEmpty()) return;
        try {
            var image = VisitPhoto.preview(photoData);
            double scale = Math.min(1.0, Math.min(360.0 / image.getWidth(), 220.0 / image.getHeight()));
            photoPreview.setIcon(new ImageIcon(image.getScaledInstance(Math.max(1, (int) (image.getWidth() * scale)),
                    Math.max(1, (int) (image.getHeight() * scale)), Image.SCALE_SMOOTH)));
        } catch (IOException e) {
            photoPreview.setText("Unable to preview photo. You can replace or remove it.");
            showError(e.getMessage());
        }
    }

    private void saveVisit() {
        if (busy || activeCustomer == null) return;
        VisitChoice current = (VisitChoice) history.getSelectedItem();
        if (current != null && current.visit() != null && loadedVisit == null) {
            showError("Select the visit again to load its details before saving.");
            return;
        }
        try {
            VisitChoice choice = (VisitChoice) history.getSelectedItem();
            VisitRecord existing = loadedVisit;
            VisitRecord draft = new VisitRecord(existing == null ? 0 : existing.id(), activeCustomer.getId(),
                    existing == null ? Instant.now() : existing.visitedAt(),
                    VisitRecord.PH_VALUES.get(ph.getValue()), VisitRecord.CHLO_VALUES.get(chlo.getValue()),
                    VisitRecord.STB_VALUES.get(stb.getValue()),
                    VisitRecord.optionalWholeNumber(alk.getText(), "ALK"),
                    VisitRecord.optionalWholeNumber(cal.getText(), "CAL"), notes.getText(), photoData,
                    salt.getValue() == 0 ? null : VisitRecord.SALT_VALUES.get(salt.getValue() - 1));
            int offset = existing == null ? 0 : visitOffset;
            background("Saving visit...", () -> {
                VisitRecord saved = database.saveVisit(draft);
                try { return new SavedVisit(saved, database.getVisitPage(saved.customerId(), 50, offset), null); }
                catch (RuntimeException e) { return new SavedVisit(saved, null, e.getMessage()); }
            }, result -> {
                VisitRecord saved = result.visit();
                visitOffset = offset;
                if (result.page() != null) {
                    visits.clear();
                    org.json.JSONArray rows = result.page().getJSONArray("rows");
                    for (int i = 0; i < rows.length(); i++) visits.add(VisitRecord.fromJson(rows.getJSONObject(i)));
                    visitTotal = result.page().getInt("total");
                } else if (existing == null) visitTotal++;
                visits.removeIf(v -> v.id() == saved.id());
                visits.add(saved);
                rebuildHistory(saved.id());
                status.setText(result.warning() == null ? "Visit saved."
                        : "Visit saved. Refresh history to reload the page: " + result.warning());
            });
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid Visit", JOptionPane.WARNING_MESSAGE);
        }
    }

    private record SavedVisit(VisitRecord visit, org.json.JSONObject page, String warning) { }

    private <T> void background(String message, Supplier<T> task, Consumer<T> success) {
        if (busy) return;
        long taskGeneration = ++generation;
        busy = true;
        setControlsEnabled(content, false);
        status.setText(message);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() {
                return task.get();
            }

            @Override
            protected void done() {
                if (taskGeneration != generation) return;
                busy = false;
                setControlsEnabled(content, true);
                previousHistory.setEnabled(visitOffset > 0);
                nextHistory.setEnabled(visitOffset + 50 < visitTotal);
                VisitChoice choice = (VisitChoice) history.getSelectedItem();
                deleteVisitButton.setEnabled(choice != null && choice.visit() != null);
                try {
                    success.accept(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("Visit operation interrupted.");
                } catch (ExecutionException e) {
                    showError(e.getCause().getMessage());
                }
            }
        }.execute();
    }

    private void showError(String message) {
        status.setText("Unable to complete visit operation.");
        JOptionPane.showMessageDialog(this, message, "Visits Error", JOptionPane.ERROR_MESSAGE);
    }

    private static void setControlsEnabled(Container container, boolean enabled) {
        for (Component component : container.getComponents()) {
            component.setEnabled(enabled);
            if (component instanceof Container child) setControlsEnabled(child, enabled);
        }
    }

    private void updateTime() {
        currentTime.setText("Current date and time: " + TIME.format(Instant.now()));
    }

    static final List<String> SALT_CHOICES = saltChoices();
    private static final String[] SALT_PALETTE = {"#e0f2fe", "#7dd3fc", "#38bdf8", "#0284c7", "#1e3a8a"};

    private static List<String> saltChoices() {
        List<String> choices = new ArrayList<>();
        choices.add("Not recorded");
        for (Integer value : VisitRecord.SALT_VALUES) choices.add(String.valueOf(value));
        return List.copyOf(choices);
    }

    // SALT has 25 stops, so it shows only the selected value in its heading instead of per-stop labels.
    private static JSlider saltSlider() {
        JSlider slider = new JSlider(0, SALT_CHOICES.size() - 1, 0);
        slider.setMajorTickSpacing(1);
        slider.setSnapToTicks(true);
        slider.setPaintTicks(true);
        slider.setPaintLabels(false);
        slider.setUI(new javax.swing.plaf.basic.BasicSliderUI(slider) {
            @Override public void paintTrack(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    int start = xPositionForValue(slider.getMinimum());
                    int end = xPositionForValue(slider.getMaximum());
                    float[] stops = new float[SALT_PALETTE.length];
                    Color[] colors = new Color[SALT_PALETTE.length];
                    for (int i = 0; i < SALT_PALETTE.length; i++) {
                        stops[i] = (float) i / (SALT_PALETTE.length - 1);
                        colors[i] = Color.decode(SALT_PALETTE[i]);
                    }
                    if (end > start) {
                        int y = trackRect.y + (trackRect.height - 14) / 2;
                        g.setPaint(new LinearGradientPaint(start, y, end, y, stops, colors));
                        g.fillRoundRect(start, y, end - start, 14, 10, 10);
                        g.setColor(new Color(0x475569));
                        g.drawRoundRect(start, y, end - start, 14, 10, 10);
                    }
                } finally {
                    g.dispose();
                }
            }
        });
        slider.setPreferredSize(new Dimension(480, 40));
        return slider;
    }

    private static JSlider slider(List<String> values) {
        JSlider slider = new JSlider(0, values.size() - 1, 0);
        slider.setMajorTickSpacing(1);
        slider.setSnapToTicks(true);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        String[] palette = values.equals(VisitRecord.PH_VALUES)
                ? new String[]{"#e6a079", "#ef866e", "#df645e", "#d9576b", "#cf4778", "#c83f80"}
                : values.equals(VisitRecord.CHLO_VALUES)
                ? new String[]{"#d9a09b", "#e88898", "#df536e", "#ee4275", "#e82d73", "#ec1b70"} : null;
        for (int i = 0; i < values.size(); i++) {
            JLabel label = new JLabel(values.get(i));
            if (palette != null) {
                label.setOpaque(true);
                label.setBackground(Color.decode(palette[i]));
                label.setForeground(new Color(0x111827));
                label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x475569)),
                        BorderFactory.createEmptyBorder(3, 5, 3, 5)));
            }
            labels.put(i, label);
        }
        if (palette != null) {
            slider.setUI(new javax.swing.plaf.basic.BasicSliderUI(slider) {
                @Override public void paintTrack(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        int start = xPositionForValue(slider.getMinimum());
                        int end = xPositionForValue(slider.getMaximum());
                        float[] stops = new float[palette.length];
                        Color[] colors = new Color[palette.length];
                        for (int i = 0; i < palette.length; i++) {
                            stops[i] = (float) i / (palette.length - 1);
                            colors[i] = Color.decode(palette[i]);
                        }
                        if (end > start) {
                            int y = trackRect.y + (trackRect.height - 14) / 2;
                            g.setPaint(new LinearGradientPaint(start, y, end, y, stops, colors));
                            g.fillRoundRect(start, y, end - start, 14, 10, 10);
                            g.setColor(new Color(0x475569));
                            g.drawRoundRect(start, y, end - start, 14, 10, 10);
                        }
                    } finally {
                        g.dispose();
                    }
                }
            });
        }
        slider.setLabelTable(labels);
        slider.setPreferredSize(new Dimension(480, palette == null ? 55 : 70));
        return slider;
    }

    private static void addSlider(JPanel form, String name, JSlider slider, List<String> values, String recommended) {
        JPanel group = new JPanel(new BorderLayout());
        JLabel label = new JLabel();
        Runnable update = () -> label.setText(name + ": " + values.get(slider.getValue())
                + (recommended == null ? "" : "   Recommended: " + recommended));
        slider.addChangeListener(e -> update.run());
        slider.getAccessibleContext().setAccessibleName(name);
        update.run();
        group.add(label, BorderLayout.NORTH);
        group.add(slider, BorderLayout.CENTER);
        form.add(group);
    }

    private static JPanel numberRow(String name, JTextField input, String recommended) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel label = new JLabel(name + " (optional):");
        label.setLabelFor(input);
        row.add(label);
        row.add(input);
        row.add(new JLabel("Recommended: " + recommended));
        return row;
    }

    private static JTextField wholeNumberField() {
        JTextField input = new JTextField(10);
        ((AbstractDocument) input.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException {
                replace(fb, offset, 0, text, attrs);
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (text == null || text.matches("[0-9]*")) super.replace(fb, offset, length, text, attrs);
                else Toolkit.getDefaultToolkit().beep();
            }
        });
        return input;
    }

    private record VisitChoice(VisitRecord visit) {
        @Override
        public String toString() {
            return visit == null ? "New Visit" : TIME.format(visit.visitedAt()) + " (#" + visit.id() + ")";
        }
    }
}
