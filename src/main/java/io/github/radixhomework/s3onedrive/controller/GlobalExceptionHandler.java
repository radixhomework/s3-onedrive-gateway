package io.github.radixhomework.s3onedrive.controller;

import io.github.radixhomework.s3onedrive.model.S3Xml;
import io.github.radixhomework.s3onedrive.util.XmlUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebClientResponseException.class)
    public void handleGraphError(WebClientResponseException ex,
                                 HttpServletResponse response) throws IOException {
        log.error("Graph API error: {} {}", ex.getStatusCode(), ex.getMessage());

        int status = switch (ex.getStatusCode().value()) {
            case 404 -> 404;
            case 403, 401 -> 403;
            case 409 -> 409;
            default -> 500;
        };

        String code = switch (ex.getStatusCode().value()) {
            case 404 -> "NoSuchKey";
            case 403, 401 -> "AccessDenied";
            case 409 -> "BucketAlreadyExists";
            default -> "InternalError";
        };

        sendError(response, status, code, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void handleBadArgument(IllegalArgumentException ex,
                                  HttpServletResponse response) throws IOException {
        log.warn("Bad request: {}", ex.getMessage());
        sendError(response, 400, "InvalidArgument", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public void handleGenericError(Exception ex,
                                   HttpServletResponse response) throws IOException {
        log.error("Unexpected error", ex);
        sendError(response, 500, "InternalError",
            "An unexpected error occurred: " + ex.getMessage());
    }

    private void sendError(HttpServletResponse response,
                           int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        S3Xml.S3Error error = S3Xml.S3Error.builder()
            .code(code)
            .message(message)
            .requestId(UUID.randomUUID().toString())
            .build();
        XmlUtil.writeXml(response.getOutputStream(), error);
    }
}
