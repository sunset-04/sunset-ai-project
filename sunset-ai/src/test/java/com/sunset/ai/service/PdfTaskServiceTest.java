package com.sunset.ai.service;

import com.sunset.ai.entity.vo.PdfUploadResult;
import com.sunset.ai.repository.ChatHistoryRepository;
import com.sunset.ai.repository.JdbcChatStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PdfTaskServiceTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    IFileService files;
    ChatHistoryRepository history;
    PdfTaskService service;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        new JdbcChatStore(jdbc, new DataSourceTransactionManager(source)).initialize();
        files = mock(IFileService.class); history = mock(ChatHistoryRepository.class);
        service = create();
        when(files.upload(anyString(), any())).thenAnswer(call -> success(call.getArgument(0)));
    }
    PdfTaskService create() throws Exception {
        var result = new PdfTaskService(jdbc, files, history, directory.toString(), 1024 * 1024, 1, 1, 2);
        result.initialize(); return result;
    }
    @AfterEach void close() { service.close(); }
    MockMultipartFile input(String value) { return new MockMultipartFile("file", "notes.pdf", "application/pdf", ("%PDF-" + value).getBytes()); }
    void owner(String id) { jdbc.update("INSERT INTO ai_conversation_owner (business_type, chat_id, user_id) VALUES ('pdf', ?, 'alice')", id); }
    PdfUploadResult success(String id) { return new PdfUploadResult(1, "done", id, "document", "notes.pdf", 2, false); }
    PdfTaskService.TaskView await(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var task = service.get(id, "alice");
            if (task.status().equals("SUCCEEDED") || task.status().equals("FAILED")) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("job did not finish");
    }

    @Test void acceptedJobDoesNotWaitForEmbeddingAndIsDeduplicated() throws Exception {
        owner("one");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(files.upload(eq("one"), any())).thenAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return success("one"); });
        var task = service.submit("alice", "one", input("one"));
        assertThat(task.status()).isEqualTo("QUEUED"); verifyNoInteractions(files);
        assertThat(service.submit("alice", "one", input("one")).id()).isEqualTo(task.id());
        service.dispatch();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        try { assertThat(service.get(task.id(), "alice").status()).isEqualTo("RUNNING"); }
        finally { release.countDown(); }
        assertThat(await(task.id()).result().chunkCount()).isEqualTo(2);
        assertThat(Files.exists(directory.resolve("tasks/" + task.id() + ".pdf"))).isFalse();
        verify(files, times(1)).upload(eq("one"), any());
    }

    @Test void failureCanBeRetriedFromDurableInputAndOtherUsersCannotInspectIt() throws Exception {
        owner("retry");
        when(files.upload(eq("retry"), any())).thenThrow(new IllegalStateException("provider secret detail"))
                .thenReturn(success("retry"));
        var task = service.submit("alice", "retry", input("retry")); service.dispatch();
        var failed = await(task.id());
        assertThat(failed.error()).doesNotContain("secret");
        assertThat(Files.exists(directory.resolve("tasks/" + task.id() + ".pdf"))).isTrue();
        assertThatThrownBy(() -> service.get(task.id(), "bob")).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.retry(task.id(), "bob")).isInstanceOf(ResponseStatusException.class);
        assertThat(service.retry(task.id(), "alice").status()).isEqualTo("QUEUED");
        service.dispatch(); assertThat(await(task.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(service.get(task.id(), "alice").attempts()).isEqualTo(2);
    }

    @Test void queuedAndInterruptedJobsCanBeRecoveredAfterRestart() throws Exception {
        owner("restart"); var task = service.submit("alice", "restart", input("restart"));
        jdbc.update("UPDATE ai_pdf_task SET status = 'RUNNING' WHERE id = ?", task.id());
        service.close(); service = create();
        assertThat(service.get(task.id(), "alice").status()).isEqualTo("QUEUED");
        service.dispatch(); assertThat(await(task.id()).status()).isEqualTo("SUCCEEDED");
    }

    @Test void queueSaturationAndDifferentContentAreRejectedWithoutOverwritingInput() {
        owner("one"); owner("two"); owner("three");
        var first = service.submit("alice", "one", input("original"));
        assertThatThrownBy(() -> service.submit("alice", "one", input("different"))).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        service.submit("alice", "two", input("two"));
        assertThatThrownBy(() -> service.submit("alice", "three", input("three"))).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(429));
        assertThat(service.get(first.id(), "alice").status()).isEqualTo("QUEUED");
        verifyNoInteractions(files);
    }
}
