package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.ui.VisitsPanel;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public class VisitsPanelTest {
    @Test
    public void customerListIsUnpagedAndEnterSearchesAcrossAllDays() throws Exception {
        Path directory = Files.createDirectories(Path.of("visits-search-test-" + java.util.UUID.randomUUID()));
        Path path = directory.resolve("visits.db");
        try {
            DatabaseManager database = new DatabaseManager(path);
            for (int i = 0; i < 56; i++) {
                database.addCustomer(new Customer("c" + i, "First", String.format("%03d", i), "", "", "",
                        i % 2 == 0 ? "Monday" : "Tuesday", java.math.BigDecimal.ZERO, ""));
            }
            AtomicReference<VisitsPanel> holder = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                VisitsPanel panel = new VisitsPanel(database, c -> true);
                holder.set(panel);
                panel.refresh();
            });
            VisitsPanel panel = holder.get();
            awaitIdle(panel);
            DefaultListModel<?> customers = field(panel, "customers", DefaultListModel.class);
            VisitsTest.check(customers.size() == 56, "Default customer list is not paged");
            SwingUtilities.invokeAndWait(() -> field(panel, "day", JComboBox.class).setSelectedItem("Monday"));
            awaitIdle(panel);
            VisitsTest.check(customers.size() == 28, "Optional day filter remains available");
            SwingUtilities.invokeAndWait(() -> {
                field(panel, "search", JTextField.class).setText("First");
                field(panel, "search", JTextField.class).postActionEvent();
            });
            awaitIdle(panel);
            VisitsTest.check("All".equals(field(panel, "day", JComboBox.class).getSelectedItem()),
                    "Enter resets the day filter");
            VisitsTest.check(customers.size() == 56, "Enter searches all days without pagination");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test
    public void filtersCustomersAndSavesAndEditsVisit() throws Exception {
        Path directory = Files.createDirectories(Path.of("visits-ui-test-" + java.util.UUID.randomUUID()));
        Path path = directory.resolve("visits.db");
        try {
            DatabaseManager database = new DatabaseManager(path);
            database.addCustomer(VisitsDatabaseTest.customer("c1", "Monday"));
            database.addCustomer(VisitsDatabaseTest.customer("c2", "Tuesday"));
            database.addCustomer(VisitsDatabaseTest.customer("hidden", "Monday"));
            AtomicReference<VisitsPanel> holder = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                VisitsPanel panel = new VisitsPanel(database, c -> !c.getId().equals("hidden"));
                holder.set(panel);
                panel.refresh();
            });
            VisitsPanel panel = holder.get();
            awaitIdle(panel);
            DefaultListModel<?> customers = field(panel, "customers", DefaultListModel.class);
            VisitsTest.check(customers.size() == 2, "Only accessible customers listed");
            SwingUtilities.invokeAndWait(() -> field(panel, "day", JComboBox.class).setSelectedItem("Monday"));
            awaitIdle(panel);
            VisitsTest.check(customers.size() == 1, "Day filter");
            SwingUtilities.invokeAndWait(() -> {
                field(panel, "customerList", JList.class).setSelectedIndex(0);
                button(panel, "Open Customer Visits").doClick();
            });
            awaitIdle(panel);
            SwingUtilities.invokeAndWait(() -> {
                VisitsTest.check(field(panel, "ph", JSlider.class).getValue() == 2, "PH default matches web");
                VisitsTest.check(!button(panel, "Delete Visit").isEnabled(), "Cannot delete unsaved visit");
                field(panel, "notes", JTextArea.class).setText("UI test visit");
                button(panel, "Save").doClick();
            });
            awaitIdle(panel);
            VisitsTest.check(database.getVisits("c1").size() == 1, "UI saves new visit");
            VisitsTest.check(button(panel, "Delete Visit").isEnabled(), "Saved visit can be deleted");
            VisitsTest.check(database.getVisits("c1").get(0).alk() == null
                    && database.getVisits("c1").get(0).cal() == null, "UI allows blank ALK/CAL");
            var original = database.getVisits("c1").get(0).visitedAt();
            SwingUtilities.invokeAndWait(() -> {
                field(panel, "alk", JTextField.class).setText("70");
                field(panel, "cal", JTextField.class).setText("250");
                setField(panel, "photoData", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jG9sAAAAASUVORK5CYII=");
                field(panel, "notes", JTextArea.class).setText("Edited UI visit");
                button(panel, "Save").doClick();
            });
            awaitIdle(panel);
            VisitsTest.check(database.getVisits("c1").size() == 1, "UI edit doesn't duplicate");
            VisitsTest.check(database.getVisits("c1").get(0).notes().equals("Edited UI visit"), "UI edit persisted");
            VisitsTest.check(!database.getVisits("c1").get(0).photoData().isEmpty(), "UI photo saved");
            VisitsTest.check(database.getVisits("c1").get(0).visitedAt().equals(original), "UI edit preserves time");
            SwingUtilities.invokeAndWait(() -> button(panel, "New Visit").doClick());
            VisitsTest.check(field(panel, "notes", JTextArea.class).getText().isEmpty(), "New visit clears notes");
            VisitsTest.check(field(panel, "photoData", String.class).isEmpty(), "New visit clears photo");
            VisitsTest.check(!button(panel, "Delete Visit").isEnabled(), "New Visit disables delete");
            SwingUtilities.invokeAndWait(() -> field(panel, "history", JComboBox.class).setSelectedIndex(1));
            awaitIdle(panel);
            VisitsTest.check(field(panel, "notes", JTextArea.class).getText().equals("Edited UI visit"), "Past visit reloads fields");
            VisitsTest.check(!field(panel, "photoData", String.class).isEmpty(), "Past visit reloads photo");
            SwingUtilities.invokeAndWait(() -> {
                button(panel, "Remove Photo").doClick();
                field(panel, "alk", JTextField.class).setText("");
                field(panel, "cal", JTextField.class).setText("");
                button(panel, "Save").doClick();
            });
            awaitIdle(panel);
            VisitsTest.check(database.getVisits("c1").get(0).photoData().isEmpty(), "Photo removal saved");
            VisitsTest.check(database.getVisits("c1").get(0).alk() == null, "Existing reading can be cleared");
        } finally {
            Files.deleteIfExists(path);
            Files.delete(directory);
        }
    }

    private static void awaitIdle(VisitsPanel panel) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> busy = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> busy.set(field(panel, "busy", Boolean.class)));
            if (!busy.get()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Visit operation timed out");
    }

    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container container) {
                JButton found = button(container, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
