package com.aresstack.enterpriseai.proasteion.platform.network;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Liest eine Kopfzeile der Antwort, {@code null} wenn sie fehlt; so bleibt der Baustein frei von HTTP-Clients. */
    public interface Headers {

        String header(String name);
    }

    /**
     * Die Antwort für {@link #copy} aus Status und Kopfzeilen: Validator {@code ETag}, sonst {@code Last-Modified};
     * die Länge aus {@code Content-Length} bei 200. Die eine Stelle, an der jeder Adapter seine Antwort übersetzt.
     */
    public static Response response(int status, Headers headers, InputStream body) {
        String validator = headers.header("ETag");
        if (validator == null) {
            validator = headers.header("Last-Modified");
        }
        long length = -1;
        String contentLength = status == 200 ? headers.header("Content-Length") : null;
        if (contentLength != null) {
            try {
                length = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                length = -1;
            }
        }
        return new Response(status, headers.header("Content-Range"), validator, body, length);
    }

    /** Die Kopfzeilen einer Folgeanfrage ab Byte {@code from}: {@code Range} und, mit Validator, {@code If-Range}. */
    public static Map<String, String> rangeHeaders(long from, String validator) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Range", "bytes=" + from + "-");
        if (validator != null) {
            headers.put("If-Range", validator);
        }
        return headers;
    }

    /** Ein Puffer im Speicher; ein Neubeginn leert ihn. */
    public static Sink sink(final ByteArrayOutputStream out) {
        return new Sink() {
            @Override
            public void write(byte[] buffer, int offset, int length) {
                out.write(buffer, offset, length);
            }

            @Override
            public void restart() {
                out.reset();
            }
        };
    }

    /** Eine Datei; ein Neubeginn kürzt sie auf null. Ein unterbrochener Thread bricht ab. */
    public static Sink sink(final FileChannel out) {
        return new Sink() {
            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Download unterbrochen");
                }
                ByteBuffer bytes = ByteBuffer.wrap(buffer, offset, length);
                while (bytes.hasRemaining()) {
                    out.write(bytes);
                }
            }

            @Override
            public void restart() throws IOException {
                out.truncate(0);
                out.position(0);
            }
        };
    }

    /**
     * Ein Datenstrom des Aufrufers, der sich nicht zurücksetzen lässt: Fortsetzen mit 206 geht, ein Neubeginn nur,
     * solange nichts geschrieben ist. Hat sich der Inhalt nach den ersten Bytes geändert, scheitert der Abruf, statt
     * zwei Fassungen zu mischen; der Aufrufer fordert ihn dann als Ganzes neu an.
     */
    public static Sink sink(final OutputStream out) {
        return new Sink() {
            private long written;

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                out.write(buffer, offset, length);
                written += length;
            }

            @Override
            public void restart() throws IOException {
                if (written > 0) {
                    throw new IOException("Inhalt hat sich während des Abrufs geändert, nach " + written
                            + " Bytes; Fortsetzen nicht möglich");
                }
            }
        };
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
