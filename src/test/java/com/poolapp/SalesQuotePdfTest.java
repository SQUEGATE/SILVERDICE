package com.poolapp;

import com.poolapp.model.Customer;
import com.poolapp.model.SalesQuote;
import com.poolapp.service.SalesQuotePdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;

public class SalesQuotePdfTest {
    private static Customer customer() {
        return new Customer("c1", "AVERY", "EXAMPLE", "123 SYNTHETIC STREET", "MIAMI", "FL", "33165",
                "", "", "Monday", BigDecimal.ZERO, "");
    }
    private static Map<String, String> settings() {
        return Map.of("logoPath", Path.of("src", "main", "resources", "logo.jpg").toString(),
                "companyName", "SILVER DICE LLC", "companyAddress", "10740 NW 83RD ST\nDORAL, FL 33178",
                "companyPhone", "786-838-7247", "paymentInfo",
                "ZELLE: silverdices@hotmail.com / Cel: 786-838-7247|Pay Check: Silver Dice LLC");
    }

    @Test public void preservesLongNotesTermsEveryLineAndRepeatedHeadersAcrossPages() throws Exception {
        Path directory = Files.createDirectories(Path.of("quote-pdf-test-" + UUID.randomUUID()));
        Path file = directory.resolve("long.pdf");
        try {
            List<SalesQuote.Line> lines = new ArrayList<>();
            for (int i = 0; i < 200; i++) lines.add(SalesQuoteTest.line("Bill", "ITEM" + i + " "
                    + "Long description with unicode José and equipment details. ".repeat(5) + "ENDITEM" + i, "-1.25"));
            SalesQuote quote = new SalesQuote(1, "SQ-000001", "c1", "10/26/2026",
                    "NOTE-BEGIN " + "Note paragraph. ".repeat(700) + " NOTE-END",
                    "TERM-BEGIN " + "Advance payment terms. ".repeat(650) + " TERM-END", lines, null, "", "");
            SalesQuotePdf.export(file, customer(), quote, settings());
            try (var pdf = Loader.loadPDF(file.toFile())) {
                assertTrue(pdf.getNumberOfPages() > 10);
                String text = new PDFTextStripper().getText(pdf);
                for (int i = 0; i < 200; i++) assertTrue("Missing line " + i, text.contains("ENDITEM" + i));
                for (String expected : List.of("NOTE-BEGIN", "NOTE-END", "TERM-BEGIN", "TERM-END",
                        "José", "Forms of Payment", "Total:", "-$250.00", "Page 1 of", "SQ-000001"))
                    assertTrue("Missing " + expected, text.contains(expected));
                assertTrue(text.split("Description", -1).length > 5);
                assertTrue(text.contains("Page " + pdf.getNumberOfPages() + " of " + pdf.getNumberOfPages()));
            }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }

    @Test public void exportsVisualSampleWithSafeFilename() throws Exception {
        Path directory = Files.createDirectories(Path.of("gradle-build-quotes-desktop", "quote-preview"));
        SalesQuote quote = new SalesQuote(1, "SQ-000001", "c1", "05/26/2026", "", SalesQuote.DEFAULT_TERMS,
                List.of(SalesQuoteTest.line("Bill", "PUMP MOTOR INSTALLATION", "-120.00"),
                        SalesQuoteTest.line("Bill", "SEAL O-RING REPAIR KIT", "-43.17"),
                        SalesQuoteTest.line("Bill", "ASQ165 1.65 THP US MOTOR 115V/230V", "-363.22")), null, "", "");
        assertEquals("EXAMPLE_AVERY_SALES_QUOTATION_SQ-000001.pdf", SalesQuotePdf.filename(customer(), quote));
        Path file = directory.resolve(SalesQuotePdf.filename(customer(), quote));
        SalesQuotePdf.export(file, customer(), quote, settings());
        try (var pdf = Loader.loadPDF(file.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            for (String expected : List.of("-$526.39", "SQ-000001", "05/26/2026", "10/26/2026",
                    "PUMP MOTOR INSTALLATION", "SEAL O-RING REPAIR KIT", "ASQ165 1.65 THP US MOTOR 115V/230V"))
                assertTrue("Missing " + expected, text.contains(expected));
            ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(0, 100), "png", directory.resolve("quote-preview.png").toFile());
        }
    }
}
