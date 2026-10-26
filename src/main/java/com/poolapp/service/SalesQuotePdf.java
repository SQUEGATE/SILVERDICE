package com.poolapp.service;

import com.poolapp.model.Customer;
import com.poolapp.model.SalesQuote;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.List;

/** Streaming, line-wrapped PDF layout: even a single very long row can span pages. */
public final class SalesQuotePdf {
    private SalesQuotePdf() { }

    public static String filename(Customer customer, SalesQuote quote) {
        return safe(customer.getLastName()) + "_" + safe(customer.getFirstName())
                + "_SALES_QUOTATION_" + safe(quote.quoteNumber()) + ".pdf";
    }

    private static String safe(String text) { return text.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_"); }

    public static void export(Path target, Customer customer, SalesQuote quote, Map<String, String> settings)
            throws IOException {
        try (PDDocument document = new PDDocument()) {
            Layout layout = new Layout(document, settings, quote);
            try {
                layout.startPage();
                layout.boxText(customer.getFullName() + "\n" + customer.getAddress() + "\n"
                        + customer.getCity() + ", " + customer.getState() + " " + customer.getZip(), true);
                layout.paragraph("Notes: " + quote.notes(), false);
                layout.tableHeader();
                int row = 0;
                for (SalesQuote.Line line : quote.lines()) layout.row(line, row++ % 2 != 0);
                layout.total(quote);
                layout.boxText("Hi " + customer.getFullName() + ",\n\n" + quote.terms(), false);
                layout.paragraph("Forms of Payment:\n" + settings.getOrDefault("paymentInfo", "").replace("|", "\n"), false);
                layout.close();
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(i),
                            PDPageContentStream.AppendMode.APPEND, true, true)) {
                        layout.text(cs, layout.regular, 9, Color.GRAY,
                                "Page " + (i + 1) + " of " + document.getNumberOfPages(), 485, 24);
                    }
                }
                document.save(target.toFile());
            } finally { layout.close(); }
        }
    }

    private static String money(java.math.BigDecimal amount) {
        return (amount.signum() < 0 ? "-$" : "$") + amount.abs().setScale(2).toPlainString();
    }

    private static final class Layout {
        private final PDDocument document;
        private final Map<String, String> settings;
        private final SalesQuote quote;
        private final PDFont regular;
        private final PDFont bold;
        private PDPageContentStream cs;
        private float y;
        private boolean inTable;
        private static final Color GREY = new Color(100, 100, 100);
        private static final Color BLUE = new Color(35, 94, 153);

        Layout(PDDocument document, Map<String, String> settings, SalesQuote quote) throws IOException {
            this.document = document; this.settings = settings; this.quote = quote;
            Path font = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts", "arial.ttf");
            Path boldFont = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts", "arialbd.ttf");
            regular = Files.isRegularFile(font) ? PDType0Font.load(document, font.toFile())
                    : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            bold = Files.isRegularFile(boldFont) ? PDType0Font.load(document, boldFont.toFile())
                    : new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        }

        void startPage() throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            cs = new PDPageContentStream(document, page);
            y = 754;
            List<String> heading = wrap(bold, 14, settings.getOrDefault("companyName", "Pool Service Company"), 405);
            for (String line : heading) { text(cs, bold, 14, GREY, line, 28, y); y -= 18; }
            for (String value : List.of(settings.getOrDefault("companyAddress", ""),
                    settings.getOrDefault("companyPhone", ""))) {
                for (String line : wrap(bold, 10, value, 405)) {
                    text(cs, bold, 10, GREY, line, 28, y); y -= 14;
                }
            }
            drawLogo();
            y = Math.min(y - 20, 674);
            String title = "SALES QUOTATION";
            text(cs, bold, 18, BLUE, title, (612 - width(bold, 18, title)) / 2, y);
            y -= 21;
            text(cs, regular, 10, GREY, quote.quoteNumber() + "    Date: " + quote.quoteDate(), 28, y);
            y -= 23;
            if (inTable) tableHeader();
        }

        void drawLogo() throws IOException {
            PDImageXObject image = null;
            try {
                String data = settings.getOrDefault("logoData", "");
                if (!data.isBlank()) {
                    int comma = data.indexOf(',');
                    image = PDImageXObject.createFromByteArray(document,
                            Base64.getDecoder().decode(comma >= 0 ? data.substring(comma + 1) : data), "logo");
                } else {
                    String path = settings.getOrDefault("logoPath", "");
                    if (!path.isBlank() && Files.isRegularFile(Path.of(path)))
                        image = PDImageXObject.createFromFile(path, document);
                }
            } catch (RuntimeException | IOException ignored) {
                // An unavailable optional logo must not prevent quotation export.
            }
            if (image != null) {
                float scale = Math.min(90f / image.getWidth(), 70f / image.getHeight());
                cs.drawImage(image, 490, 690, image.getWidth() * scale, image.getHeight() * scale);
            }
        }

        void ensure(float height) throws IOException {
            if (y - height < 48) startPage();
        }

        void tableHeader() throws IOException {
            if (y - 26 < 48) {
                inTable = false;
                startPage();
            }
            inTable = true;
            box(y - 23, 23, false);
            text(cs, bold, 10, GREY, "Date", 34, y - 15);
            text(cs, bold, 10, GREY, "Description", 108, y - 15);
            text(cs, bold, 10, GREY, "Price", 535, y - 15);
            y -= 27;
        }

        void row(SalesQuote.Line line, boolean grey) throws IOException {
            List<String> description = wrap(regular, 9,
                    line.description().isBlank() ? line.type() : line.description(), 350);
            String price = money(line.amount());
            for (int i = 0; i < description.size(); i++) {
                ensure(20);
                box(y - 19, 19, grey);
                if (i == 0) {
                    text(cs, regular, 9, GREY, line.date(), 34, y - 13);
                    text(cs, regular, 9, GREY, price, 578 - width(regular, 9, price), y - 13);
                }
                text(cs, regular, 9, GREY, description.get(i), 108, y - 13);
                y -= 19;
            }
            y -= 4;
        }

        void paragraph(String value, boolean strong) throws IOException {
            inTable = false;
            y -= 7;
            for (String line : wrap(strong ? bold : regular, 10, value, 540)) {
                ensure(16);
                text(cs, strong ? bold : regular, 10, GREY, line, 34, y - 12);
                y -= 16;
            }

            y -= 8;
        }

        void total(SalesQuote quote) throws IOException {
            inTable = false;
            ensure(29);
            String label = "Total: " + money(quote.total());
            text(cs, bold, 12, GREY, label, 578 - width(bold, 12, label), y - 17);
            y -= 29;
        }

        void boxText(String value, boolean strong) throws IOException {
            inTable = false;
            y -= 6;
            List<String> wrapped = wrap(strong ? bold : regular, 10, value, 536);
            int index = 0;
            while (index < wrapped.size()) {
                ensure(19);
                int count = Math.min(wrapped.size() - index, (int) ((y - 48) / 19));
                box(y - count * 19, count * 19, false);
                for (int i = 0; i < count; i++) {
                    text(cs, strong ? bold : regular, 10, GREY, wrapped.get(index++), 36, y - 13);
                    y -= 19;
                }
            }
            y -= 7;
        }

        void box(float bottom, float height, boolean grey) throws IOException {
            cs.setNonStrokingColor(grey ? new Color(242, 242, 242) : Color.WHITE);
            cs.setStrokingColor(new Color(229, 229, 229));
            cs.setLineWidth(0.4f);
            cs.addRect(28, bottom, 556, height); cs.fillAndStroke();
        }

        List<String> wrap(PDFont font, float size, String value, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : clean(font, value).replace("\r", "").split("\n", -1)) {
                StringBuilder current = new StringBuilder();
                for (int offset = 0; offset < paragraph.length();) {
                    int cp = paragraph.codePointAt(offset);
                    String next = new String(Character.toChars(cp));
                    offset += Character.charCount(cp);
                    if (current.length() > 0 && width(font, size, current + next) > maxWidth) {
                        int space = current.lastIndexOf(" ");
                        if (space > 0) {
                            lines.add(current.substring(0, space));
                            current.delete(0, space + 1);
                        } else { lines.add(current.toString()); current.setLength(0); }
                    }
                    current.append(next);
                }
                lines.add(current.toString());
            }
            return lines;
        }

        String clean(PDFont font, String value) {
            StringBuilder text = new StringBuilder();
            Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC).codePoints().forEach(cp -> {
                if (cp == '\n' || cp == '\r') { text.append((char) cp); return; }
                if (cp == '\t') { text.append("    "); return; }
                String ch = new String(Character.toChars(cp));
                try { font.encode(ch); text.append(ch); }
                catch (IOException | IllegalArgumentException e) { text.append('?'); }
            });
            return text.toString();
        }

        float width(PDFont font, float size, String value) throws IOException {
            return font.getStringWidth(value) * size / 1000;
        }

        void text(PDPageContentStream stream, PDFont font, float size, Color color, String value, float x, float y)
                throws IOException {
            stream.beginText(); stream.setFont(font, size); stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, y); stream.showText(clean(font, value)); stream.endText();
        }

        void close() throws IOException { if (cs != null) { cs.close(); cs = null; } }
    }
}
