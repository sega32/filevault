package com.filevault.repository;

import com.filevault.model.Chunk;
import com.filevault.model.ChunkId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Data access for chunk metadata. */
@Repository
public interface ChunkRepository extends JpaRepository<Chunk, ChunkId> {

    /** Chunks of a file in storage order — the order they must be reassembled in. */
    List<Chunk> findByIdFileIdOrderByIdChunkIndexAsc(String fileId);

    long countByIdFileId(String fileId);
}
