package com.filevault.repository;

import com.filevault.model.FileRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Data access for file metadata. */
@Repository
public interface FileRepository extends JpaRepository<FileRecord, String> {

    /** All files, newest first. */
    List<FileRecord> findAllByOrderByCreatedAtDesc();

    /** Total bytes occupied on disk across every stored file, or 0 when the vault is empty. */
    @Query("select coalesce(sum(c.chunkSize), 0) from Chunk c")
    long totalStoredBytes();

    /** Total size of the original, uncompressed files, or 0 when the vault is empty. */
    @Query("select coalesce(sum(f.originalSize), 0) from FileRecord f")
    long totalOriginalBytes();
}
