package com.filevault.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;

/**
 * Gzip compression and decompression.
 *
 * <p>Both directions are exposed as stream decorators rather than byte-array transforms so that
 * arbitrarily large files pass through in constant memory.
 */
@Service
public class CompressionService {

    /** Deflate level 6 — the usual balance of ratio against CPU. */
    public static final int COMPRESSION_LEVEL = 6;

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Wraps a sink so that everything written to the returned stream arrives gzip-compressed.
     *
     * <p>The caller must close the returned stream to flush the deflater and write the gzip
     * trailer; closing it also closes {@code sink}.
     */
    public GZIPOutputStream compressing(OutputStream sink) throws IOException {
        return new LevelledGzipOutputStream(sink, BUFFER_SIZE, COMPRESSION_LEVEL);
    }

    /** Wraps a gzip-compressed source so that reads return the original bytes. */
    public GZIPInputStream decompressing(InputStream source) throws IOException {
        return new GZIPInputStream(source, BUFFER_SIZE);
    }

    /**
     * {@link GZIPOutputStream} exposes no setter for the deflate level; the underlying {@code
     * Deflater} is only reachable from a subclass.
     */
    private static final class LevelledGzipOutputStream extends GZIPOutputStream {
        LevelledGzipOutputStream(OutputStream sink, int bufferSize, int level) throws IOException {
            super(sink, bufferSize);
            this.def.setLevel(level);
        }
    }
}
