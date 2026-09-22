package com.filevault.controller;

import com.filevault.dto.FileMetadataResponse;
import com.filevault.exception.FileVaultExceptions.InvalidRequestException;
import com.filevault.model.FileRecord;
import com.filevault.service.FileService;
import com.filevault.service.FileService.VaultStats;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** REST API for storing and retrieving files. */
@RestController
@RequestMapping("/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * Stores an uploaded file.
     *
     * @param file multipart part named {@code file}
     * @return 201 with the stored file's metadata and a {@code Location} header
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileMetadataResponse> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new InvalidRequestException("Uploaded file is empty");
        }
        try (InputStream source = file.getInputStream()) {
            FileRecord record =
                    fileService.upload(source, file.getOriginalFilename(), file.getContentType());
            return ResponseEntity.created(URI.create("/files/" + record.getFileId()))
                    .body(FileMetadataResponse.from(record));
        }
    }

    /**
     * Streams a file back, reassembled, verified and decompressed.
     *
     * <p>The body is produced lazily so the server never holds more than one chunk in memory.
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String fileId) {
        FileRecord record = fileService.getMetadata(fileId);

        MediaType contentType =
                record.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : parseOrOctetStream(record.getContentType());

        ContentDisposition disposition =
                ContentDisposition.attachment()
                        .filename(record.getFilename(), StandardCharsets.UTF_8)
                        .build();

        StreamingResponseBody body = sink -> fileService.writeContentTo(fileId, sink);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_TYPE, contentType.toString())
                .header("X-Content-SHA256", record.getOriginalSha256())
                .contentLength(record.getOriginalSize())
                .body(body);
    }

    /** Metadata for one file. */
    @GetMapping("/{fileId}")
    public FileMetadataResponse metadata(@PathVariable String fileId) {
        return FileMetadataResponse.from(fileService.getMetadata(fileId));
    }

    /** All stored files, newest first. */
    @GetMapping
    public List<FileMetadataResponse> list() {
        return fileService.listAll().stream().map(FileMetadataResponse::from).toList();
    }

    /** Deletes a file's metadata and chunks. */
    @DeleteMapping("/{fileId}")
    public Map<String, String> delete(@PathVariable String fileId) {
        fileService.delete(fileId);
        return Map.of("message", "File deleted successfully", "fileId", fileId);
    }

    /** Liveness probe kept at the documented path; {@code /actuator/health} is the richer one. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "message", "FileVault server is running");
    }

    /** Vault-wide storage totals. */
    @GetMapping("/stats")
    public VaultStats stats() {
        return fileService.stats();
    }

    private MediaType parseOrOctetStream(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (org.springframework.http.InvalidMediaTypeException e) {
            log.debug("Stored content type {} is not parseable, falling back to octet-stream", value);
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
