package com.filevault.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

/** Verifies that API-key authentication actually gates the API. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeySecurityTest {

    private static final String VALID_KEY = "test-key-0123456789abcdef";

    @TempDir static Path workDir;

    @Autowired MockMvc mockMvc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("app.storage.path", () -> workDir.resolve("storage").toString());
        registry.add("app.security.enabled", () -> true);
        registry.add("app.security.api-keys[0]", () -> VALID_KEY);
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:" + workDir.resolve("secure.db") + "?busy_timeout=5000");
    }

    @Test
    void rejectsRequestsWithoutAKey() throws Exception {
        mockMvc.perform(get("/files")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestsWithAWrongKey() throws Exception {
        mockMvc.perform(get("/files").header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsRequestsWithTheConfiguredKey() throws Exception {
        mockMvc.perform(get("/files").header("X-API-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void protectsUploadsToo() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "secret.txt",
                        MediaType.TEXT_PLAIN_VALUE,
                        "classified".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/files/upload").file(file)).andExpect(status().isUnauthorized());
    }

    @Test
    void leavesTheHealthProbeOpen() throws Exception {
        mockMvc.perform(get("/files/health")).andExpect(status().isOk());
    }
}
