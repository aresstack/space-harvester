package com.aresstack.enterpriseai.proasteion.platform.network;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Fortsetzen abgerissener Downloads (Angelo 26.11.2026, WLAN); erfundene Inhalte. */
public class ResumableDownloadTest {

    private static final byte[] CONTENT = "Erfundener Inhalt einer Datei, der unterwegs abreißt."
            .getBytes(StandardCharsets.UTF_8);

    /** Liefert {@code bytes} bis {@code breakAt}, dann einen Abriss. */
    private static InputStream breaking(final byte[] bytes, final int from, final int breakAt) {
        return new InputStream() {
            private int position = from;

            @Override
            public int read() throws IOException {
                if (position >= breakAt) {
                    throw new IOException("erfunden: Verbindung abgerissen");
                }
                return position < bytes.length ? bytes[position++] & 0xff : -1;
            }
        };
    }

    private static final class Buffer implements ResumableDownload.Sink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int restarts;

        @Override
        public void write(byte[] buffer, int offset, int length) {
            out.write(buffer, offset, length);
        }

        @Override
        public void restart() {
            restarts++;
            out.reset();
        }
    }

    @Test
    public void brokenStreamContinuesFromTheLastByte() throws IOException {
        final List<String> requests = new ArrayList<String>();
        Buffer sink = new Buffer();
        long length = ResumableDownload.copy(
                new ResumableDownload.Response(200, null, "\"v1\"", breaking(CONTENT, 0, 10)),
                new ResumableDownload.Source() {
                    @Override
                    public ResumableDownload.Response open(long from, String validator) {
                        requests.add(from + " " + validator);
                        return new ResumableDownload.Response(206, "bytes " + from + "-" + (CONTENT.length - 1) + "/"
                                + CONTENT.length, validator, new ByteArrayInputStream(CONTENT, (int) from,
                                CONTENT.length - (int) from));
                    }
                }, sink, Long.MAX_VALUE, 2);

        assertEquals(CONTENT.length, length);
        assertArrayEquals(CONTENT, sink.out.toByteArray());
        assertEquals(1, requests.size());
        assertEquals("10 \"v1\"", requests.get(0));
        assertEquals(0, sink.restarts);
    }

    @Test
    public void aNewVersionStartsOverInsteadOfMixing() throws IOException {
        final byte[] changed = "Neue Fassung".getBytes(StandardCharsets.UTF_8);
        Buffer sink = new Buffer();
        ResumableDownload.copy(new ResumableDownload.Response(200, null, "\"v1\"", breaking(CONTENT, 0, 10)),
                new ResumableDownload.Source() {
                    @Override
                    public ResumableDownload.Response open(long from, String validator) {
                        return new ResumableDownload.Response(200, null, "\"v2\"", new ByteArrayInputStream(changed));
                    }
                }, sink, Long.MAX_VALUE, 1);

        assertArrayEquals(changed, sink.out.toByteArray());
        assertEquals(1, sink.restarts);
    }

    @Test
    public void afterTheLastResumeTheBreakIsReported() {
        Buffer sink = new Buffer();
        try {
            ResumableDownload.copy(new ResumableDownload.Response(200, null, null, breaking(CONTENT, 0, 5)),
                    new ResumableDownload.Source() {
                        @Override
                        public ResumableDownload.Response open(long from, String validator) {
                            return new ResumableDownload.Response(206, "bytes " + from + "-", null,
                                    breaking(CONTENT, (int) from, (int) from + 5));
                        }
                    }, sink, Long.MAX_VALUE, 2);
            fail("Abriss erwartet");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("abgerissen"));
        }
        // Zwei Fortsetzungen zu je 5 Bytes nach den ersten 5.
        assertEquals(15, sink.out.size());
    }

    @Test
    public void aWrongRangeIsRefused() {
        try {
            ResumableDownload.copy(new ResumableDownload.Response(200, null, null, breaking(CONTENT, 0, 5)),
                    new ResumableDownload.Source() {
                        @Override
                        public ResumableDownload.Response open(long from, String validator) {
                            return new ResumableDownload.Response(206, "bytes 0-9/10", null,
                                    new ByteArrayInputStream(CONTENT));
                        }
                    }, new Buffer(), Long.MAX_VALUE, 1);
            fail("Ablehnung erwartet");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("abgelehnt"));
        }
    }

    @Test
    public void tooLargeIsNeverResumed() {
        try {
            ResumableDownload.copy(new ResumableDownload.Response(200, null, null, new ByteArrayInputStream(CONTENT)),
                    new ResumableDownload.Source() {
                        @Override
                        public ResumableDownload.Response open(long from, String validator) {
                            throw new AssertionError("kein Fortsetzen");
                        }
                    }, new Buffer(), 10, 3);
            fail("zu groß erwartet");
        } catch (ResumableDownload.TooLargeException expected) {
            // erwartet
        } catch (IOException other) {
            fail("falscher Fehler " + other);
        }
    }

    @Test
    public void rangeStartIsParsed() {
        assertEquals(10, ResumableDownload.rangeStart("bytes 10-19/20"));
        assertEquals(-1, ResumableDownload.rangeStart(null));
        assertEquals(-1, ResumableDownload.rangeStart("items 1-2"));
    }
}
