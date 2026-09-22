package com.filevault.dto;

import com.filevault.model.FileRecord;
import java.time.Instant;

/**
 * API representation of a stored file.
 *
 * @param fileId           opaque UUID used for all subsequent operations
 * @param filename         sanitised original filename
 * @param contentType      declared content type, may be null
 * @param originalSize     bytes before compression
 * @param compressedSize   bytes after compression
 * @param compressionRatio percentage of space saved
 * @param chunkCount       number of chunks on disk
 * @param sha256           SHA-256 of the original bytes, for client-side verification
 * @param createdAt        upload timestamp
 */
public record FileMetadataResponse(
        String fileId,
        String filename,
        String contentType,
        long originalSize,
        long compressedSize,
        double compressionRatio,
        int chunkCount,
        String sha256,
        Instant createdAt) {

    public static FileMetadataResponse from(FileRecord record) {
        return new FileMetadataResponse(
                record.getFileId(),
                record.getFilename(),
                record.getContentType(),
                record.getOriginalSize(),
                record.getCompressedSize(),
                Math.round(record.compressionRatio() * 100.0) / 100.0,
                record.getChunkCount(),
                record.getOriginalSha256(),
                record.getCreatedAt());
    }
}
