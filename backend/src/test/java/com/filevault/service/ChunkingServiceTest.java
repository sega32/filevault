package com.filevault.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.filevault.config.FileVaultProperties;
import com.filevault.service.ChunkingService.ChunkDescriptor;
import com.filevault.service.ChunkingService.ChunkWriter;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkingServiceTest {

    private static final int CHUNK_SIZE = 1024;

    @TempDir Path storageRoot;

    private ChunkingService chunking;
    private StorageService storage;
    private String fileId;

    @BeforeEach
    void setUp() {
        FileVaultProperties properties =
                new FileVaultProperties(
                        new FileVaultProperties.Storage(storageRoot.toString()),
                        new FileVaultProperties.Chunk(CHUNK_SIZE),
                        new FileVaultProperties.Security(false, "X-API-Key", List.of(), List.of()),
                        new FileVaultProperties.Encryption(false, null));
        storage = new StorageService(properties, new EncryptionService(properties));
        storage.initialise();
        chunking = new ChunkingService(properties, storage);
        fileId = UUID.randomUUID().toString();
    }

    @Test
    void splitsOnTheChunkBoundary() throws Exception {
        byte[] data = new byte[CHUNK_SIZE * 2 + 100];
        new Random(7).nextBytes(data);

        List<ChunkDescriptor> chunks;
        try (ChunkWriter writer = chunking.newWriter(fileId)) {
            writer.write(data);
            writer.close();
            chunks = writer.chunks();
            assertThat(writer.totalBytes()).isEqualTo(data.length);
        }

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).storedSize()).isEqualTo(CHUNK_SIZE);
        assertThat(chunks.get(1).storedSize()).isEqualTo(CHUNK_SIZE);
        assertThat(chunks.get(2).storedSize()).isEqualTo(100);
        assertThat(chunks).extracting(ChunkDescriptor::index).containsExactly(0, 1, 2);
    }

    @Test
    void producesExactlyOneChunkWhenTheDataFitsTheBoundaryExactly() throws Exception {
        byte[] data = new byte[CHUNK_SIZE];

        try (ChunkWriter writer = chunking.newWriter(fileId)) {
            writer.write(data);
            writer.close();
            assertThat(writer.chunks()).hasSize(1);
        }
    }

    @Test
    void reassemblesToTheOriginalBytes() throws Exception {
        byte[] data = new byte[CHUNK_SIZE * 3 + 511];
        new Random(11).nextBytes(data);

        List<ChunkDescriptor> chunks;
        try (ChunkWriter writer = chunking.newWriter(fileId)) {
            // Write in awkward slices to exercise the buffer-straddling path.
            writer.write(data, 0, 7);
            writer.write(data, 7, 1500);
            writer.write(data, 1507, data.length - 1507);
            writer.close();
            chunks = writer.chunks();
        }

        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        for (ChunkDescriptor descriptor : chunks) {
            reassembled.writeBytes(
                    storage.readChunk(
                            fileId,
                            new com.filevault.model.Chunk(
                                    fileId,
                                    descriptor.index(),
                                    descriptor.hash(),
                                    descriptor.storedSize())));
        }

        assertThat(reassembled.toByteArray()).isEqualTo(data);
    }

    @Test
    void writesSingleBytesCorrectly() throws Exception {
        try (ChunkWriter writer = chunking.newWriter(fileId)) {
            for (int i = 0; i < CHUNK_SIZE + 5; i++) {
                writer.write(i % 256);
            }
            writer.close();
            assertThat(writer.chunks()).hasSize(2);
            assertThat(writer.totalBytes()).isEqualTo(CHUNK_SIZE + 5);
        }
    }

    @Test
    void reportsChunkCountForASize() {
        assertThat(chunking.chunkCountFor(0)).isZero();
        assertThat(chunking.chunkCountFor(1)).isEqualTo(1);
        assertThat(chunking.chunkCountFor(CHUNK_SIZE)).isEqualTo(1);
        assertThat(chunking.chunkCountFor(CHUNK_SIZE + 1)).isEqualTo(2);
    }
}
