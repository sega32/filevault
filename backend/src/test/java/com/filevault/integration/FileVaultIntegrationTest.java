package com.filevault.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filevault.exception.FileVaultExceptions.IntegrityException;
import com.filevault.model.FileRecord;
import com.filevault.repository.ChunkRepository;
import com.filevault.repository.FileRepository;
import com.filevault.service.FileService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests over the real stack: HTTP, service layer, SQLite and the filesystem.
 *
 * <p>The chunk size is deliberately tiny so that ordinary test payloads span many chunks — the
 * multi-chunk path is where reassembly bugs live.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileVaultIntegrationTest {

    private static final int TEST_CHUNK_SIZE = 8192;

    @TempDir static Path workDir;

    @Autowired MockMvc mockMvc;
    @Autowired FileService fileService;
    @Autowired FileRepository fileRepository;
    @Autowired ChunkRepository chunkRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("app.storage.path", () -> workDir.resolve("storage").toString());
        registry.add("app.chunk.size", () -> TEST_CHUNK_SIZE);
        registry.add("app.security.enabled", () -> false);
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:" + workDir.resolve("test.db") + "?busy_timeout=5000");
    }

    @BeforeEach
    void clean() {
        fileRepository.findAll().forEach(record -> fileService.delete(record.getFileId()));
    }

    @Test
    void storesAndReturnsAFileByteForByte() throws Exception {
        byte[] original = "Important content. ".repeat(3000).getBytes(StandardCharsets.UTF_8);

        String fileId = upload("report.txt", MediaType.TEXT_PLAIN_VALUE, original);
        byte[] downloaded = download(fileId);

        assertThat(downloaded).isEqualTo(original);
    }

    @Test
    void roundTripsIncompressibleBinaryAcrossManyChunks() throws Exception {
        byte[] noise = new byte[TEST_CHUNK_SIZE * 5 + 977];
        new Random(1234).nextBytes(noise);

        String fileId = upload("noise.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, noise);

        assertThat(download(fileId)).isEqualTo(noise);
        assertThat(chunkRepository.countByIdFileId(fileId)).isGreaterThan(1);
    }

    @Test
    void reportsMetadataAndCompressionForACompressiblePayload() throws Exception {
        byte[] original = "aaaaaaaaaaaaaaaa".repeat(4000).getBytes(StandardCharsets.UTF_8);

        String fileId = upload("repeats.txt", MediaType.TEXT_PLAIN_VALUE, original);

        mockMvc.perform(get("/files/" + fileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("repeats.txt"))
                .andExpect(jsonPath("$.originalSize").value(original.length))
                .andExpect(jsonPath("$.compressionRatio").value(org.hamcrest.Matchers.greaterThan(90.0)))
                .andExpect(jsonPath("$.sha256").isNotEmpty());
    }

    @Test
    void writesTheExpectedNumberOfChunksToDisk() throws Exception {
        byte[] noise = new byte[TEST_CHUNK_SIZE * 3];
        new Random(99).nextBytes(noise);

        String fileId = upload("noise.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, noise);

        FileRecord record = fileRepository.findById(fileId).orElseThrow();
        Path chunkDir = workDir.resolve("storage").resolve(fileId);
        try (var files = Files.list(chunkDir)) {
            assertThat(files.count()).isEqualTo(record.getChunkCount());
        }
        assertThat(chunkRepository.countByIdFileId(fileId)).isEqualTo(record.getChunkCount());
    }

    @Test
    void listsFilesNewestFirst() throws Exception {
        upload("first.txt", MediaType.TEXT_PLAIN_VALUE, "one".repeat(100).getBytes(StandardCharsets.UTF_8));
        Thread.sleep(10);
        upload("second.txt", MediaType.TEXT_PLAIN_VALUE, "two".repeat(100).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].filename").value("second.txt"));
    }

    @Test
    void deleteRemovesMetadataAndChunks() throws Exception {
        String fileId = upload("temp.txt", MediaType.TEXT_PLAIN_VALUE, "delete me".repeat(500).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(delete("/files/" + fileId)).andExpect(status().isOk());

        mockMvc.perform(get("/files/" + fileId)).andExpect(status().isNotFound());
        assertThat(chunkRepository.countByIdFileId(fileId)).isZero();
        assertThat(workDir.resolve("storage").resolve(fileId)).doesNotExist();
    }

    @Test
    void returnsNotFoundForAnUnknownFile() throws Exception {
        mockMvc.perform(get("/files/" + java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("File not found"));
    }

    @Test
    void rejectsAMalformedFileId() throws Exception {
        mockMvc.perform(get("/files/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void rejectsAnEmptyUpload() throws Exception {
        mockMvc.perform(multipart("/files/upload").file(new MockMultipartFile("file", "empty.txt", MediaType.TEXT_PLAIN_VALUE, new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void strippesDirectoryComponentsFromTheUploadedFilename() throws Exception {
        byte[] content = "path traversal attempt".repeat(100).getBytes(StandardCharsets.UTF_8);

        String fileId = upload("../../etc/passwd", MediaType.TEXT_PLAIN_VALUE, content);

        assertThat(fileRepository.findById(fileId).orElseThrow().getFilename()).isEqualTo("passwd");
    }

    @Test
    void detectsCorruptionInsteadOfServingDamagedData() throws Exception {
        byte[] original = "verify me".repeat(2000).getBytes(StandardCharsets.UTF_8);
        String fileId = upload("verify.txt", MediaType.TEXT_PLAIN_VALUE, original);

        Path firstChunk = workDir.resolve("storage").resolve(fileId).resolve("chunk_00000.bin");
        byte[] onDisk = Files.readAllBytes(firstChunk);
        onDisk[10] ^= 0x7F;
        Files.write(firstChunk, onDisk);

        assertThatThrownBy(() -> fileService.writeContentTo(fileId, new ByteArrayOutputStream()))
                .isInstanceOf(IntegrityException.class);
    }

    @Test
    void detectsCorruptionInATrailingChunkToo() throws Exception {
        byte[] noise = new byte[TEST_CHUNK_SIZE * 4];
        new Random(4242).nextBytes(noise);
        String fileId = upload("noise.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, noise);

        FileRecord record = fileRepository.findById(fileId).orElseThrow();
        Path lastChunk =
                workDir.resolve("storage")
                        .resolve(fileId)
                        .resolve(String.format("chunk_%05d.bin", record.getChunkCount() - 1));
        byte[] onDisk = Files.readAllBytes(lastChunk);
        onDisk[onDisk.length - 1] ^= 0x55;
        Files.write(lastChunk, onDisk);

        // Earlier chunks verify fine, so this aborts partway: the caller must still see the error
        // rather than a silently short file.
        assertThatThrownBy(() -> fileService.writeContentTo(fileId, new ByteArrayOutputStream()))
                .isInstanceOf(IntegrityException.class);
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/files/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void statsSummariseTheVault() throws Exception {
        byte[] content = "statistics".repeat(1000).getBytes(StandardCharsets.UTF_8);
        upload("stats.txt", MediaType.TEXT_PLAIN_VALUE, content);

        mockMvc.perform(get("/files/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files").value(1))
                .andExpect(jsonPath("$.originalBytes").value(content.length))
                .andExpect(jsonPath("$.chunkSizeBytes").value(TEST_CHUNK_SIZE));
    }

    // ---------------------------------------------------------------- helpers

    private String upload(String filename, String contentType, byte[] content) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                multipart("/files/upload")
                                        .file(new MockMultipartFile("file", filename, contentType, content)))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("fileId").asText();
    }

    private byte[] download(String fileId) throws Exception {
        MvcResult started =
                mockMvc.perform(get("/files/" + fileId + "/download"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }
}
