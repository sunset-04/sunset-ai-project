package com.sunset.ai.service;

import com.sunset.ai.service.impl.FileServiceImpl;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileServiceTest {
    @TempDir Path directory;
    private VectorStore vectors;
    private FileServiceImpl service;

    @BeforeEach void initialize() throws Exception {
        vectors = mock(VectorStore.class);
        service = createService();
    }

    private FileServiceImpl createService() throws Exception {
        FileServiceImpl files = new FileServiceImpl(vectors, directory.resolve("data").toString(),
                20L * 1024 * 1024, directory.resolve("legacy").toString());
        files.initialize();
        return files;
    }

    @Test void identicalRetryOnlyEmbedsOnceAndSurvivesRestart() throws Exception {
        var file = pdf("notes.pdf", "This is a document used to verify idempotent PDF ingestion.");
        var first = service.upload("pdf_one", file);
        var duplicate = service.upload("pdf_one", file);
        assertThat(first.reused()).isFalse();
        assertThat(duplicate.reused()).isTrue();
        assertThat(duplicate.documentId()).isEqualTo(first.documentId());
        assertThat(createService().upload("pdf_one", file).reused()).isTrue();
        verify(vectors, times(1)).add(anyList());
    }

    @Test void matchingFilenamesInDifferentSessionsKeepDistinctFilesAndSearchFilters() throws Exception {
        service.upload("first", pdf("same.pdf", "Alpha course description for the first uploaded document."));
        service.upload("second", pdf("same.pdf", "Beta course description for the second uploaded document."));
        assertThat(service.getFile("first").getFile()).isNotEqualTo(service.getFile("second").getFile());
        assertThat(service.getFilterExpression("first")).isNotEqualTo(service.getFilterExpression("second"));
        assertThat(service.getFile("first").getContentAsByteArray())
                .isNotEqualTo(service.getFile("second").getContentAsByteArray());
        ArgumentCaptor<List<Document>> captured = ArgumentCaptor.forClass(List.class);
        verify(vectors, times(2)).add(captured.capture());
        assertThat(captured.getAllValues().get(0)).allSatisfy(doc -> {
            assertThat(doc.getMetadata()).containsEntry("chat_id", "first");
            assertThat(doc.getMetadata()).containsKey("document_id");
        });
    }

    @Test void newDocumentMustUseNewSessionSoOldChatContextCannotLeakIntoIt() throws Exception {
        var first = service.upload("same", pdf("same.pdf", "Original document content for the existing conversation."));
        assertThatThrownBy(() -> service.upload("same", pdf("same.pdf", "A different document for this same conversation.")))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(service.getFilterExpression("same")).contains(first.documentId());
        verify(vectors, times(1)).add(anyList());
    }

    @Test void badPdfAndInvalidSessionNeverReachEmbeddingModel() {
        var fake = new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a PDF".getBytes());
        assertThatThrownBy(() -> service.upload("valid", fake)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload("../escape", fake)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vectors);
    }

    @Test void oversizedFileIsRejectedBeforeReadingOrEmbedding() {
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getSize()).thenReturn(21L * 1024 * 1024);
        assertThatThrownBy(() -> service.upload("valid", file)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(vectors);
    }

    @Test void failedEmbeddingLeavesNoPublishedDocumentOrLocalPdf() throws Exception {
        doThrow(new IllegalStateException("embedding unavailable")).when(vectors).add(anyList());
        assertThatThrownBy(() -> service.upload("failed", pdf("test.pdf", "The vector service will fail to index this document.")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.getFile("failed")).isInstanceOf(ResponseStatusException.class);
        try (var files = Files.list(directory.resolve("data/pdf"))) { assertThat(files).isEmpty(); }
        try (var files = Files.list(directory.resolve("data/documents"))) { assertThat(files).isEmpty(); }
        verify(vectors).delete(anyList());
    }

    @Test void suppliedPathIsNeverUsedAsStorageLocation() throws Exception {
        service.upload("safe", pdf("../../outside.pdf", "A valid PDF uploaded with an unsafe original filename."));
        assertThat(service.getFilename("safe")).isEqualTo("outside.pdf");
        assertThat(service.getFile("safe").getFile().toPath().getParent()).isEqualTo(directory.resolve("data/pdf"));
    }

    @Test void vectorSnapshotSurvivesRestartAndQueriesOnlyTheSelectedDocument() throws Exception {
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embedding.embed(any(Document.class))).thenReturn(new float[]{1, 0, 0});
        when(embedding.embed(anyString())).thenReturn(new float[]{1, 0, 0});
        when(embedding.dimensions()).thenReturn(3);
        SimpleVectorStore first = SimpleVectorStore.builder(embedding).build();
        service = new FileServiceImpl(first, directory.resolve("data").toString(), 20971520,
                directory.resolve("legacy").toString());
        service.initialize();
        service.upload("first", pdf("same.pdf", "Alpha course information only belongs to the first document."));
        service.upload("second", pdf("same.pdf", "Beta course information only belongs to the second document."));
        SimpleVectorStore restored = SimpleVectorStore.builder(embedding).build();
        FileServiceImpl restarted = new FileServiceImpl(restored, directory.resolve("data").toString(), 20971520,
                directory.resolve("legacy").toString());
        restarted.initialize();
        var matches = restored.similaritySearch(SearchRequest.builder().query("course information")
                .filterExpression(restarted.getFilterExpression("first")).topK(10).build());
        assertThat(matches).isNotEmpty().allSatisfy(doc -> {
            assertThat(doc.getMetadata()).containsEntry("chat_id", "first");
            assertThat(doc.getText()).contains("Alpha");
        });
        verify(embedding, times(2)).embed(any(Document.class));
    }

    private MockMultipartFile pdf(String filename, String text) throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            pdf.save(bytes);
            return new MockMultipartFile("file", filename, "application/pdf", bytes.toByteArray());
        }
    }
}
