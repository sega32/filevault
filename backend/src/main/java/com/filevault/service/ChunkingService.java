package com.filevault.service;

import com.filevault.config.FileVaultProperties;
import com.filevault.service.StorageService.StoredChunk;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Splits a byte stream into fixed-size chunks and hands each one to {@link StorageService}.
 *
 * <p>The writer holds at most one chunk in memory (4 MB by default) no matter how large the file
 * is, which is what keeps a 5 GB upload from becoming a 5 GB heap.
 */
@Service
public class ChunkingService {

    private final int chunkSize;
    private final StorageService storage;

    public ChunkingService(FileVaultProperties properties, StorageService storage) {
        this.chunkSize = properties.chunk().size();
        this.storage = storage;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    /** Number of chunks a payload of {@code totalBytes} will occupy. */
    public int chunkCountFor(long totalBytes) {
        return (int) ((totalBytes + chunkSize - 1) / chunkSize);
    }

    /**
     * Opens a writer that persists everything written to it as chunks of {@code fileId}.
     *
     * <p>The caller must close the writer to flush the final partial chunk.
     */
    public ChunkWriter newWriter(String fileId) {
        StorageService.validateFileId(fileId);
        return new ChunkWriter(fileId);
    }

    /** An {@link OutputStream} that materialises its input as numbered chunks on disk. */
    public final class ChunkWriter extends OutputStream {

        private final String fileId;
        private final byte[] buffer = new byte[chunkSize];
        private final List<ChunkDescriptor> written = new ArrayList<>();

        private int buffered;
        private long totalBytes;
        private boolean closed;

        private ChunkWriter(String fileId) {
            this.fileId = fileId;
        }

        @Override
        public void write(int b) {
            ensureOpen();
            buffer[buffered++] = (byte) b;
            totalBytes++;
            if (buffered == chunkSize) {
                flushChunk();
            }
        }

        @Override
        public void write(byte[] source, int offset, int length) {
            ensureOpen();
            if (offset < 0 || length < 0 || offset + length > source.length) {
                throw new IndexOutOfBoundsException(
                        "offset=" + offset + " length=" + length + " capacity=" + source.length);
            }
            int position = offset;
            int remaining = length;
            while (remaining > 0) {
                int take = Math.min(remaining, chunkSize - buffered);
                System.arraycopy(source, position, buffer, buffered, take);
                buffered += take;
                position += take;
                remaining -= take;
                totalBytes += take;
                if (buffered == chunkSize) {
                    flushChunk();
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (buffered > 0) {
                flushChunk();
            }
            closed = true;
        }

        private void flushChunk() {
            byte[] payload = new byte[buffered];
            System.arraycopy(buffer, 0, payload, 0, buffered);
            int index = written.size();
            StoredChunk stored = storage.writeChunk(fileId, index, payload);
            written.add(new ChunkDescriptor(index, stored.hash(), stored.storedSize()));
            buffered = 0;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("ChunkWriter for " + fileId + " is already closed");
            }
        }

        /** Descriptors of the chunks written so far, in order. Read this after {@link #close()}. */
        public List<ChunkDescriptor> chunks() {
            return List.copyOf(written);
        }

        /** Total number of bytes fed into this writer — the compressed size of the file. */
        public long totalBytes() {
            return totalBytes;
        }
    }

    /**
     * A chunk that has been persisted.
     *
     * @param index      position in the file, starting at 0
     * @param hash       hex SHA-256 of the bytes on disk
     * @param storedSize bytes occupied on disk
     */
    public record ChunkDescriptor(int index, String hash, long storedSize) {}
}
