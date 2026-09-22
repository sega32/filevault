package com.filevault.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CompressionServiceTest {

    private final CompressionService compression = new CompressionService();

    @Test
    void roundTripsBytesExactly() throws Exception {
        byte[] original = "the quick brown fox ".repeat(500).getBytes(StandardCharsets.UTF_8);

        byte[] compressed = compress(original);
        byte[] restored = decompress(compressed);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void compressesRepetitiveTextSubstantially() throws Exception {
        byte[] original = "log line: everything is fine\n".repeat(2000).getBytes(StandardCharsets.UTF_8);

        byte[] compressed = compress(original);

        assertThat(compressed.length).isLessThan(original.length / 10);
    }

    @Test
    void roundTripsIncompressibleDataWithoutCorruption() throws Exception {
        byte[] noise = new byte[256 * 1024];
        new Random(42).nextBytes(noise);

        assertThat(decompress(compress(noise))).isEqualTo(noise);
    }

    @Test
    void roundTripsEmptyInput() throws Exception {
        assertThat(decompress(compress(new byte[0]))).isEmpty();
    }

    private byte[] compress(byte[] data) throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream gzip = compression.compressing(sink)) {
            gzip.write(data);
        }
        return sink.toByteArray();
    }

    private byte[] decompress(byte[] data) throws Exception {
        try (InputStream source = compression.decompressing(new ByteArrayInputStream(data))) {
            return source.readAllBytes();
        }
    }
}
