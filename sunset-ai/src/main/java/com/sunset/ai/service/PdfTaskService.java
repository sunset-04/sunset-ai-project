package com.sunset.ai.service;

import com.sunset.ai.entity.vo.PdfUploadResult;
import com.sunset.ai.repository.ChatHistoryRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Durable single-instance job queue. Workers never retain a request's MultipartFile or security context. */
@Slf4j
@Service
@DependsOn("jdbcChatStore")
public class PdfTaskService {
    private final JdbcTemplate jdbc;
    private final IFileService files;
    private final ChatHistoryRepository history;
    private final Path staging;
    private final long maxBytes;
    private final int maxPending;
    private final ThreadPoolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public PdfTaskService(JdbcTemplate jdbc, IFileService files, ChatHistoryRepository history,
            @Value("${app.storage.directory:./data}") String directory,
            @Value("${app.storage.max-pdf-bytes:20971520}") long maxBytes,
            @Value("${app.pdf.tasks.workers:2}") int workers,
            @Value("${app.pdf.tasks.queue-capacity:8}") int capacity,
            @Value("${app.pdf.tasks.max-pending:20}") int maxPending) {
        if (workers < 1 || workers > 16 || capacity < 1 || maxPending < 1) throw new IllegalArgumentException("无效的 PDF 任务配置");
        this.jdbc = jdbc; this.files = files; this.history = history;
        this.staging = Path.of(directory).toAbsolutePath().normalize().resolve("tasks");
        this.maxBytes = maxBytes; this.maxPending = maxPending;
        this.executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, "pdf-index-worker"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct public void initialize() throws IOException {
        Files.createDirectories(staging);
        // A process crash must not leave jobs permanently RUNNING. Re-indexing uses the file service's deduplication.
        jdbc.update("UPDATE ai_pdf_task SET status = 'QUEUED', updated_at = CURRENT_TIMESTAMP(6) WHERE status = 'RUNNING'");
        // Recover the small crash window between publishing a result and deleting its staged copy.
        for (String id : jdbc.queryForList("SELECT id FROM ai_pdf_task WHERE status = 'SUCCEEDED'", String.class)) deleteStaged(id);
    }

    public synchronized TaskView submit(String userId, String chatId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空的 PDF 文件");
        if (file.getSize() > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF 文件不能超过 20 MB");
        try {
            byte[] bytes;
            try (var input = file.getInputStream()) { bytes = input.readNBytes(Math.toIntExact(maxBytes + 1)); }
            if (bytes.length > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF 文件不能超过 20 MB");
            if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw new IllegalArgumentException("文件内容不是有效的 PDF");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var existing = jdbc.queryForList("SELECT id, content_hash FROM ai_pdf_task WHERE user_id = ? AND chat_id = ?", userId, chatId);
            if (!existing.isEmpty()) {
                if (!hash.equals(existing.get(0).get("content_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话已关联其他 PDF，请新建会话后上传");
                history.save("pdf", chatId);
                return get(existing.get(0).get("id").toString(), userId);
            }
            ensureCapacity();
            String id = UUID.randomUUID().toString();
            String filename = safeName(file.getOriginalFilename());
            Path partial = staging.resolve(id + ".part");
            try {
                Files.write(partial, bytes, StandardOpenOption.CREATE_NEW);
                Files.move(partial, inputPath(id), StandardCopyOption.ATOMIC_MOVE);
                jdbc.update("INSERT INTO ai_pdf_task (id, user_id, chat_id, filename, content_hash, status) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                        id, userId, chatId, filename, hash);
            } catch (Exception error) { Files.deleteIfExists(partial); Files.deleteIfExists(inputPath(id)); throw error; }
            history.save("pdf", chatId);
            return get(id, userId);
        } catch (ResponseStatusException | IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("保存 PDF 任务失败，请稍后重试", error); }
    }

    public TaskView get(String id, String userId) {
        var rows = jdbc.query("SELECT * FROM ai_pdf_task WHERE id = ? AND user_id = ?", (rs, row) -> {
            String json = rs.getString("result_json");
            PdfUploadResult result = json == null ? null : mapper.readValue(json, PdfUploadResult.class);
            return new TaskView(rs.getString("id"), rs.getString("chat_id"), rs.getString("filename"),
                    rs.getString("status"), rs.getString("error_message"), rs.getInt("attempts"), result,
                    rs.getTimestamp("updated_at").toInstant().toString());
        }, id, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或无权访问");
        return rows.get(0);
    }

    public TaskView forChat(String chatId, String userId) {
        var ids = jdbc.queryForList("SELECT id FROM ai_pdf_task WHERE chat_id = ? AND user_id = ?", String.class, chatId, userId);
        return ids.isEmpty() ? null : get(ids.get(0), userId);
    }

    public synchronized TaskView retry(String id, String userId) {
        TaskView task = get(id, userId);
        if (!"FAILED".equals(task.status())) return task;
        if (!Files.isRegularFile(inputPath(id))) throw new ResponseStatusException(HttpStatus.CONFLICT, "原始文件已不可用，请新建会话重新上传");
        ensureCapacity();
        jdbc.update("UPDATE ai_pdf_task SET status = 'QUEUED', error_message = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND user_id = ? AND status = 'FAILED'", id, userId);
        return get(id, userId);
    }

    @Scheduled(fixedDelayString = "${app.pdf.tasks.dispatch-delay-ms:700}")
    public void dispatch() {
        if (executor.isShutdown()) return;
        var ids = jdbc.queryForList("SELECT id FROM ai_pdf_task WHERE status = 'QUEUED' ORDER BY created_at LIMIT 10", String.class);
        for (String id : ids) {
            if (jdbc.update("UPDATE ai_pdf_task SET status = 'RUNNING', attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND status = 'QUEUED'", id) == 0) continue;
            try { executor.execute(() -> run(id)); }
            catch (RejectedExecutionException rejected) {
                jdbc.update("UPDATE ai_pdf_task SET status = 'QUEUED', attempts = attempts - 1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND status = 'RUNNING'", id);
                break;
            }
        }
    }

    private void run(String id) {
        try {
            var row = jdbc.queryForMap("SELECT user_id, chat_id, filename FROM ai_pdf_task WHERE id = ?", id);
            String userId = row.get("user_id").toString(), chatId = row.get("chat_id").toString();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation_owner WHERE business_type = 'pdf' AND chat_id = ? AND user_id = ?", Integer.class, chatId, userId) != 1) {
                throw new IllegalArgumentException("文档权限已失效，请重新上传");
            }
            PdfUploadResult result = files.upload(chatId, new StagedPdf(inputPath(id), row.get("filename").toString()));
            jdbc.update("UPDATE ai_pdf_task SET status = 'SUCCEEDED', result_json = ?, error_message = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                    mapper.writeValueAsString(result), id);
            deleteStaged(id);
        } catch (Exception error) {
            log.error("PDF indexing job failed, taskId={}", id, error);
            String message = error instanceof IllegalArgumentException ? error.getMessage() : "文档处理失败，请检查模型与嵌入服务后重试";
            if (message == null) message = "文档处理失败，请重试";
            jdbc.update("UPDATE ai_pdf_task SET status = 'FAILED', error_message = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                    message.substring(0, Math.min(500, message.length())), id);
        }
    }

    private void ensureCapacity() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_pdf_task WHERE status IN ('QUEUED', 'RUNNING')", Integer.class) >= maxPending) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "当前等待处理的文档较多，请稍后再上传");
        }
    }
    private Path inputPath(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("任务 ID 格式不正确");
        return staging.resolve(id + ".pdf");
    }
    private void deleteStaged(String id) {
        try { Files.deleteIfExists(inputPath(id)); }
        catch (IOException error) { log.warn("Unable to clean staged PDF for task {}", id); }
    }
    private String safeName(String name) {
        String value = name == null ? "document.pdf" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return value.isBlank() ? "document.pdf" : value.substring(0, Math.min(255, value.length()));
    }
    @PreDestroy public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(20, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException error) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    public record TaskView(String id, String chatId, String filename, String status, String error, int attempts,
                           PdfUploadResult result, String updatedAt) { }

    private record StagedPdf(Path path, String filename) implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return "application/pdf"; }
        public boolean isEmpty() { return getSize() == 0; }
        public long getSize() { try { return Files.size(path); } catch (IOException error) { throw new UncheckedIOException(error); } }
        public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        public void transferTo(File destination) throws IOException { Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
}
