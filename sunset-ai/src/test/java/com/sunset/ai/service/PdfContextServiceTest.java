package com.sunset.ai.service;

import com.sunset.ai.support.PdfTestDocuments;
import com.sunset.ai.utils.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PdfContextServiceTest {
    private final IFileService files = mock(IFileService.class);
    private final VectorStore vectors = mock(VectorStore.class);

    private void selectedPdf(String... pages) throws Exception {
        when(files.getFile("selected")).thenReturn(new ByteArrayResource(PdfTestDocuments.pdf(pages)));
        when(files.getFilename("selected")).thenReturn("resume.pdf");
        when(files.getFilterExpression("selected")).thenReturn("document_id == 'selected-document'");
    }

    @Test void genericQuestionAboutAShortPdfAlwaysReceivesItsFullText() throws Exception {
        selectedPdf("Personal resume. Seeking a Java backend developer internship.", "Education and project experience.");
        var service = new PdfContextService(files, vectors, 16000, 0.3, 6);
        var context = service.resolve("selected", "看看这个是做什么的");
        assertThat(context.scope()).isEqualTo("完整文档");
        assertThat(context.content()).contains("Personal resume", "Education and project experience", "第 1 页", "第 2 页");
        verifyNoInteractions(vectors);
        service.resolve("selected", "求职意向是什么");
        verify(files, times(1)).getFile("selected");
    }

    @Test void longDocumentRetrievalIsRestrictedToTheSelectedPdf() throws Exception {
        selectedPdf("Java course description and enrollment information. ".repeat(20));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(new Document("The Java course takes six months.", Map.of("page_number", 1))));
        var service = new PdfContextService(files, vectors, 200, 0.3, 6);
        var context = service.resolve("selected", "课程多久");
        assertThat(context.content()).contains("six months");
        assertThat(context.scope()).contains("节选");
        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors).similaritySearch(search.capture());
        assertThat(search.getValue().getSimilarityThreshold()).isEqualTo(0.3);
        assertThat(search.getValue().getTopK()).isEqualTo(6);
        assertThat(search.getValue().getFilterExpression().toString()).contains("selected-document");
    }

    @Test void emptySemanticResultsStillProvideABoundedOverviewOfTheSameFile() throws Exception {
        selectedPdf("FIRST_PAGE personal resume overview. ".repeat(20), "LAST_PAGE education and awards. ".repeat(20));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var context = new PdfContextService(files, vectors, 400, 0.3, 6).resolve("selected", "看看这个是做什么的");
        assertThat(context.content()).contains("FIRST_PAGE", "LAST_PAGE");
        assertThat(context.content().length()).isLessThanOrEqualTo(400);
        assertThat(context.scope()).contains("概览节选", "不代表全文");
    }

    @Test void layoutPaddingIsRemovedWithoutJoiningSeparateLines() {
        assertThat(PdfTextExtractor.normalize("Title            \n  Java    backend\n\n\nDetails"))
                .isEqualTo("Title\nJava backend\n\nDetails");
    }
}
