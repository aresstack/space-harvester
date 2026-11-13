package com.aresstack.spaceharvester.testcorpus;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Eine Mail im MIME-Format ({@code .eml}, RFC 5322 und 2045 ff.) für den Testkorpus (ADR 0026, Abnahme 3, 4, 5, 7, 11),
 * ohne Mail-Bibliothek gebaut. Text und Anhänge gehen base64-kodiert in {@code multipart/mixed}; eine angehängte Mail
 * steht als {@code message/rfc822} unverändert darin (Mail in Mail). Ein Anhang kann aus einer Datei kommen und wird
 * dann beim Schreiben fortlaufend kodiert, sodass eine Mail mit einem Anhang über 300 MiB ohne großen Heap entsteht.
 *
 * <p>Die Bytes sind bei gleichem Aufbau gleich (feste Grenzen, Datum und Message-ID), damit Hash-Prüfungen stabil
 * bleiben. Dateinamen gehen unverändert in den Kopf (RFC 2231), auch mit Schrägstrich, Umlaut oder doppelt.
 */
public final class Mail {

    /** Medientyp einer Outlook-Nachricht als Anhang. */
    public static final String OUTLOOK = "application/vnd.ms-outlook";

    private final String subject;
    private final String body;
    private final List<Part> parts = new ArrayList<>();

    private Mail(String subject, String body) {
        this.subject = subject;
        this.body = body;
    }

    /** Eine Mail mit Betreff und Text (Klartext, UTF-8). */
    public static Mail of(String subject, String body) {
        return new Mail(subject, body);
    }

    /** Hängt eine Datei aus dem Speicher an. */
    public Mail attach(String fileName, String mediaType, byte[] content) {
        parts.add(new Part(fileName, mediaType, content.clone(), null, null));
        return this;
    }

    /** Hängt eine Datei an, die erst beim Schreiben gelesen wird (für große Anhänge). */
    public Mail attach(String fileName, String mediaType, Path content) {
        parts.add(new Part(fileName, mediaType, null, content, null));
        return this;
    }

    /** Hängt eine Mail als {@code message/rfc822} an, ohne Dateinamen wie eine weitergeleitete Mail. */
    public Mail attach(Mail forwarded) {
        return attach(null, forwarded);
    }

    /** Hängt eine Mail als {@code message/rfc822} an, mit Dateinamen wie ein gespeichertes {@code .eml}. */
    public Mail attach(String fileName, Mail forwarded) {
        if (forwarded == this) {
            throw new IllegalArgumentException("Eine Mail enthält sich nicht selbst");
        }
        parts.add(new Part(fileName, "message/rfc822", null, null, forwarded));
        return this;
    }

    /** Die Mail als Bytes; nur für Mails ohne Anhang aus einer Datei gedacht. */
    public byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Schreibt die Mail in eine Datei. */
    public Path write(Path target) {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 64 * 1024)) {
            write(out);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Schreibt die Mail; {@code out} bleibt offen. */
    public void write(OutputStream out) throws IOException {
        String boundary = "=_grenze_" + depth();
        line(out, "From: Absender Test <absender@example.org>");
        line(out, "To: Empfaenger Test <empfaenger@example.org>");
        line(out, "Subject: " + encodedWord(subject));
        line(out, "Date: Thu, 12 Nov 2026 09:30:00 +0100");
        line(out, "Message-ID: <korpus-" + Integer.toHexString(subject.hashCode()) + "-" + depth() + "@example.org>");
        line(out, "MIME-Version: 1.0");
        line(out, "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"");
        line(out, "");
        line(out, "--" + boundary);
        line(out, "Content-Type: text/plain; charset=UTF-8");
        line(out, "Content-Transfer-Encoding: base64");
        line(out, "");
        base64(out, body.getBytes(StandardCharsets.UTF_8));
        for (Part part : parts) {
            line(out, "--" + boundary);
            part.write(out);
        }
        line(out, "--" + boundary + "--");
    }

    /** 1 ohne angehängte Mail, sonst 1 + Tiefe der tiefsten; ergibt verschiedene Grenzen je Ebene. */
    private int depth() {
        int deepest = 0;
        for (Part part : parts) {
            if (part.mail != null) {
                deepest = Math.max(deepest, part.mail.depth());
            }
        }
        return deepest + 1;
    }

    private record Part(String fileName, String mediaType, byte[] bytes, Path file, Mail mail) {

        void write(OutputStream out) throws IOException {
            String name = fileName == null ? "" : "; " + parameter("name", fileName);
            line(out, "Content-Type: " + mediaType + name);
            if (mail != null) {
                line(out, "Content-Transfer-Encoding: 8bit");
                line(out, fileName == null ? "Content-Disposition: inline"
                        : "Content-Disposition: attachment; " + parameter("filename", fileName));
                line(out, "");
                mail.write(out);
                return;
            }
            line(out, "Content-Transfer-Encoding: base64");
            line(out, "Content-Disposition: attachment; " + parameter("filename", fileName));
            line(out, "");
            if (bytes != null) {
                base64(out, bytes);
            } else {
                try (InputStream in = Files.newInputStream(file)) {
                    base64(out, in);
                }
            }
        }
    }

    /** Dateiname nach RFC 2231, wenn er mehr als druckbares ASCII ohne Anführungszeichen enthält. */
    private static String parameter(String key, String value) {
        if (value.chars().allMatch(c -> c >= 0x20 && c < 0x7F && c != '"' && c != '\\')) {
            return key + "=\"" + value + "\"";
        }
        return key + "*=UTF-8''" + URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodedWord(String text) {
        if (text.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
            return text;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static void line(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }

    private static void base64(OutputStream out, byte[] content) throws IOException {
        try (OutputStream encoder = encoder(out)) {
            encoder.write(content);
        }
        line(out, "");
    }

    private static void base64(OutputStream out, InputStream in) throws IOException {
        try (OutputStream encoder = encoder(out)) {
            in.transferTo(encoder);
        }
        line(out, "");
    }

    /** Base64 mit Zeilen zu 76 Zeichen; schließt beim Schließen nur den Kodierer, nicht {@code out}. */
    private static OutputStream encoder(OutputStream out) {
        OutputStream open = new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
        return Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).wrap(open);
    }
}
