package com.filevault.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Metadata for one stored file.
 *
 * <p>Named {@code FileRecord} rather than {@code File} deliberately: an entity named {@code File}
 * shadows {@link java.io.File} throughout the storage layer, which is exactly where the confusion
 * would be most expensive.
 */
@Entity
@Table(name = "files")
public class FileRecord {

    @Id
    @Column(name = "file_id", nullable = false, updatable = false)
    private String fileId;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "content_type")
    private String contentType;

    /** Size of the file as the client uploaded it, in bytes. */
    @Column(name = "original_size", nullable = false)
    private long originalSize;

    /** Size after gzip, in bytes; the sum of the plaintext chunk payloads. */
    @Column(name = "compressed_size", nullable = false)
    private long compressedSize;

    /** SHA-256 of the original (uncompressed) bytes, for end-to-end verification by clients. */
    @Column(name = "original_sha256", nullable = false)
    private String originalSha256;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(
            mappedBy = "file",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("id.chunkIndex ASC")
    private List<Chunk> chunks = new ArrayList<>();

    protected FileRecord() {
        // required by JPA
    }

    public FileRecord(
            String fileId,
            String filename,
            String contentType,
            long originalSize,
            long compressedSize,
            String originalSha256,
            int chunkCount,
            Instant createdAt) {
        this.fileId = Objects.requireNonNull(fileId, "fileId");
        this.filename = Objects.requireNonNull(filename, "filename");
        this.contentType = contentType;
        this.originalSize = originalSize;
        this.compressedSize = compressedSize;
        this.originalSha256 = Objects.requireNonNull(originalSha256, "originalSha256");
        this.chunkCount = chunkCount;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Attaches a chunk to this file, keeping both sides of the association consistent. */
    public void addChunk(Chunk chunk) {
        chunk.setFile(this);
        chunks.add(chunk);
    }

    /**
     * Space saved by compression, as a percentage of the original size. Returns 0 for empty files
     * and clamps negatives to 0 — gzip can expand incompressible input, which is not a "ratio"
     * anyone wants to read as a negative number.
     */
    public double compressionRatio() {
        if (originalSize <= 0) {
            return 0.0;
        }
        double saved = (originalSize - compressedSize) * 100.0 / originalSize;
        return Math.max(saved, 0.0);
    }

    public String getFileId() {
        return fileId;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getOriginalSize() {
        return originalSize;
    }

    public long getCompressedSize() {
        return compressedSize;
    }

    public String getOriginalSha256() {
        return originalSha256;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Chunk> getChunks() {
        return chunks;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileRecord other && Objects.equals(fileId, other.fileId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(fileId);
    }

    @Override
    public String toString() {
        return "FileRecord[" + fileId + ", " + filename + ", chunks=" + chunkCount + "]";
    }
}
