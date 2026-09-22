package com.filevault.service;

import com.filevault.config.FileVaultProperties;
import com.filevault.exception.FileVaultExceptions.IntegrityException;
import com.filevault.exception.FileVaultExceptions.InvalidRequestException;
import com.filevault.exception.FileVaultExceptions.StorageException;
import com.filevault.model.Chunk;
import com.filevault.util.HashUtil;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Disk I/O for chunks.
 *
 * <p>Layout is one directory per file: {@code <storage-root>/<file-id>/chunk_00000.bin}. Chunk ids
 * are UUIDs and every resolved path is checked to stay under the storage root, so a crafted id can
 * never escape the vault directory.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final String CHUNK_NAME_FORMAT = "chunk_%05d.bin";

    private final Path root;
    private final EncryptionService encryption;

    public StorageService(FileVaultProperties properties, EncryptionService encryption) {
        this.root = Path.of(properties.storage().path()).toAbsolutePath().normalize();
        this.encryption = encryption;
    }

    /** Creates the storage root if it does not exist, failing fast if it is not usable. */
    @PostConstruct
    void initialise() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new StorageException("Cannot create storage directory " + root, e);
        }
        if (!Files.isWritable(root)) {
            throw new StorageException("Storage directory is not writable: " + root);
        }
        log.info(
                "Storage root {} ready (at-rest encryption: {})",
                root,
                encryption.isEnabled() ? "enabled" : "disabled");
    }

    public Path getRoot() {
        return root;
    }

    /**
     * Rejects anything that is not a UUID before it reaches the filesystem.
     *
     * @throws InvalidRequestException if {@code fileId} is not a well-formed UUID
     */
    public static void validateFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new InvalidRequestException("File id is required");
        }
        try {
            UUID.fromString(fileId);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("File id must be a UUID: " + fileId);
        }
    }

    /** Resolves a file's chunk directory, refusing any path that escapes the storage root. */
    Path chunkDirectory(String fileId) {
        validateFileId(fileId);
        Path dir = root.resolve(fileId).normalize();
        if (!dir.startsWith(root)) {
            throw new InvalidRequestException("Resolved path escapes the storage root");
        }
        return dir;
    }

    Path chunkPath(String fileId, int chunkIndex) {
        if (chunkIndex < 0) {
            throw new InvalidRequestException("Chunk index must not be negative");
        }
        return chunkDirectory(fileId).resolve(String.format(CHUNK_NAME_FORMAT, chunkIndex));
    }

    /**
     * Writes one chunk, encrypting it first when at-rest encryption is enabled.
     *
     * @param payload the compressed bytes belonging to this chunk
     * @return the SHA-256 and byte count of what actually landed on disk
     */
    public StoredChunk writeChunk(String fileId, int chunkIndex, byte[] payload) {
        byte[] stored = encryption.encrypt(payload);
        Path path = chunkPath(fileId, chunkIndex);
        try {
            Files.createDirectories(path.getParent());
            Files.write(
                    path,
                    stored,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new StorageException("Failed to write chunk " + chunkIndex + " of " + fileId, e);
        }
        return new StoredChunk(HashUtil.sha256Hex(stored), stored.length);
    }

    /**
     * Reads one chunk and verifies it against its recorded metadata <em>before</em> returning any
     * bytes, so corruption is caught while it can still be reported as an error rather than as a
     * truncated download.
     *
     * @return the decrypted, still-compressed chunk payload
     * @throws IntegrityException if the chunk is missing, the wrong size, or fails its hash check
     */
    public byte[] readChunk(String fileId, Chunk metadata) {
        Path path = chunkPath(fileId, metadata.getChunkIndex());
        byte[] stored;
        try {
            stored = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            throw new IntegrityException(
                    "Chunk " + metadata.getChunkIndex() + " of " + fileId + " is missing from disk");
        } catch (IOException e) {
            throw new StorageException("Failed to read chunk " + path, e);
        }

        if (stored.length != metadata.getChunkSize()) {
            throw new IntegrityException(
                    "Chunk %d of %s has size %d, expected %d"
                            .formatted(
                                    metadata.getChunkIndex(),
                                    fileId,
                                    stored.length,
                                    metadata.getChunkSize()));
        }
        String actualHash = HashUtil.sha256Hex(stored);
        if (!HashUtil.matches(metadata.getChunkHash(), actualHash)) {
            throw new IntegrityException(
                    "Chunk %d of %s failed SHA-256 verification (expected %s, got %s)"
                            .formatted(
                                    metadata.getChunkIndex(),
                                    fileId,
                                    metadata.getChunkHash(),
                                    actualHash));
        }
        return encryption.decrypt(stored);
    }

    /**
     * Recursively deletes a file's chunk directory. Safe to call for a file that was never written
     * — used both for deletes and to clean up after a failed upload.
     */
    public void deleteFile(String fileId) {
        Path dir = chunkDirectory(fileId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        } catch (IOException | UncheckedIOException e) {
            throw new StorageException("Failed to delete chunk directory " + dir, e);
        }
    }

    /** Free space on the storage volume, in bytes; {@code -1} when it cannot be determined. */
    public long usableSpaceBytes() {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            log.warn("Could not determine free space for {}: {}", root, e.getMessage());
            return -1;
        }
    }

    /**
     * The result of storing one chunk.
     *
     * @param hash       hex SHA-256 of the bytes on disk
     * @param storedSize number of bytes on disk
     */
    public record StoredChunk(String hash, long storedSize) {}
}
