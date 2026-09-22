package com.filevault.service;

import com.filevault.exception.FileVaultExceptions.FileNotFoundException;
import com.filevault.exception.FileVaultExceptions.IntegrityException;
import com.filevault.exception.FileVaultExceptions.InvalidRequestException;
import com.filevault.exception.FileVaultExceptions.StorageException;
import com.filevault.model.Chunk;
import com.filevault.model.FileRecord;
import com.filevault.repository.ChunkRepository;
import com.filevault.repository.FileRepository;
import com.filevault.service.ChunkingService.ChunkDescriptor;
import com.filevault.service.ChunkingService.ChunkWriter;
import com.filevault.util.HashUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the storage pipeline: compress, chunk, hash, persist — and the reverse on the way
 * out.
 *
 * <p>Chunk I/O deliberately happens <em>outside</em> the database transaction. SQLite allows a
 * single writer, so holding a write transaction open for the duration of a multi-gigabyte upload
 * would serialise the whole server behind one client.
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final int MAX_FILENAME_LENGTH = 255;

    private final FileRepository fileRepository;
    private final ChunkRepository chunkRepository;
    private final CompressionService compression;
    private final ChunkingService chunking;
    private final StorageService storage;

    private final Counter uploadCounter;
    private final Counter downloadCounter;
    private final Counter integrityFailureCounter;
    private final Timer uploadTimer;

    public FileService(
            FileRepository fileRepository,
            ChunkRepository chunkRepository,
            CompressionService compression,
            ChunkingService chunking,
            StorageService storage,
            MeterRegistry meterRegistry) {
        this.fileRepository = fileRepository;
        this.chunkRepository = chunkRepository;
        this.compression = compression;
        this.chunking = chunking;
        this.storage = storage;
        this.uploadCounter = meterRegistry.counter("filevault.uploads");
        this.downloadCounter = meterRegistry.counter("filevault.downloads");
        this.integrityFailureCounter = meterRegistry.counter("filevault.integrity.failures");
        this.uploadTimer = meterRegistry.timer("filevault.upload.duration");
    }

    /**
     * Compresses, chunks and stores an uploaded stream.
     *
     * <p>If anything fails partway, chunks already written are removed before the error propagates,
     * so a failed upload never leaves orphaned bytes on disk.
     *
     * @param source       the uploaded bytes; closed by the caller
     * @param rawFilename  client-supplied filename, sanitised to a bare basename
     * @param contentType  client-supplied content type, may be null
     * @return the persisted metadata
     */
    public FileRecord upload(InputStream source, String rawFilename, String contentType) {
        String filename = sanitiseFilename(rawFilename);
        String fileId = UUID.randomUUID().toString();
        Timer.Sample sample = Timer.start();

        long originalSize;
        String originalSha256;
        long compressedSize;
        List<ChunkDescriptor> descriptors;

        try {
            MessageDigest digest = HashUtil.newDigest();
            try (ChunkWriter writer = chunking.newWriter(fileId)) {
                try (DigestInputStream digesting = new DigestInputStream(source, digest);
                        OutputStream gzip = compression.compressing(writer)) {
                    originalSize = digesting.transferTo(gzip);
                }
                // The gzip trailer is only written on close, so read totals after it is closed.
                writer.close();
                compressedSize = writer.totalBytes();
                descriptors = writer.chunks();
            }
            originalSha256 = HashUtil.toHex(digest.digest());
        } catch (IOException e) {
            storage.deleteFile(fileId);
            throw new StorageException("Failed to store upload " + filename, e);
        } catch (RuntimeException e) {
            storage.deleteFile(fileId);
            throw e;
        }

        if (originalSize == 0) {
            storage.deleteFile(fileId);
            throw new InvalidRequestException("Uploaded file is empty");
        }

        try {
            FileRecord record =
                    persist(
                            fileId,
                            filename,
                            contentType,
                            originalSize,
                            compressedSize,
                            originalSha256,
                            descriptors);
            sample.stop(uploadTimer);
            uploadCounter.increment();
            log.info(
                    "Stored {} ({}) as {}: {} B -> {} B in {} chunks ({}% saved)",
                    filename,
                    contentType,
                    fileId,
                    originalSize,
                    compressedSize,
                    descriptors.size(),
                    String.format("%.2f", record.compressionRatio()));
            return record;
        } catch (RuntimeException e) {
            // Metadata is the source of truth; without it the chunks are unreachable garbage.
            storage.deleteFile(fileId);
            throw e;
        }
    }

    /**
     * Saves metadata and chunk rows. {@code save} cascades to the chunks and runs in its own
     * repository-level transaction, so file and chunk rows commit together or not at all.
     */
    private FileRecord persist(
            String fileId,
            String filename,
            String contentType,
            long originalSize,
            long compressedSize,
            String originalSha256,
            List<ChunkDescriptor> descriptors) {
        FileRecord record =
                new FileRecord(
                        fileId,
                        filename,
                        contentType,
                        originalSize,
                        compressedSize,
                        originalSha256,
                        descriptors.size(),
                        Instant.now());
        for (ChunkDescriptor descriptor : descriptors) {
            record.addChunk(
                    new Chunk(
                            fileId, descriptor.index(), descriptor.hash(), descriptor.storedSize()));
        }
        return fileRepository.save(record);
    }

    /** @throws FileNotFoundException if no such file exists */
    @Transactional(readOnly = true)
    public FileRecord getMetadata(String fileId) {
        StorageService.validateFileId(fileId);
        return fileRepository.findById(fileId).orElseThrow(() -> new FileNotFoundException(fileId));
    }

    /** All stored files, newest first. */
    @Transactional(readOnly = true)
    public List<FileRecord> listAll() {
        return fileRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Reassembles a file and writes the original bytes to {@code sink}.
     *
     * <p>Every chunk is read and verified in full before any of its bytes are emitted, so a
     * corrupted chunk surfaces as an error rather than as a silently truncated download.
     *
     * @throws FileNotFoundException if no such file exists
     * @throws IntegrityException    if a chunk is missing, resized, or fails verification
     */
    public void writeContentTo(String fileId, OutputStream sink) throws IOException {
        FileRecord record = getMetadata(fileId);
        List<Chunk> chunks = loadChunks(fileId);

        if (chunks.size() != record.getChunkCount()) {
            integrityFailureCounter.increment();
            throw new IntegrityException(
                    "File %s should have %d chunks but %d are recorded"
                            .formatted(fileId, record.getChunkCount(), chunks.size()));
        }

        try (InputStream chunkStream = new SequenceInputStream(verifiedChunkStreams(fileId, chunks));
                GZIPInputStream decompressed = compression.decompressing(chunkStream)) {
            decompressed.transferTo(sink);
        } catch (IntegrityException e) {
            integrityFailureCounter.increment();
            // Once the first chunk has been written the response is already committed, so this
            // never reaches the exception handler: the download simply aborts mid-stream. Log it
            // here or a corruption event leaves no server-side trace at all.
            log.error("Integrity failure while streaming {}: {}", fileId, e.getMessage());
            throw e;
        }
        downloadCounter.increment();
    }

    private List<Chunk> loadChunks(String fileId) {
        return chunkRepository.findByIdFileIdOrderByIdChunkIndexAsc(fileId);
    }

    /**
     * Deletes a file's metadata and its chunks.
     *
     * <p>Metadata goes first: if the disk delete then fails, the file is already unreachable and
     * the leftover bytes are recoverable garbage, which is the better of the two failure modes.
     *
     * @throws FileNotFoundException if no such file exists
     */
    public void delete(String fileId) {
        FileRecord record = getMetadata(fileId);
        // deleteById loads the row inside the delete transaction, so the cascade to chunks runs
        // against a managed entity rather than a detached one with an uninitialised collection.
        fileRepository.deleteById(fileId);
        storage.deleteFile(fileId);
        log.info("Deleted {} ({})", record.getFilename(), fileId);
    }

    /** Aggregate counters for the operational endpoint. */
    @Transactional(readOnly = true)
    public VaultStats stats() {
        long files = fileRepository.count();
        long originalBytes = fileRepository.totalOriginalBytes();
        long storedBytes = fileRepository.totalStoredBytes();
        double ratio =
                originalBytes == 0 ? 0.0 : (originalBytes - storedBytes) * 100.0 / originalBytes;
        return new VaultStats(
                files,
                originalBytes,
                storedBytes,
                Math.max(ratio, 0.0),
                storage.usableSpaceBytes(),
                chunking.getChunkSize());
    }

    /**
     * Produces one verified {@link InputStream} per chunk, lazily: a chunk is read from disk only
     * when the previous one has been consumed, bounding memory at one chunk.
     */
    private Enumeration<InputStream> verifiedChunkStreams(String fileId, List<Chunk> chunks) {
        Iterator<Chunk> iterator = chunks.iterator();
        return new Enumeration<>() {
            private int expectedIndex = 0;

            @Override
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            @Override
            public InputStream nextElement() {
                if (!iterator.hasNext()) {
                    throw new NoSuchElementException();
                }
                Chunk chunk = iterator.next();
                if (chunk.getChunkIndex() != expectedIndex) {
                    throw new IntegrityException(
                            "File %s has a gap in its chunk sequence: expected index %d, found %d"
                                    .formatted(fileId, expectedIndex, chunk.getChunkIndex()));
                }
                expectedIndex++;
                return new ByteArrayInputStream(storage.readChunk(fileId, chunk));
            }
        };
    }

    /**
     * Reduces a client-supplied filename to a safe basename: no directory components, no control
     * characters, never empty.
     */
    static String sanitiseFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            throw new InvalidRequestException("Filename is required");
        }
        String name = rawFilename.trim().replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new InvalidRequestException("Filename is not usable: " + rawFilename);
        }
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(0, MAX_FILENAME_LENGTH);
        }
        return name;
    }

    /**
     * Vault-wide totals.
     *
     * @param files            number of stored files
     * @param originalBytes    total size before compression
     * @param storedBytes      total bytes occupied on disk
     * @param spaceSavedPercent percentage saved by compression
     * @param usableSpaceBytes free space on the storage volume, or -1 if unknown
     * @param chunkSizeBytes   configured chunk size
     */
    public record VaultStats(
            long files,
            long originalBytes,
            long storedBytes,
            double spaceSavedPercent,
            long usableSpaceBytes,
            int chunkSizeBytes) {}
}
