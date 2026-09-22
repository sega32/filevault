package com.filevault.cli;

/**
 * Client-side view of a stored file, as returned by the API.
 *
 * <p>Fields are non-final and package-private so Gson can populate them reflectively.
 */
public class FileMetadata {

    String fileId;
    String filename;
    String contentType;
    long originalSize;
    long compressedSize;
    double compressionRatio;
    int chunkCount;
    String sha256;
    String createdAt;

    public String fileId() {
        return fileId;
    }

    public String filename() {
        return filename;
    }

    public long originalSize() {
        return originalSize;
    }

    public long compressedSize() {
        return compressedSize;
    }

    public double compressionRatio() {
        return compressionRatio;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public String sha256() {
        return sha256;
    }

    public String createdAt() {
        return createdAt;
    }

    public String contentType() {
        return contentType;
    }
}
