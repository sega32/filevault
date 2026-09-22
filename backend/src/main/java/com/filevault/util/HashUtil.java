package com.filevault.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers used for chunk and whole-file integrity verification. */
public final class HashUtil {

    public static final String ALGORITHM = "SHA-256";

    private HashUtil() {}

    /**
     * @return a fresh SHA-256 {@link MessageDigest}
     * @throws IllegalStateException if the JVM lacks SHA-256, which no supported JVM does
     */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required but unavailable", e);
        }
    }

    /** Hex-encodes a digest value, lowercase. */
    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    /** SHA-256 of a byte array, hex-encoded. */
    public static String sha256Hex(byte[] data) {
        return toHex(newDigest().digest(data));
    }

    /**
     * Compares two hex digests without leaking timing information.
     *
     * @return true when both are non-null and equal
     */
    public static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHex.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actualHex.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
