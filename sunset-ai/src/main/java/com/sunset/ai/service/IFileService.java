package com.sunset.ai.service;

import com.sunset.ai.entity.vo.PdfUploadResult;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IFileService {
    PdfUploadResult upload(String chatId, MultipartFile file);
    Resource getFile(String chatId);
    String getFilename(String chatId);
    String getFilterExpression(String chatId);
}
