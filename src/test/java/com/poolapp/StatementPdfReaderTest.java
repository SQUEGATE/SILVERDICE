package com.poolapp;

import com.poolapp.service.StatementPdfReader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.*;

public class StatementPdfReaderTest {
    private static final PDFont FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final StatementPdfReader reader = new StatementPdfReader();

    @Test public void previewOnlyKeepsZelleOrZelDepositsAndPreservesOriginalIdentities() {
        List<StatementPdfReader.SourceRow> rows = List.of(
                source("1:1", "Zelle From Demo", "deposit"),
                source("1:2", "zEL from Demo", "deposit"),
                source("1:3", "Cash deposit", "deposit"),
                source("1:4", "Zelle To Demo", "deposit"),
                source("1:5", "Zel To Demo", "withdrawal"),
                source("1:6", "Zelle From Demo", "unknown"),
                source("1:7", "Hazel deposit", "deposit"),
                source("1:8", "Zel deposit", "deposit"));
        List<StatementPdfReader.SourceRow> kept = StatementPdfReader.zelleDepositRows(rows);
        assertEquals(List.of("1:1", "1:2", "1:8"), kept.stream().map(StatementPdfReader.SourceRow::sourceId).toList());
        assertSame(rows.get(0), kept.get(0));
    }

    private static StatementPdfReader.SourceRow source(String id, String text, String direction) {
        return new StatementPdfReader.SourceRow(id, 1, Integer.parseInt(id.substring(2)),
                text, "9/1", "25.00", direction, "");
    }

    @Test public void readsDepositColumnOnlyFirstColumnDateWrapsAndRepeatedPageHeaders() throws Exception {
        byte[] pdf = statementPdf();
        StatementPdfReader.Extraction extraction = reader.extract(pdf);
        assertEquals(2, extraction.pageCount());
        assertEquals(extraction.rows().toString(), 3, extraction.rows().size());
        assertEquals(64, extraction.documentHash().length());

        StatementPdfReader.SourceRow first = extraction.rows().get(0);
        assertEquals("9/1", first.dateText());
        assertEquals("deposit", first.direction());
        assertTrue(first.depositText().contains("100.00"));
        assertTrue(first.text(), first.text().contains("memo continues here"));
        assertTrue(first.text().contains("on09/01"));
        assertEquals("TX1234", first.reference());
        assertTrue(first.sourceId().matches("1:\\d+"));

        assertEquals("withdrawal", extraction.rows().get(1).direction());
        assertEquals("", extraction.rows().get(1).depositText());
        assertEquals("9/4", extraction.rows().get(2).dateText());
        assertEquals("deposit", extraction.rows().get(2).direction());
        assertTrue(extraction.rows().get(2).sourceId().startsWith("2:"));
    }

    @Test public void fallsBackToSingleDollarAmountButNeverGuessesAmongMultipleAmounts() throws Exception {
        StatementPdfReader.Extraction single = reader.extract(unheadedPdf("$25.00"));
        assertEquals(1, single.rows().size());
        assertEquals("deposit", single.rows().get(0).direction());
        assertEquals(0, new BigDecimal("25.00").compareTo(
                com.poolapp.db.StatementReaderStore.parsePositiveAmount(single.rows().get(0).depositText())));

        StatementPdfReader.Extraction multiple = reader.extract(unheadedPdf("$25.00 $500.00"));
        assertEquals(1, multiple.rows().size());
        assertEquals("unknown", multiple.rows().get(0).direction());
        assertEquals("", multiple.rows().get(0).depositText());

        StatementPdfReader.Extraction withdrawal = reader.extract(unheadedPdf("ATM withdrawal $25.00"));
        assertEquals(1, withdrawal.rows().size());
        assertNotEquals("deposit", withdrawal.rows().get(0).direction());
        assertEquals("", withdrawal.rows().get(0).depositText());
    }

