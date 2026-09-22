package com.filevault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.filevault.config.FileVaultProperties;
import com.filevault.exception.FileVaultExceptions.IntegrityException;
import com.filevault.exception.FileVaultExceptions.InvalidRequestException;
import com.filevault.model.Chunk;
import com.filevault.service.StorageService.StoredChunk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {

    @TempDir Path storageRoot;

    private StorageService storage;
    private String fileId;

    @BeforeEach
    void setUp() {
        FileVaultProperties properties =
                new FileVaultProperties(
                        new FileVaultProperties.Storage(storageRoot.toString()),
                        new FileVaultProperties.Chunk(4096),
                        new FileVaultProperties.Security(false, "X-API-Key", List.of(), List.of()),
                        new FileVaultProperties.Encryption(false, null));
        storage = new StorageService(properties, new EncryptionService(properties));
        storage.initialise();
        fileId = UUID.randomUUID().toString();
    }

    @Test
    void writesAndReadsAChunk() {
        byte[] payload = "chunk contents".getBytes(StandardCharsets.UTF_8);

        StoredChunk stored = storage.writeChunk(fileId, 0, payload);
        byte[] read = storage.readChunk(fileId, chunk(0, stored));

        assertThat(read).isEqualTo(payload);
        assertThat(stored.storedSize()).isEqualTo(payload.length);
    }

    @Test
    void namesChunkFilesPredictably() {
        storage.writeChunk(fileId, 7, "x".getBytes(StandardCharsets.UTF_8));

        assertThat(storageRoot.resolve(fileId).resolve("chunk_00007.bin")).exists();
    }

    @Test
    void detectsACorruptedChunk() throws IOException {
        byte[] payload = "trust but verify".getBytes(StandardCharsets.UTF_8);
        StoredChunk stored = storage.writeChunk(fileId, 0, payload);

        // Flip a byte on disk, keeping the length identical so only the hash can catch it.
        Path chunkFile = storageRoot.resolve(fileId).resolve("chunk_00000.bin");
        byte[] onDisk = Files.readAllBytes(chunkFile);
        onDisk[0] ^= 0x01;
        Files.write(chunkFile, onDisk);

        assertThatThrownBy(() -> storage.readChunk(fileId, chunk(0, stored)))
                .isInstanceOf(IntegrityException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void detectsAResizedChunk() throws IOException {
        StoredChunk stored =
                storage.writeChunk(fileId, 0, "original".getBytes(StandardCharsets.UTF_8));
        Files.write(
                storageRoot.resolve(fileId).resolve("chunk_00000.bin"),
                "much longer than before".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.readChunk(fileId, chunk(0, stored)))
                .isInstanceOf(IntegrityException.class)
                .hasMessageContaining("size");
    }

    @Test
    void detectsAMissingChunk() {
        StoredChunk stored = storage.writeChunk(fileId, 0, "gone".getBytes(StandardCharsets.UTF_8));
        storage.deleteFile(fileId);

        assertThatThrownBy(() -> storage.readChunk(fileId, chunk(0, stored)))
                .isInstanceOf(IntegrityException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void deleteRemovesEveryChunkAndIsIdempotent() {
        storage.writeChunk(fileId, 0, "a".getBytes(StandardCharsets.UTF_8));
        storage.writeChunk(fileId, 1, "b".getBytes(StandardCharsets.UTF_8));

        storage.deleteFile(fileId);
        storage.deleteFile(fileId); // deleting a file that is already gone must not throw

        assertThat(storageRoot.resolve(fileId)).doesNotExist();
    }

    @Test
    void rejectsFileIdsThatAreNotUuids() {
        assertThatThrownBy(() -> storage.readChunk("../../etc", chunk(0, new StoredChunk("x", 1))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void rejectsNegativeChunkIndexes() {
        assertThatThrownBy(() -> storage.writeChunk(fileId, -1, new byte[] {1}))
                .isInstanceOf(InvalidRequestException.class);
    }

    private Chunk chunk(int index, StoredChunk stored) {
        return new Chunk(fileId, index, stored.hash(), stored.storedSize());
    }
}
