package com.filevault.service;

import com.filevault.config.FileVaultProperties;
import com.filevault.exception.FileVaultExceptions.StorageException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Optional AES-256-GCM encryption of chunks at rest.
 *
 * <p>Each chunk is sealed independently with a fresh 12-byte IV, stored as {@code IV || ciphertext
 * || tag}. GCM authenticates as well as encrypts, so a tampered chunk fails to decrypt rather than
 * yielding garbage. Disabled by default; enabling it without a key is a startup failure, never a
 * silent fallback to plaintext.
 */
@Service
public class EncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final boolean enabled;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(FileVaultProperties properties) {
        this.enabled = properties.encryption().enabled();
        this.key = enabled ? loadKey(properties.encryption().key()) : null;
    }

    private static SecretKey loadKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "app.encryption.enabled=true requires app.encryption.key "
                            + "(base64-encoded 32 bytes; generate with: "
                            + "openssl rand -base64 32)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.encryption.key is not valid base64", e);
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "app.encryption.key must decode to "
                            + KEY_LENGTH_BYTES
                            + " bytes (AES-256), got "
                            + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Seals a chunk for storage.
     *
     * @return {@code IV || ciphertext || tag}, or the input unchanged when encryption is off
     */
    public byte[] encrypt(byte[] plaintext) {
        if (!enabled) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);

            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return out;
        } catch (Exception e) {
            throw new StorageException("Failed to encrypt chunk", e);
        }
    }

    /**
     * Opens a chunk read from storage.
     *
     * @return the plaintext bytes, or the input unchanged when encryption is off
     * @throws StorageException if the chunk cannot be authenticated or decrypted
     */
    public byte[] decrypt(byte[] stored) {
        if (!enabled) {
            return stored;
        }
        if (stored.length < IV_LENGTH) {
            throw new StorageException("Encrypted chunk is too short to contain an IV");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, stored, 0, IV_LENGTH));
            return cipher.doFinal(stored, IV_LENGTH, stored.length - IV_LENGTH);
        } catch (Exception e) {
            throw new StorageException("Failed to decrypt chunk", e);
        }
    }
}