    @Test public void reportsScannedMalformedAndOversizedDocumentsExplicitly() throws Exception {
        byte[] blank;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            blank = out.toByteArray();
        }
        assertTrue(assertThrows(IOException.class, () -> reader.extract(blank)).getMessage().contains("Scanned/image"));
        assertThrows(IOException.class, () -> reader.extract(new byte[]{1, 2, 3, 4}));
        assertTrue(assertThrows(IOException.class,
                () -> reader.extract(new byte[(int) StatementPdfReader.MAX_PDF_BYTES + 1])).getMessage().contains("25 MB"));
    }

    @Test public void extractsExpenseLinesUsingTheSharedPositionGrouping() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                text(stream, 60, 700, "Coffee");
                text(stream, 120, 697.6f, "$12.34");
                text(stream, 60, 694.0f, "Next line");
            }
            document.save(out);
            pdf = out.toByteArray();
        }
        List<StatementPdfReader.TextRow> lines = reader.extractTextDocument(pdf).rows();
        assertEquals(2, lines.size());
        assertEquals("Coffee $12.34", lines.get(0).text());
        assertEquals("Next line", lines.get(1).text());
    }

    @Test public void rejectsPageAndTransactionRowCaps() throws Exception {
        byte[] manyPages;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int page = 0; page <= StatementPdfReader.MAX_PAGES; page++) document.addPage(new PDPage());
            document.save(out);
            manyPages = out.toByteArray();
        }
        assertTrue(assertThrows(IOException.class, () -> reader.extract(manyPages)).getMessage().contains("100-page"));

        byte[] manyRows;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                headers(stream, 740, 730);
                for (int i = 0; i <= StatementPdfReader.MAX_ROWS; i++) {
                    row(stream, 710 - i * 8f, "9/1", "Zelle payment", "1.00", "", "2.00");
                }
            }
            document.save(out);
            manyRows = out.toByteArray();
        }
        assertTrue(assertThrows(IOException.class, () -> reader.extract(manyRows)).getMessage().contains("1,000-row"));
    }

    private static byte[] statementPdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage first = new PDPage(PDRectangle.LETTER);
            document.addPage(first);
            try (PDPageContentStream stream = new PDPageContentStream(document, first)) {
                headers(stream, 740, 730);
                row(stream, 690, "9/1", "Zelle From ParetSwihl", "100.00", "", "500.00");
                text(stream, 190, 675, "Jane on09/01 Ref#TX1234");
                text(stream, 190, 660, "memo continues here");
                row(stream, 630, "9/2", "ATM withdrawal", "", "20.00", "480.00");
            }
            PDPage second = new PDPage(PDRectangle.LETTER);
            document.addPage(second);
            try (PDPageContentStream stream = new PDPageContentStream(document, second)) {
                headers(stream, 740, 730);
                row(stream, 690, "9/4", "Payment from Jane Paret-Swihl", "50.00", "", "530.00");
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] unheadedPdf(String amounts) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                text(stream, 60, 700, "9/5");
                text(stream, 160, 700, "Zelle from Jane Doe " + amounts);
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void headers(PDPageContentStream stream, float firstY, float secondY) throws IOException {
        text(stream, 395, firstY, "Check");
        text(stream, 405, firstY, "Deposits/");
        text(stream, 458, firstY, "Withdrawals/");
        text(stream, 525, firstY, "Ending daily");
        text(stream, 60, secondY, "Date");
        text(stream, 102, secondY, "Number");
        text(stream, 165, secondY, "Description");
        text(stream, 405, secondY, "Additions");
        text(stream, 458, secondY, "Subtractions");
        text(stream, 532, secondY, "balance");
    }

    private static void row(PDPageContentStream stream, float y, String date, String description,
                            String deposit, String withdrawal, String balance) throws IOException {
        text(stream, 60, y, date);
        text(stream, 165, y, description);
        if (!deposit.isEmpty()) rightText(stream, 450, y, deposit);
        if (!withdrawal.isEmpty()) rightText(stream, 514, y, withdrawal);
        if (!balance.isEmpty()) rightText(stream, 571, y, balance);
    }

    private static void rightText(PDPageContentStream stream, float right, float y, String value) throws IOException {
        text(stream, right - FONT.getStringWidth(value) / 1000f * 10f, y, value);
    }

    private static void text(PDPageContentStream stream, float x, float y, String value) throws IOException {
        stream.beginText();
        stream.setFont(FONT, 10);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }
}
