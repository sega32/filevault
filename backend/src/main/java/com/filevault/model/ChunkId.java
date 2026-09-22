package com.filevault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite primary key for {@link Chunk}: a chunk is identified by its file and its position. */
@Embeddable
public class ChunkId implements Serializable {

    @Column(name = "file_id", nullable = false)
    private String fileId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    protected ChunkId() {
        // required by JPA
    }

    public ChunkId(String fileId, int chunkIndex) {
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
    }

    public String getFileId() {
        return fileId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChunkId other
                && chunkIndex == other.chunkIndex
                && Objects.equals(fileId, other.fileId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileId, chunkIndex);
    }

    @Override
    public String toString() {
        return fileId + "#" + chunkIndex;
    }
}
