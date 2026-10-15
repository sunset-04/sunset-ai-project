package com.sunset.ai.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;

public final class PdfTestDocuments {
    private PdfTestDocuments() { }

    public static byte[] pdf(String... pageTexts) throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 720);
                    for (int offset = 0; offset < text.length(); offset += 70) {
                        content.showText(text.substring(offset, Math.min(offset + 70, text.length())));
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            pdf.save(output);
            return output.toByteArray();
        }
    }
}
