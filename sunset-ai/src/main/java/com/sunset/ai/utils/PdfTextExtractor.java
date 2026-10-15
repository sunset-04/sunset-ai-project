package com.sunset.ai.utils;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.Resource;

import java.util.HashMap;
import java.util.List;

public final class PdfTextExtractor {
    private PdfTextExtractor() { }

    public static List<Document> read(Resource resource) {
        return new PagePdfDocumentReader(resource, PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1).build()).read().stream()
                .map(page -> new Document(page.getId(), normalize(page.getText()), new HashMap<>(page.getMetadata())))
                .toList();
    }

    /** PDF layout extraction often inserts hundreds of spaces between columns. */
    public static String normalize(String text) {
        if (text == null) return "";
        return text.replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
