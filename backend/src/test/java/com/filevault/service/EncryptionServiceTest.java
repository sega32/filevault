package com.filevault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.filevault.config.FileVaultProperties;
import com.filevault.config.FileVaultProperties.Encryption;
import com.filevault.exception.FileVaultExceptions.StorageException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class EncryptionServiceTest {

    private static final String KEY = generateKey();

    private static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static EncryptionService service(boolean enabled, String key) {
        return new EncryptionService(
                new FileVaultProperties(
                        new FileVaultProperties.Storage("./storage"),
                        new FileVaultProperties.Chunk(4096),
                        new FileVaultProperties.Security(false, "X-API-Key", List.of(), List.of()),
                        new Encryption(enabled, key)));
    }

    @Test
    void passesBytesThroughWhenDisabled() {
        EncryptionService service = service(false, null);
        byte[] data = "plaintext".getBytes(StandardCharsets.UTF_8);

        assertThat(service.encrypt(data)).isSameAs(data);
        assertThat(service.decrypt(data)).isSameAs(data);
    }

    @Test
    void roundTripsWhenEnabled() {
        EncryptionService service = service(true, KEY);
        byte[] data = "sensitive chunk contents".repeat(100).getBytes(StandardCharsets.UTF_8);

        byte[] sealed = service.encrypt(data);

        assertThat(sealed).isNotEqualTo(data);
        assertThat(service.decrypt(sealed)).isEqualTo(data);
    }

    @Test
    void producesADifferentCiphertextEachTime() {
        EncryptionService service = service(true, KEY);
        byte[] data = "same input".getBytes(StandardCharsets.UTF_8);

        assertThat(service.encrypt(data)).isNotEqualTo(service.encrypt(data));
    }

    @Test
    void rejectsTamperedCiphertext() {
        EncryptionService service = service(true, KEY);
        byte[] sealed = service.encrypt("important".getBytes(StandardCharsets.UTF_8));
        sealed[sealed.length - 1] ^= 0x01;

        assertThatThrownBy(() -> service.decrypt(sealed))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("decrypt");
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> service(true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.encryption.key");
    }

    @Test
    void refusesAKeyOfTheWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> service(true, shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
