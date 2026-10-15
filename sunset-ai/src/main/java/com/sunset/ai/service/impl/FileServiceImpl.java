package com.sunset.ai.service.impl;

import com.sunset.ai.entity.vo.PdfUploadResult;
import com.sunset.ai.service.IFileService;
import com.sunset.ai.utils.ConversationIds;
import com.sunset.ai.utils.PdfTextExtractor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class FileServiceImpl implements IFileService {
    private final VectorStore vectorStore;
    private final Path root;
    private final Path legacyRoot;
    private final long maxPdfBytes;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StoredPdf> documents = new ConcurrentHashMap<>();
    private final Object[] uploadLocks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public FileServiceImpl(VectorStore vectorStore,
                           @Value("${app.storage.directory:./data}") String directory,
                           @Value("${app.storage.max-pdf-bytes:20971520}") long maxPdfBytes,
                           @Value("${app.storage.legacy-directory:.}") String legacyDirectory) {
        this.vectorStore = vectorStore;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.maxPdfBytes = maxPdfBytes;
        this.legacyRoot = Path.of(legacyDirectory).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(root.resolve("pdf"));
        Files.createDirectories(root.resolve("documents"));
        Path snapshot = root.resolve("vectors.json");
        if (vectorStore instanceof SimpleVectorStore simple) {
            Path source = Files.exists(snapshot) ? snapshot : legacyRoot.resolve("chat-pdf.json");
            if (Files.exists(source)) simple.load(new FileSystemResource(source));
        }
        try (DirectoryStream<Path> manifests = Files.newDirectoryStream(root.resolve("documents"), "*.json")) {
            for (Path manifest : manifests) {
                StoredPdf document = mapper.readValue(Files.readString(manifest), StoredPdf.class);
                ConversationIds.validate(document.chatId());
                storagePath(document.storageName());
                documents.put(document.chatId(), document);
            }
        }
        migrateLegacyFiles();
    }

    /** Serialize retries of the same conversation without blocking unrelated embedding jobs. */
    @Override
    public PdfUploadResult upload(String chatId, MultipartFile file) {
        ConversationIds.validate(chatId);
        synchronized (uploadLocks[Math.floorMod(chatId.hashCode(), uploadLocks.length)]) {
            return uploadLocked(chatId, file);
        }
    }

    private PdfUploadResult uploadLocked(String chatId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空的 PDF 文件");
        if (file.getSize() > maxPdfBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF 文件不能超过 20 MB");
        }
        String filename = safeFilename(file.getOriginalFilename());
        String documentId = UUID.randomUUID().toString();
        Path target = storagePath(documentId + ".pdf");
        List<String> addedIds = List.of();
        try {
            byte[] bytes;
            try (InputStream input = file.getInputStream()) {
                bytes = input.readNBytes(Math.toIntExact(maxPdfBytes + 1));
            }
            if (bytes.length > maxPdfBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF 文件不能超过 20 MB");
            }
            if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw new IllegalArgumentException("文件内容不是有效的 PDF");
            }
            String hash = digest(bytes);
            StoredPdf previous = documents.get(chatId);
            if (previous != null && hash.equals(previous.sha256()) && Files.isRegularFile(storagePath(previous.storageName()))) {
                return result(previous, true);
            }
            if (previous != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话已关联其他 PDF，请新建会话后上传");
            }
            Resource resource = new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            };
            List<Document> pages;
            try {
                pages = PdfTextExtractor.read(resource);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("PDF 无法解析，请确认文件未损坏且未加密", exception);
            }
            if (pages.size() > 300) throw new IllegalArgumentException("PDF 最多支持 300 页，请拆分后上传");
            List<Document> chunks = TokenTextSplitter.builder().withChunkSize(800)
                    .withMinChunkSizeChars(100).withMinChunkLengthToEmbed(5)
                    .withMaxNumChunks(10000).withKeepSeparator(true).build().apply(pages)
                    .stream().filter(doc -> doc.getText() != null && !doc.getText().isBlank()).toList();
            if (chunks.isEmpty()) throw new IllegalArgumentException("PDF 中未提取到文字；扫描件需要先进行 OCR 识别");
            for (Document chunk : chunks) {
                chunk.getMetadata().put("chat_id", chatId);
                chunk.getMetadata().put("document_id", documentId);
                chunk.getMetadata().put("file_name", filename);
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            addedIds = chunks.stream().map(Document::getId).toList();
            vectorStore.add(chunks);
            persistVectors();
            StoredPdf stored = new StoredPdf(chatId, documentId, filename, hash, target.getFileName().toString(),
                    addedIds, chunks.size(), false);
            writeManifest(stored);
            documents.put(chatId, stored);
            return result(stored, false);
        } catch (IOException exception) {
            rollback(addedIds, target);
            throw new IllegalStateException("PDF 保存失败，请稍后重试", exception);
        } catch (RuntimeException exception) {
            rollback(addedIds, target);
            throw exception;
        }
    }

    @Override public Resource getFile(String chatId) {
        return new FileSystemResource(storagePath(requireDocument(chatId).storageName()));
    }

    @Override public String getFilename(String chatId) {
        return requireDocument(chatId).filename();
    }

    @Override public String getFilterExpression(String chatId) {
        StoredPdf document = requireDocument(chatId);
        return document.legacy() ? "chat_id == '" + document.chatId() + "'"
                : "document_id == '" + document.documentId() + "'";
    }

    private StoredPdf requireDocument(String chatId) {
        ConversationIds.validate(chatId);
        StoredPdf stored = documents.get(chatId);
        if (stored == null || !Files.isRegularFile(storagePath(stored.storageName()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话文件不存在，请先上传 PDF");
        }
        return stored;
    }

    private Path storagePath(String name) {
        if (name == null || !name.matches("[a-f0-9-]+\\.pdf")) throw new IllegalArgumentException("无效的文件存储标识");
        return root.resolve("pdf").resolve(name);
    }

    private String safeFilename(String original) {
        if (original == null) throw new IllegalArgumentException("文件名不能为空");
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank() || name.length() > 200 || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("请上传文件名长度不超过 200 的 PDF 文件");
        }
        return name;
    }

    private PdfUploadResult result(StoredPdf doc, boolean reused) {
        return new PdfUploadResult(1, reused ? "文件已存在，无需重复处理" : "上传成功", doc.chatId(),
                doc.documentId(), doc.filename(), doc.chunkCount(), reused);
    }

    private synchronized void persistVectors() throws IOException {
        if (vectorStore instanceof SimpleVectorStore simple) {
            Path temporary = Files.createTempFile(root, "vectors-", ".json");
            try {
                simple.save(temporary.toFile());
                atomicMove(temporary, root.resolve("vectors.json"));
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    private void writeManifest(StoredPdf stored) throws IOException {
        Path directory = root.resolve("documents");
        Path temporary = Files.createTempFile(directory, "manifest-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writeValueAsString(stored));
            atomicMove(temporary, directory.resolve(digest(stored.chatId().getBytes(StandardCharsets.UTF_8)) + ".json"));
        } finally { Files.deleteIfExists(temporary); }
    }

    private void atomicMove(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    private void rollback(List<String> addedIds, Path target) {
        try {
            if (!addedIds.isEmpty()) { vectorStore.delete(addedIds); persistVectors(); }
            Files.deleteIfExists(target);
        } catch (Exception exception) { log.warn("Failed to clean up an unsuccessful PDF upload", exception); }
    }

    /** Copy legacy files without re-embedding or changing the original properties/snapshot. */
    private void migrateLegacyFiles() throws IOException {
        Path legacy = legacyRoot.resolve("chat-pdf.properties");
        if (!Files.exists(legacy)) return;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) { properties.load(reader); }
        for (String chatId : properties.stringPropertyNames()) {
            if (documents.containsKey(chatId)) continue;
            ConversationIds.validate(chatId);
            String filename = safeFilename(properties.getProperty(chatId));
            Path original = legacyRoot.resolve(filename);
            if (!Files.isRegularFile(original)) continue;
            String id = UUID.randomUUID().toString();
            byte[] bytes = Files.readAllBytes(original);
            Files.write(storagePath(id + ".pdf"), bytes, StandardOpenOption.CREATE_NEW);
            StoredPdf stored = new StoredPdf(chatId, id, filename, digest(bytes), id + ".pdf", List.of(), 0, true);
            writeManifest(stored);
            documents.put(chatId, stored);
        }
        persistVectors();
    }

    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record StoredPdf(String chatId, String documentId, String filename, String sha256,
                            String storageName, List<String> vectorIds, int chunkCount, boolean legacy) { }
}
