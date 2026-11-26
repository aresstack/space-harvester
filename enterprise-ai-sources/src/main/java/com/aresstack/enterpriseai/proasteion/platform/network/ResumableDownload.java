package com.aresstack.enterpriseai.proasteion.platform.network;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Setzt einen abgerissenen HTTP-Download dort fort, wo er abriss, statt ihn zu verwerfen und von vorn zu beginnen
 * (Angelo 26.11.2026, WLAN: „einzelne Pakete neu anfordern und fortsetzen“). Die eine Stelle für alle Adapter mit
 * Datei- oder Inhaltsabrufen: Sie kennt nur Status, {@code Content-Range}, einen Validator und den Datenstrom, nie
 * einen HTTP-Client, eine Route oder TLS.
 *
 * <p>Ablauf: Der Aufrufer stellt die erste Anfrage selbst und reicht eine Antwort mit Status 200 herein. Reißt der
 * Datenstrom ab (eine {@link IOException} beim Lesen), fordert {@link #copy} den Rest mit {@code Range: bytes=n-} und
 * {@code If-Range: <Validator>} an ({@link Source#open}). Antwortet die Gegenseite mit 206 ab genau Byte {@code n},
 * geht es dort weiter. Antwortet sie mit 200 (Range nicht unterstützt oder die Datei hat sich geändert), beginnt das
 * Ziel neu ({@link Sink#restart()}), damit nie zwei Fassungen gemischt werden. Jede Fortsetzung verbraucht einen der
 * {@code resumes} Versuche. Eine Größenüberschreitung oder ein unterbrochener Thread wird nie fortgesetzt.
 */
public final class ResumableDownload {

    private static final int BUFFER = 8192;

    private ResumableDownload() {
    }

    /** Eine Antwort: Status, Kopfzeilen für das Fortsetzen und der Datenstrom. */
    public static class Response implements Closeable {

        private final int status;
        private final String contentRange;
        private final String validator;
        private final InputStream body;
        private final long length;

        /** Ohne bekannte Gesamtlänge. */
        public Response(int status, String contentRange, String validator, InputStream body) {
            this(status, contentRange, validator, body, -1);
        }

        /**
         * @param status       HTTP-Status
         * @param contentRange Kopfzeile {@code Content-Range} oder {@code null}
         * @param validator    {@code ETag}, sonst {@code Last-Modified} der Antwort, oder {@code null}; ohne Validator
         *                     wird nur fortgesetzt, wenn die Gegenseite mit 206 ab der richtigen Stelle antwortet
         * @param body         der Datenstrom, {@code null} ohne Inhalt
         * @param length       Länge des ganzen Inhalts ({@code Content-Length} bei 200, die Gesamtlänge aus
         *                     {@code Content-Range} bei 206), {@code -1} wenn unbekannt. Endet der Datenstrom vorher
         *                     ohne Fehler, gilt das als Abriss: Manche Verbindungen enden still statt mit einem Fehler
         */
        public Response(int status, String contentRange, String validator, InputStream body, long length) {
            this.status = status;
            this.contentRange = contentRange;
            this.validator = validator;
            this.body = body;
            this.length = length;
        }

        public long length() {
            return length;
        }

        public int status() {
            return status;
        }

        public String contentRange() {
            return contentRange;
        }

        public String validator() {
            return validator;
        }

        public InputStream body() {
            return body;
        }

        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }
    }

    /** Stellt eine Folgeanfrage ab einem Byte. */
    public interface Source {

        /**
         * Fordert den Rest ab Byte {@code from} an: mit {@code Range: bytes=<from>-} und, wenn {@code validator} nicht
         * {@code null} ist, {@code If-Range: <validator>}.
         */
        Response open(long from, String validator) throws IOException;
    }

    /** Das Ziel der Bytes: eine Datei, ein Puffer. */
    public interface Sink {

        void write(byte[] buffer, int offset, int length) throws IOException;

        /** Verwirft alles bisher Geschriebene; danach kommt der Inhalt von Anfang an. */
        void restart() throws IOException;
    }

    /** Die Antwort ist größer als erlaubt; wird nie fortgesetzt. */
    public static final class TooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        public TooLargeException(long maxBytes) {
            super("response larger than " + maxBytes + " bytes");
        }
    }

    /**
     * Kopiert den Inhalt von {@code first} (Status 200) nach {@code sink} und setzt bei einem Abriss höchstens
     * {@code resumes} Mal fort. Schließt jede Antwort, auch {@code first}.
     *
     * @param maxBytes größte erlaubte Länge des ganzen Inhalts
     * @param resumes  wie oft ein Abriss höchstens fortgesetzt wird, 0 für nie
     * @return die Länge des vollständigen Inhalts
     * @throws TooLargeException wenn der Inhalt {@code maxBytes} überschreitet
     * @throws IOException       wenn der Datenstrom öfter abreißt, als fortgesetzt werden darf, oder eine Fortsetzung
     *                           abgelehnt wird; die erste Ursache steht dann als Ursache bzw. unterdrückt dabei
     */
    public static long copy(Response first, Source source, Sink sink, long maxBytes, int resumes) throws IOException {
        if (first.status() != 200) {
            first.close();
            throw new IllegalArgumentException("Fortsetzen nur für eine Antwort mit Status 200");
        }
        Response current = first;
        String validator = first.validator();
        long written = 0;
        long expected = first.length();
        int left = Math.max(0, resumes);
        byte[] buffer = new byte[BUFFER];
        try {
            while (true) {
                InputStream in = current.body();
                if (in == null) {
                    return written;
                }
                int n;
                try {
                    n = in.read(buffer);
                    if (n < 0 && expected >= 0 && written < expected) {
                        throw new java.io.EOFException("Datenstrom endete nach " + written + " von " + expected
                                + " Bytes");
                    }
                } catch (IOException broken) {
                    // Nur ein Abriss beim Lesen wird fortgesetzt; Fehler des Ziels (etwa Platte voll) nie.
                    if (left == 0 || Thread.currentThread().isInterrupted()) {
                        throw broken;
                    }
                    left--;
                    closeQuietly(current);
                    current = null;
                    try {
                        current = source.open(written, validator);
                    } catch (IOException again) {
                        again.addSuppressed(broken);
                        throw again;
                    }
                    if (current.status() == 206 && rangeStart(current.contentRange()) == written) {
                        long total = rangeTotal(current.contentRange());
                        expected = total >= 0 ? total : current.length();
                        continue;
                    }
                    if (current.status() == 200) {
                        // Range nicht unterstützt oder eine neue Fassung: von vorn, nie zwei Fassungen mischen.
                        sink.restart();
                        written = 0;
                        validator = current.validator();
                        expected = current.length();
                        continue;
                    }
                    throw new IOException("Fortsetzen ab Byte " + written + " abgelehnt (HTTP " + current.status()
                            + ")", broken);
                }
                if (n < 0) {
                    return written;
                }
                if (written + n > maxBytes) {
                    throw new TooLargeException(maxBytes);
                }
                sink.write(buffer, 0, n);
                written += n;
            }
        } finally {
            if (current != null) {
                closeQuietly(current);
            }
        }
    }

    /** Die Gesamtlänge aus {@code bytes <start>-<end>/<total>}, sonst {@code -1}. */
    static long rangeTotal(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Der erste Byte-Index aus {@code bytes <start>-<end>/<total>}, sonst {@code -1}. */
    static long rangeStart(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        String value = contentRange.trim();
        if (!value.regionMatches(true, 0, "bytes", 0, 5)) {
            return -1;
        }
        value = value.substring(5).trim();
        int dash = value.indexOf('-');
        if (dash <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(value.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void closeQuietly(Response response) {
        try {
            response.close();
        } catch (IOException ignored) {
            // Eine abgerissene Verbindung lässt sich oft nicht sauber schließen.
        }
    }
}
