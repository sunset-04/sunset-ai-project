package com.sunset.ai.controller;

import com.sunset.ai.entity.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<Result> missingParameter(Exception exception) {
        return ResponseEntity.badRequest().body(Result.fail("请求参数缺失或格式不正确"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result> invalidJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Result.fail("请求内容格式不正确"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result> method(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Result.fail("不支持此请求方式"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Result.fail(exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Result.fail(exception.getReason()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result> size(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Result.fail("上传文件超过大小限制，PDF 最多为 20 MB"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> unexpected(Exception exception) {
        log.error("API request failed", exception);
        return ResponseEntity.internalServerError().body(Result.fail("服务暂时不可用，请稍后重试"));
    }
}
