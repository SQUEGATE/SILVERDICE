package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.model.SalesQuote;
import com.poolapp.ui.SalesQuotesPanel;
import org.junit.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.*;

public class SalesQuotesPanelTest {
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static void populate(SalesQuotesPanel panel, SalesQuote quote) throws Exception {
        Field customer = SalesQuotesPanel.class.getDeclaredField("customer"); customer.setAccessible(true);
        customer.set(panel, new Customer("c1", "Synthetic", "Customer", "", "", "", "Monday", BigDecimal.ZERO, ""));
        Method populate = SalesQuotesPanel.class.getDeclaredMethod("populate", SalesQuote.class);
        populate.setAccessible(true); populate.invoke(panel, quote);
    }

    @Test public void constructorDoesNotPerformNetworkIoAndConvertedQuoteOnlyAllowsPdf() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                SalesQuotesPanel panel = new SalesQuotesPanel(new DatabaseManager("http://127.0.0.1:1", "synthetic", 1),
                        customer -> true, true);
                SalesQuote converted = new SalesQuote(7, "SQ-000007", "c1", "10/26/2026", "Saved", "Fixed terms",
                        List.of(SalesQuoteTest.line("Bill", "Description", "-1")), "2026-10-26T15:00:00Z", "", "");
                populate(panel, converted);
                for (String name : List.of("save", "delete", "convert", "quoteDate", "notes", "amount", "type"))
                    assertFalse(name, ((JComponent) field(panel, name)).isEnabled());
                assertTrue(((JButton) field(panel, "pdf")).isEnabled());
                assertTrue(((JLabel) field(panel, "number")).getText().contains("SQ-000007"));
                assertTrue(((JLabel) field(panel, "number")).getText().contains("read-only"));
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void unsavedEditsAndPendingLineAreDetectedBeforeSaveOrExport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                SalesQuotesPanel panel = new SalesQuotesPanel(new DatabaseManager("http://127.0.0.1:1", "synthetic", 1),
                        customer -> true, false);
                SalesQuote quote = new SalesQuote(7, "SQ-000007", "c1", "10/26/2026", "Saved", "Fixed terms",
                        List.of(SalesQuoteTest.line("Bill", "Description", "-1")), null, "", "");
                populate(panel, quote);
                Method changed = SalesQuotesPanel.class.getDeclaredMethod("hasChanges"); changed.setAccessible(true);
                assertEquals(false, changed.invoke(panel));
                ((JTextArea) field(panel, "notes")).setText("Unsaved");
                assertEquals(true, changed.invoke(panel));
                assertFalse(((JButton) field(panel, "convert")).isEnabled());
                ((JTextArea) field(panel, "notes")).setText("Saved");
                ((JTextField) field(panel, "amount")).setText("10");
                assertEquals(true, changed.invoke(panel));
                Method capture = SalesQuotesPanel.class.getDeclaredMethod("capture"); capture.setAccessible(true);
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> capture.invoke(panel));
                assertTrue(failure.getCause().getMessage().contains("pending line"));
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
}
