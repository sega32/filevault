package com.filevault.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to the FileVault REST API.
 *
 * <p>Named {@code VaultHttpClient} to avoid colliding with {@link java.net.http.HttpClient}, which
 * it wraps. Uploads stream straight from disk and downloads stream straight to disk, so the client
 * handles files far larger than its heap.
 */
public class VaultHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Uploads and downloads of large files must not be cut off by a request timeout. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofHours(6);

    private final String baseUrl;
    private final String apiKey;
    private final String apiKeyHeader;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public VaultHttpClient(String baseUrl, String apiKey, String apiKeyHeader) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.apiKeyHeader = apiKeyHeader;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** @return true if the server answers its health endpoint */
    public boolean health() {
        try {
            HttpResponse<String> response =
                    send(get("/files/health").build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (CliException e) {
            return false;
        }
    }

    /**
     * Uploads a file as {@code multipart/form-data}, streaming it from disk.
     *
     * @return metadata for the stored file
     */
    public FileMetadata upload(Path file) {
        String boundary = "FileVaultBoundary" + UUID.randomUUID().toString().replace("-", "");
        String filename = file.getFileName().toString();
        String contentType = probeContentType(file);

        String prefix =
                "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                        + filename.replace("\"", "")
                        + "\"\r\nContent-Type: "
                        + contentType
                        + "\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";

        HttpRequest request =
                post("/files/upload")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(
                                HttpRequest.BodyPublishers.ofInputStream(
                                        () -> multipartBody(prefix, file, suffix)))
                        .build();

        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "upload " + filename);
        return parse(response.body(), FileMetadata.class);
    }

    private InputStream multipartBody(String prefix, Path file, String suffix) {
        try {
            return new SequenceInputStream(
                    Collections.enumeration(
                            List.of(
                                    new ByteArrayInputStream(
                                            prefix.getBytes(StandardCharsets.UTF_8)),
                                    Files.newInputStream(file),
                                    new ByteArrayInputStream(
                                            suffix.getBytes(StandardCharsets.UTF_8)))));
        } catch (IOException e) {
            throw new CliException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Downloads a file to {@code destination}, verifying the SHA-256 the server reports.
     *
     * @return the number of bytes written
     * @throws CliException if the downloaded bytes do not match the server's digest
     */
    public long download(String fileId, Path destination) {
        HttpRequest request = get("/files/" + fileId + "/download").build();
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String body = readErrorBody(response);
            throw new CliException(describeFailure(response.statusCode(), body, "download " + fileId));
        }

        String expectedHash = response.headers().firstValue("X-Content-SHA256").orElse(null);
        long expectedLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest digest = newDigest();

        long written;
        Path parent = destination.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream source = new DigestInputStream(response.body(), digest);
                    OutputStream sink = Files.newOutputStream(destination)) {
                written = source.transferTo(sink);
            }
        } catch (IOException e) {
            // A server-side integrity failure past the first chunk shows up here, as a connection
            // that dies mid-body. Say so, rather than surfacing a bare "closed".
            throw new CliException(
                    "Download of "
                            + fileId
                            + " ended early: "
                            + e.getMessage()
                            + "\nThe server aborted the transfer, which usually means a stored chunk "
                            + "failed its integrity check. Check the server log.",
                    e);
        }

        if (expectedLength >= 0 && written != expectedLength) {
            throw new CliException(
                    "Download of "
                            + fileId
                            + " is incomplete: expected "
                            + expectedLength
                            + " bytes but received "
                            + written
                            + ".\nThe partial file has been left at "
                            + destination
                            + " for inspection.");
        }

        if (expectedHash != null) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedHash)) {
                throw new CliException(
                        "Integrity check FAILED for "
                                + destination
                                + "\n  expected SHA-256: "
                                + expectedHash
                                + "\n  actual   SHA-256: "
                                + actual
                                + "\nThe downloaded file has been left in place for inspection.");
            }
        }
        return written;
    }

    /** Metadata for one file. */
    public FileMetadata info(String fileId) {
        HttpResponse<String> response =
                send(get("/files/" + fileId).build(), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "look up " + fileId);
        return parse(response.body(), FileMetadata.class);
    }

    /** All stored files, newest first. */
    public List<FileMetadata> list() {
        HttpResponse<String> response =
                send(get("/files").build(), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "list files");
        try {
            List<FileMetadata> files =
                    gson.fromJson(response.body(), new TypeToken<List<FileMetadata>>() {}.getType());
            return files == null ? List.of() : files;
        } catch (JsonSyntaxException e) {
            throw new CliException("Server returned a response the client could not parse", e);
        }
    }

    /** Deletes a file and its chunks. */
    public void delete(String fileId) {
        HttpResponse<String> response =
                send(
                        baseRequest("/files/" + fileId).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "delete " + fileId);
    }

    // ---------------------------------------------------------------- helpers

    private HttpRequest.Builder get(String path) {
        return baseRequest(path).GET();
    }

    private HttpRequest.Builder post(String path) {
        return baseRequest(path);
    }

    private HttpRequest.Builder baseRequest(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(REQUEST_TIMEOUT);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(apiKeyHeader, apiKey);
        }
        return builder;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (ConnectException e) {
            throw new CliException(
                    "Cannot connect to the FileVault server at "
                            + baseUrl
                            + "\nIs the backend running?  java -jar backend/target/"
                            + "filevault-backend-1.0.0.jar",
                    e);
        } catch (IOException e) {
            throw new CliException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Request was interrupted", e);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String action) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw new CliException(describeFailure(status, response.body(), action));
    }

    /** Turns an RFC 9457 problem response into a one-line explanation. */
    private String describeFailure(int status, String body, String action) {
        String detail = extractProblemDetail(body);
        String base = "Could not " + action + " (HTTP " + status + ")";
        if (status == 401) {
            return base
                    + "\nThe server requires an API key. Pass --api-key, or set FILEVAULT_API_KEY.";
        }
        return detail == null || detail.isBlank() ? base : base + ": " + detail;
    }

    private String extractProblemDetail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return Optional.ofNullable(json.get("detail"))
                    .or(() -> Optional.ofNullable(json.get("message")))
                    .map(element -> element.getAsString())
                    .orElse(null);
        } catch (RuntimeException e) {
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }
    }

    private String readErrorBody(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            T parsed = gson.fromJson(body, type);
            if (parsed == null) {
                throw new CliException("Server returned an empty response");
            }
            return parsed;
        } catch (JsonSyntaxException e) {
            throw new CliException("Server returned a response the client could not parse", e);
        }
    }

    private String probeContentType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? "application/octet-stream" : probed;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
