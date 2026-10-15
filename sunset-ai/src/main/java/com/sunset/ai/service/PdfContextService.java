package com.sunset.ai.service;

import com.sunset.ai.utils.PdfTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** Selected short PDFs are read in full; semantic filtering must not hide their existence. */
@Slf4j
@Service
public class PdfContextService {
    private final IFileService files;
    private final VectorStore vectors;
    private final int maxContextChars;
    private final double similarityThreshold;
    private final int topK;
    private final Map<String, List<Document>> pageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Document>> eldest) {
                    return size() > 16;
                }
            });

    public PdfContextService(IFileService files, VectorStore vectors,
                             @Value("${app.pdf.max-context-chars:16000}") int maxContextChars,
                             @Value("${app.pdf.similarity-threshold:0.3}") double similarityThreshold,
                             @Value("${app.pdf.top-k:6}") int topK) {
        if (maxContextChars < 200 || topK < 1 || similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("无效的 PDF 上下文配置");
        }
        this.files = files;
        this.vectors = vectors;
        this.maxContextChars = maxContextChars;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
    }

    public PdfContext resolve(String chatId, String question) {
        String filter = files.getFilterExpression(chatId);
        String filename = files.getFilename(chatId);
        List<Document> pages = pageCache.get(filter);
        if (pages == null) {
            pages = PdfTextExtractor.read(files.getFile(chatId)).stream()
                    .filter(page -> !page.getText().isBlank()).toList();
            pageCache.put(filter, pages);
        }
        if (pages.isEmpty()) throw new IllegalArgumentException("该 PDF 未提取到文字，请先对扫描件进行 OCR 识别");
        String completeText = format(pages);
        if (completeText.length() <= maxContextChars) {
            log.info("PDF context mode=full_document, pages={}, chars={}", pages.size(), completeText.length());
            return new PdfContext(filename, completeText, "完整文档");
        }
        List<Document> matches = vectors.similaritySearch(SearchRequest.builder()
                .query(question).filterExpression(filter).topK(topK)
                .similarityThreshold(similarityThreshold).build());
        if (matches != null && !matches.isEmpty()) {
            String content = bounded(format(matches));
            log.info("PDF context mode=semantic, chunks={}, chars={}", matches.size(), content.length());
            return new PdfContext(filename, content, "与问题相关的文档节选，不代表全文");
        }
        // Overview questions such as "what is this?" can have weak semantic similarity.
        // Keep the fallback inside the selected PDF and disclose its limited coverage.
        List<Document> overview = new ArrayList<>();
        int count = Math.min(pages.size(), topK);
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : i * (pages.size() - 1) / (count - 1);
            String pageText = PdfTextExtractor.normalize(pages.get(index).getText());
            int budget = Math.max(1, maxContextChars / count - 50);
            if (pageText.length() > budget) pageText = pageText.substring(0, budget);
            overview.add(new Document(pageText, pages.get(index).getMetadata()));
        }
        String content = bounded(format(overview));
        log.info("PDF context mode=overview_fallback, pages={}, chars={}", overview.size(), content.length());
        return new PdfContext(filename, content, "未检索到高相关片段，以下为文档概览节选，不代表全文");
    }

    private String format(List<Document> documents) {
        StringBuilder text = new StringBuilder();
        for (Document document : documents) {
            text.append("[第 ").append(document.getMetadata().getOrDefault("page_number", "未知"))
                    .append(" 页]\n").append(PdfTextExtractor.normalize(document.getText())).append("\n\n");
        }
        return text.toString().strip();
    }

    private String bounded(String text) {
        if (text.length() <= maxContextChars) return text;
        String notice = "\n[内容因长度限制截断]";
        return text.substring(0, maxContextChars - notice.length()) + notice;
    }

    public record PdfContext(String filename, String content, String scope) { }
}
