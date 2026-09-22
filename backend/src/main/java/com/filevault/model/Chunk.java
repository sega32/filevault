package com.filevault.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Metadata for one chunk of a stored file.
 *
 * <p>{@link #getChunkHash()} is the SHA-256 of the bytes <em>as written to disk</em> — after
 * encryption, when at-rest encryption is enabled — so verification covers exactly what was stored.
 */
@Entity
@Table(name = "chunks")
public class Chunk {

    @EmbeddedId
    private ChunkId id;

    @MapsId("fileId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileRecord file;

    /** Hex-encoded SHA-256 of the stored chunk bytes. */
    @Column(name = "chunk_hash", nullable = false)
    private String chunkHash;

    /** Size of the chunk on disk, in bytes. */
    @Column(name = "chunk_size", nullable = false)
    private long chunkSize;

    protected Chunk() {
        // required by JPA
    }

    public Chunk(String fileId, int chunkIndex, String chunkHash, long chunkSize) {
        this.id = new ChunkId(fileId, chunkIndex);
        this.chunkHash = Objects.requireNonNull(chunkHash, "chunkHash");
        this.chunkSize = chunkSize;
    }

    public ChunkId getId() {
        return id;
    }

    public int getChunkIndex() {
        return id.getChunkIndex();
    }

    public String getChunkHash() {
        return chunkHash;
    }

    public long getChunkSize() {
        return chunkSize;
    }

    public FileRecord getFile() {
        return file;
    }

    void setFile(FileRecord file) {
        this.file = file;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chunk other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Chunk[" + id + ", size=" + chunkSize + "]";
    }
}
