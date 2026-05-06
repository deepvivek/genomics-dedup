package com.genomics.dedup.hash;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streams files in fixed-size chunks and computes SHA-256.
 * Never loads more than bufferBytes into RAM — safe for 100 GB+ files.
 * Thread-safe: each call creates its own MessageDigest instance.
 */
public class FileHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final int bufferBytes;

    public FileHasher(int bufferBytes) {
        if (bufferBytes < 4096) throw new IllegalArgumentException("bufferBytes must be >= 4096");
        this.bufferBytes = bufferBytes;
    }

    /**
     * Computes SHA-256 of entire file by streaming in chunks.
     *
     * @param path file to hash
     * @return lowercase hex SHA-256 string (64 chars)
     * @throws IOException if the file cannot be read
     */
    public String sha256(Path path) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream raw = Files.newInputStream(path);
             BufferedInputStream bis = new BufferedInputStream(raw, bufferBytes)) {
            byte[] buf = new byte[bufferBytes];
            int read;
            while ((read = bis.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    public static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2]     = HEX[v >>> 4];
            hex[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(hex);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available — JVM is broken", e);
        }
    }
}
