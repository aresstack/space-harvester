package com.aresstack.spaceharvester.application.assets;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Ein Strom, der beim Lesen den SHA-256 bildet und eine Abweichung von der erwarteten Prüfsumme am Ende als
 * {@link IOException} meldet, bevor er das Ende anzeigt. So bleibt eine Datei beliebiger Größe geprüft, ohne ganz im
 * Speicher zu liegen; wer nicht bis zum Ende liest, bekommt keine Prüfung.
 */
public final class Sha256CheckedInputStream extends DigestInputStream {

    private final String sha256;
    private final String mismatch;
    private boolean checked;

    /**
     * @param in       der ungeprüfte Strom
     * @param sha256   die erwartete Prüfsumme als {@code sha256:<hex>}
     * @param mismatch die Meldung der {@link IOException} bei Abweichung
     */
    public Sha256CheckedInputStream(InputStream in, String sha256, String mismatch) {
        super(Objects.requireNonNull(in, "in"), digest());
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.mismatch = Objects.requireNonNull(mismatch, "mismatch");
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        return b < 0 ? end(b) : b;
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        int n = super.read(bytes, off, len);
        return n < 0 ? end(n) : n;
    }

    private int end(int eof) throws IOException {
        if (!checked) {
            checked = true;
            if (!sha256.equals("sha256:" + HexFormat.of().formatHex(getMessageDigest().digest()))) {
                throw new IOException(mismatch);
            }
        }
        return eof;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }
}
