package com.llm.app.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class SecretKeyDerivation {

    private SecretKeyDerivation() {
    }

    /**
     * Derives a fixed 32-byte (256-bit) key from a secret string: secrets shorter than 32 bytes
     * are stretched with SHA-256, longer secrets are truncated to their first 32 bytes.
     *
     * <p>Transport-stable: this exact derivation backs both the JWT signing key and the
     * upload-session AES key. The AES key must stay byte-compatible with the external upload
     * script, so do NOT "harden" the {@code Math.min(..., 32)} truncation or the &lt;32 branch
     * here — changing them invalidates all existing tokens and breaks the wire format.
     */
    public static byte[] derive32Bytes(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            try {
                bytes = MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 not available", exception);
            }
        }
        byte[] keyBytes = new byte[32];
        System.arraycopy(bytes, 0, keyBytes, 0, Math.min(bytes.length, 32));
        return keyBytes;
    }
}
