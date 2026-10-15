package com.sunset.ai.entity.vo;

public record PdfUploadResult(int ok, String msg, String chatId, String documentId,
                              String filename, int chunkCount, boolean reused) { }
