package com.filevault.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed configuration for FileVault, bound from the {@code app.*} property namespace.
 *
 * @param storage    where chunks live on disk
 * @param chunk      chunking behaviour
 * @param security   API authentication settings
 * @param encryption at-rest encryption settings
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record FileVaultProperties(
        @DefaultValue Storage storage,
        @DefaultValue Chunk chunk,
        @DefaultValue Security security,
        @DefaultValue Encryption encryption) {

    /**
     * @param path root directory holding per-file chunk directories
     */
    public record Storage(@DefaultValue("./storage") @NotBlank String path) {}

    /**
     * @param size chunk size in bytes; the compressed byte stream is split on this boundary
     */
    public record Chunk(@DefaultValue("4194304") @Min(4096) int size) {}

    /**
     * API-key authentication. Enabled by default: the application refuses to start with
     * authentication on and no keys configured, so an unprotected server is never the default.
     *
     * @param enabled    whether the API-key filter is active
     * @param header     header carrying the key
     * @param apiKeys    accepted keys
     * @param corsOrigins allowed CORS origins ({@code *} is rejected when auth is enabled)
     */
    public record Security(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("X-API-Key") @NotBlank String header,
            @DefaultValue List<String> apiKeys,
            @DefaultValue List<String> corsOrigins) {

        public Security {
            apiKeys = List.copyOf(apiKeys == null ? new ArrayList<>() : apiKeys);
            corsOrigins = List.copyOf(corsOrigins == null ? new ArrayList<>() : corsOrigins);
        }
    }

    /**
     * AES-256-GCM encryption of chunks at rest.
     *
     * @param enabled whether chunks are encrypted before hitting disk
     * @param key     base64-encoded 32-byte key; required when {@code enabled}
     */
    public record Encryption(@DefaultValue("false") boolean enabled, String key) {}
}
