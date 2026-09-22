package com.filevault.exception;

import com.filevault.exception.FileVaultExceptions.FileNotFoundException;
import com.filevault.exception.FileVaultExceptions.IntegrityException;
import com.filevault.exception.FileVaultExceptions.InvalidRequestException;
import com.filevault.exception.FileVaultExceptions.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Translates domain exceptions into RFC 9457 problem responses.
 *
 * <p>Client errors are logged at debug and echoed back in full; server errors are logged with a
 * stack trace but reported to the caller without internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE_TYPE = "https://filevault.dev/problems/";

    @ExceptionHandler(FileNotFoundException.class)
    public ProblemDetail handleNotFound(FileNotFoundException e, HttpServletRequest request) {
        log.debug("File not found: {}", e.getFileId());
        return problem(HttpStatus.NOT_FOUND, "File not found", e.getMessage(), "file-not-found", request);
    }

    @ExceptionHandler({InvalidRequestException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, IllegalArgumentException.class})
    public ProblemDetail handleBadRequest(Exception e, HttpServletRequest request) {
        log.debug("Rejected request to {}: {}", request.getRequestURI(), e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), "invalid-request", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        log.warn("Upload rejected as too large: {}", e.getMessage());
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Upload too large",
                "The uploaded file exceeds the configured maximum size",
                "upload-too-large",
                request);
    }

    @ExceptionHandler(IntegrityException.class)
    public ProblemDetail handleIntegrity(IntegrityException e, HttpServletRequest request) {
        // A verification failure means stored data is damaged; it warrants an alertable log line.
        log.error("Integrity failure on {}: {}", request.getRequestURI(), e.getMessage());
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Integrity verification failed",
                e.getMessage(),
                "integrity-failure",
                request);
    }

    @ExceptionHandler(StorageException.class)
    public ProblemDetail handleStorage(StorageException e, HttpServletRequest request) {
        log.error("Storage failure on {}", request.getRequestURI(), e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Storage failure",
                "The server could not complete the storage operation",
                "storage-failure",
                request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred",
                "internal-error",
                request);
    }

    private ProblemDetail problem(
            HttpStatus status, String title, String detail, String type, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE_TYPE + type));
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }
}
