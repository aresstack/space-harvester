package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.sources.AttachmentSink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Ein Anhang auf dem Weg in die Ablage ({@link AssetStore#stagingFile()}): schreibt die Bytes der Quelle in die Datei
 * und rechnet dabei Hash, Größe und den Anfang für die Typerkennung mit, ohne die Datei im Speicher zu halten. Ein
 * lokaler Schreibfehler (etwa eine volle Platte) bleibt als {@link #failure()} erkennbar, damit er nicht als Störung
 * der Quelle gilt.
 */
final class StagedAttachment extends AttachmentSink {

    /** So viel vom Anfang reicht für die Signatur, wie {@code TikaDocumentText} bei großen Dateien. */
    static final int HEAD_BYTES = 64 * 1024;

    private final Path file;
    private final long maxBytes;
    private final FileChannel channel;
    private final MessageDigest digest;
    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private long size;
    private boolean exceeded;
    private IOException failure;
    private String sha256;
    private boolean closed;

    StagedAttachment(Path file, long maxBytes) throws IOException {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes muss positiv sein");
        }
        this.file = Objects.requireNonNull(file, "file");
        this.maxBytes = maxBytes;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        this.channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (closed) {
            throw new IOException("Anhang schon abgeschlossen");
        }
        if (length == 0) {
            return;
        }
        if (size + length > maxBytes) {
            exceeded = true;
            throw new IOException("Anhang größer als " + maxBytes + " Bytes");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            failure = e;
            throw e;
        }
        digest.update(bytes, offset, length);
        int missing = HEAD_BYTES - head.size();
        if (missing > 0) {
            head.write(bytes, offset, Math.min(length, missing));
        }
        size += length;
    }

    @Override
    public synchronized void restart() throws IOException {
        if (closed) {
            throw new IOException("Anhang schon abgeschlossen");
        }
        try {
            channel.truncate(0);
            channel.position(0);
        } catch (IOException e) {
            failure = e;
            throw e;
        }
        digest.reset();
        head.reset();
        size = 0;
        exceeded = false;
        failure = null;
        sha256 = null;
    }

    @Override
    public long maxBytes() {
        return maxBytes;
    }

    @Override
    public synchronized boolean exceeded() {
        return exceeded;
    }

    /** Der lokale Schreibfehler des letzten Versuchs oder {@code null}. */
    synchronized IOException failure() {
        return failure;
    }

    Path file() {
        return file;
    }

    synchronized long size() {
        return size;
    }

    /** Der Anfang der Datei, höchstens {@link #HEAD_BYTES}. */
    synchronized byte[] head() {
        return head.toByteArray();
    }

    /** {@code sha256:<hex>} über alles Geschriebene; erst nach {@link #close()}. */
    synchronized String sha256() {
        if (!closed) {
            throw new IllegalStateException("Anhang noch offen");
        }
        if (sha256 == null) {
            sha256 = "sha256:" + HexFormat.of().formatHex(digest.digest());
        }
        return sha256;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.close();
        }
    }
}
