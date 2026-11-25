package com.aresstack.spaceharvester.deck.ftp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Capture a bounded prefix while counting and hashing the completed binary FTP transfer. */
public final class BinarySample {
    public static final int MAX_CAPTURE_BYTES = 8 * 1024 * 1024;
    private final byte[] prefix;
    private final long totalBytes;
    private final String sha256;

    private BinarySample(byte[] prefix, long totalBytes, String sha256) {
        this.prefix = prefix;
        this.totalBytes = totalBytes;
        this.sha256 = sha256;
    }

    public static BinarySample capture(InputStream input, int limit) throws IOException {
        if (limit < 1 || limit > MAX_CAPTURE_BYTES) {
            throw new IllegalArgumentException("Ungültiges Diagnoselimit.");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(Math.min(limit, 65536));
        byte[] buffer = new byte[65536];
        long count = 0;
        for (int n; (n = input.read(buffer)) != -1; ) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Abgebrochen.");
            digest.update(buffer, 0, n);
            int copy = Math.min(n, limit - prefix.size());
            if (copy > 0) prefix.write(buffer, 0, copy);
            count = Math.addExact(count, n);
        }
        return new BinarySample(prefix.toByteArray(), count, HexFormat.of().formatHex(digest.digest()));
    }

    public long totalBytes() { return totalBytes; }
    public int capturedBytes() { return prefix.length; }
    public String sha256() { return sha256; }

    public byte[] slice(long offset, int length) {
        if (offset < 0 || offset > prefix.length || length < 0) {
            throw new IllegalArgumentException("Bytebereich außerhalb der erfassten Daten.");
        }
        int start = (int) offset;
        int end = start + Math.min(length, prefix.length - start);
        return Arrays.copyOfRange(prefix, start, end);
    }
}
