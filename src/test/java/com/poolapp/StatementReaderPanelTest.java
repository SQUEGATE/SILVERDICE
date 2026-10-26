package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.ui.StatementReaderPanel;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StatementReaderPanelTest {
    @Test
    public void sharedCustomerPickerStartsCompleteAndEnterFiltersAllDaysByName() throws Exception {
        Path directory = Files.createDirectories(Path.of("statement-reader-picker-test-" + UUID.randomUUID()));
        try {
            DatabaseManager database = new DatabaseManager(directory.resolve("reader.db"));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    StatementReaderPanel panel = new StatementReaderPanel(database, customer -> true, false);
                    List<Customer> loaded = List.of(
                            new Customer("c1", "Carla", "Lopez", "", "", "", "Monday", BigDecimal.ZERO, ""),
                            new Customer("c2", "Carlos", "Rivera", "", "", "", "Tuesday", BigDecimal.ZERO, ""),
                            new Customer("c3", "Jane", "Doe", "", "", "", "Wednesday", BigDecimal.ZERO, ""));
                    Field customers = StatementReaderPanel.class.getDeclaredField("customers");
                    customers.setAccessible(true);
                    customers.set(panel, loaded);
                    Method build = StatementReaderPanel.class.getDeclaredMethod("buildCustomerChoices", List.class);
                    build.setAccessible(true);
                    build.invoke(panel, loaded);

                    JComboBox<?> choices = (JComboBox<?>) field(panel, "customerChoices");
                    assertEquals(4, choices.getItemCount());
                    JTextField search = (JTextField) field(panel, "customerSearch");
                    search.setText("cAr");
                    search.postActionEvent();
                    assertEquals(3, choices.getItemCount());
                    assertTrue(((Map<?, ?>) field(panel, "customerIdsByDisplay")).containsKey("Jane Doe (c3)"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
