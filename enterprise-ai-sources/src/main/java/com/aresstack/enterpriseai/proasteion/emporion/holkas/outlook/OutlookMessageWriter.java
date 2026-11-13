package com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook;

import com.pff.PSTAttachment;
import com.pff.PSTException;
import com.pff.PSTMessage;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * Schreibt ein angehängtes Outlook-Element ({@link PSTAttachment#ATTACHMENT_METHOD_EMBEDDED}) als RFC-822-Mail
 * ({@code message/rfc822}, {@code .eml}). Ein solches Element hat in der Postfachdatei keine Dateibytes; als Mail
 * liest es jeder Mail-Parser samt seinen eigenen Anhängen, die dabei als enthaltene Dateien erscheinen.
 *
 * <p>Alles fließt: Text und Anhänge gehen Base64-kodiert direkt in den Strom, nie ganz in den Speicher. Gleiche
 * Eingabe ergibt dieselben Bytes (feste Grenzen je Tiefe, keine Zufallswerte). Die Mail enthält nur Daten der
 * Quelle, keine erzeugten Hinweise: Ohne Namen in der Postfachdatei bleibt der Teil ohne Dateinamen. Gekürzt wird
 * nie: Ein unlesbarer Teil lässt das Schreiben scheitern, und tiefer als {@link #MAX_DEPTH} verschachtelte Elemente
 * schreibt der Aufrufer gar nicht ({@link #depth}). Verweise und OLE-Objekte haben keine Dateibytes in der
 * Postfachdatei und erscheinen nicht als Teil.</p>
 */
final class OutlookMessageWriter {

    /** Version der Darstellung; Teil der Revision eines solchen Anhangs, damit eine geänderte Darstellung neu lädt. */
    static final String VERSION = "eml1";
    /** Höchste Verschachtelungstiefe angehängter Elemente. */
    static final int MAX_DEPTH = 8;

    private static final byte[] CRLF = {'\r', '\n'};
    private static final int BUFFER = 64 * 1024;
    /** Bytes UTF-8 je Encoded Word, damit eine Kopfzeile unter 76 Zeichen bleibt (RFC 2047). */
    private static final int WORD_BYTES = 45;
    private static final Pattern MEDIA_TYPE = Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");
    private static final DateTimeFormatter DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private OutlookMessageWriter() {
    }

    /**
     * Schreibt {@code message} als Mail nach {@code out}; schließt {@code out} nicht.
     *
     * @throws IllegalArgumentException wenn angehängte Elemente tiefer als {@link #MAX_DEPTH} verschachtelt sind
     */
    static void write(PSTMessage message, OutputStream out) throws IOException, PSTException {
        write(message, out, 0);
    }

    /**
     * Ebenen der Nachricht samt ihrer angehängten Elemente (ohne Elemente: 1), höchstens bis eine Ebene über
     * {@link #MAX_DEPTH}.
     */
    static int depth(PSTMessage message) throws IOException, PSTException {
        return depth(message, 1);
    }

    private static int depth(PSTMessage message, int level) throws IOException, PSTException {
        int deepest = level;
        if (level > MAX_DEPTH) {
            return deepest;
        }
        int count = attachmentCount(message);
        for (int i = 0; i < count; i++) {
            PSTAttachment attachment = message.getAttachment(i);
            if (attachment.getAttachMethod() == PSTAttachment.ATTACHMENT_METHOD_EMBEDDED) {
                PSTMessage inner = attachment.getEmbeddedPSTMessage();
                if (inner == null) {
                    throw new java.io.IOException("Angehängtes Element nicht lesbar");
                }
                deepest = Math.max(deepest, depth(inner, level + 1));
            }
        }
        return deepest;
    }

    private static void write(PSTMessage message, OutputStream out, int depth) throws IOException, PSTException {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("Angehängte Elemente tiefer als " + MAX_DEPTH + " Ebenen");
        }
        header(out, "MIME-Version", "1.0");
        String messageId = oneLine(message.getInternetMessageId());
        if (!messageId.isEmpty() && ascii(messageId)) {
            header(out, "Message-ID", messageId);
        }
        Date date = firstDate(message.getMessageDeliveryTime(), message.getClientSubmitTime(),
                message.getLastModificationTime());
        if (date != null) {
            header(out, "Date", DATE.format(date.toInstant().atZone(ZoneOffset.UTC)));
        }
        String from = mailbox(message.getSenderName(), message.getSenderEmailAddress());
        if (!from.isEmpty()) {
            header(out, "From", from);
        }
        text(out, "To", message.getDisplayTo());
        text(out, "Cc", message.getDisplayCC());
        text(out, "Subject", message.getSubject());

        String body = safe(message.getBody());
        String html = body.isEmpty() ? safe(message.getBodyHTML()) : "";
        String bodyType = html.isEmpty() ? "text/plain; charset=UTF-8" : "text/html; charset=UTF-8";
        String bodyText = html.isEmpty() ? body : html;
        int count = attachmentCount(message);
        if (count == 0) {
            header(out, "Content-Type", bodyType);
            header(out, "Content-Transfer-Encoding", "base64");
            out.write(CRLF);
            base64(out, bodyText.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String boundary = "outlook-part-" + depth;
        header(out, "Content-Type", "multipart/mixed; boundary=\"" + boundary + "\"");
        out.write(CRLF);
        boundary(out, boundary, false);
        header(out, "Content-Type", bodyType);
        header(out, "Content-Transfer-Encoding", "base64");
        out.write(CRLF);
        base64(out, bodyText.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < count; i++) {
            PSTAttachment attachment = message.getAttachment(i);
            int method = attachment.getAttachMethod();
            String name = OutlookKnowledgeSource.sourceName(attachment);
            if (method == PSTAttachment.ATTACHMENT_METHOD_BY_VALUE) {
                InputStream in = OutlookKnowledgeSource.fileStream(attachment);
                String type = contentType(OutlookKnowledgeSource.mediaType(attachment, name));
                boundary(out, boundary, false);
                header(out, "Content-Type", name.isEmpty() ? type : type + "; " + parameter("name", name));
                header(out, "Content-Disposition", name.isEmpty() ? "attachment"
                        : "attachment; " + parameter("filename", name));
                header(out, "Content-Transfer-Encoding", "base64");
                out.write(CRLF);
                if (in == null) {
                    // Ohne Datenfeld hat der Anhang in der Postfachdatei keine Bytes.
                    base64(out, new byte[0]);
                    continue;
                }
                try {
                    base64(out, in);
                } finally {
                    in.close();
                }
            } else if (method == PSTAttachment.ATTACHMENT_METHOD_EMBEDDED) {
                PSTMessage inner = attachment.getEmbeddedPSTMessage();
                if (inner == null) {
                    throw new java.io.IOException("Angehängtes Element nicht lesbar");
                }
                boundary(out, boundary, false);
                header(out, "Content-Type", "message/rfc822");
                header(out, "Content-Disposition", name.isEmpty() ? "attachment"
                        : "attachment; " + parameter("filename", name));
                out.write(CRLF);
                write(inner, out, depth + 1);
                out.write(CRLF);
            }
            // Verweise und OLE-Objekte haben keine Dateibytes in der Postfachdatei.
        }
        boundary(out, boundary, true);
    }

    /**
     * Die Zahl der Zeilen in der Anhangstabelle. java-libpst meldet eine unlesbare Tabelle als null Anhänge; trägt die
     * Nachricht dabei das Kennzeichen „hat Anhänge“, ist das ein Lesefehler und kein leerer Anhang, damit nie eine
     * Nachricht ohne ihre Dateien als vollständig gilt. Laufzeitfehler gehen unverändert an den Aufrufer.
     */
    static int attachmentCount(PSTMessage message) throws IOException {
        int count = message.getNumberOfAttachments();
        if (count <= 0 && message.hasAttachments()) {
            throw new IOException("Anhangstabelle nicht lesbar");
        }
        return Math.max(0, count);
    }

    // ── Kopf ───────────────────────────────────────────────────────────────────────────────────────────────

    private static void header(OutputStream out, String name, String value) throws IOException {
        out.write((name + ": " + value).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }

    private static void text(OutputStream out, String name, String value) throws IOException {
        String text = oneLine(value);
        if (!text.isEmpty()) {
            header(out, name, encodedText(text));
        }
    }

    /** Ein Postfach wie {@code "Name" <adresse>}; ohne verwendbare Adresse nur der Name. */
    static String mailbox(String name, String address) {
        String display = oneLine(name);
        String email = oneLine(address);
        boolean usable = !email.isEmpty() && ascii(email) && email.indexOf('<') < 0 && email.indexOf('>') < 0
                && email.indexOf(' ') < 0;
        String shown = display.isEmpty() ? "" : ascii(display)
                ? "\"" + display.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : encodedText(display);
        if (!usable) {
            return shown;
        }
        return shown.isEmpty() ? "<" + email + ">" : shown + " <" + email + ">";
    }

    /** Text für eine Kopfzeile: druckbares ASCII unverändert, sonst Encoded Words (RFC 2047, UTF-8, Base64). */
    static String encodedText(String text) {
        if (ascii(text)) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int bytes = 0;
            while (end < text.length()) {
                int codePoint = text.codePointAt(end);
                int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > WORD_BYTES && end > start) {
                    break;
                }
                bytes += size;
                end += Character.charCount(codePoint);
            }
            if (result.length() > 0) {
                result.append("\r\n ");
            }
            result.append("=?UTF-8?B?").append(Base64.getEncoder().encodeToString(
                    text.substring(start, end).getBytes(StandardCharsets.UTF_8))).append("?=");
            start = end;
        }
        return result.toString();
    }

    /** Ein Parameter wie {@code filename="a.pdf"}; mit anderen Zeichen nach RFC 2231 ({@code filename*=UTF-8''…}). */
    static String parameter(String name, String value) {
        String text = oneLine(value);
        if (ascii(text) && text.indexOf('"') < 0 && text.indexOf('\\') < 0) {
            return name + "=\"" + text + "\"";
        }
        StringBuilder encoded = new StringBuilder(name).append("*=UTF-8''");
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static String contentType(String type) {
        return type != null && MEDIA_TYPE.matcher(type).matches() ? type : "application/octet-stream";
    }

    private static void boundary(OutputStream out, String boundary, boolean last) throws IOException {
        out.write(("--" + boundary + (last ? "--" : "")).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }

    // ── Inhalt ─────────────────────────────────────────────────────────────────────────────────────────────

    private static void base64(OutputStream out, byte[] content) throws IOException {
        OutputStream encoder = Base64.getMimeEncoder(76, CRLF).wrap(new Unclosed(out));
        encoder.write(content);
        encoder.close();
        out.write(CRLF);
    }

    private static void base64(OutputStream out, InputStream in) throws IOException {
        OutputStream encoder = Base64.getMimeEncoder(76, CRLF).wrap(new Unclosed(out));
        byte[] buffer = new byte[BUFFER];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            encoder.write(buffer, 0, n);
        }
        encoder.close();
        out.write(CRLF);
    }

    // ── Hilfen ─────────────────────────────────────────────────────────────────────────────────────────────

    private static Date firstDate(Date... dates) {
        for (Date date : dates) {
            if (date != null) {
                return date;
            }
        }
        return null;
    }

    /** Ohne Zeilenumbrüche und Steuerzeichen, damit kein Wert eine Kopfzeile beenden kann. */
    static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        boolean gap = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                if (!gap) {
                    sb.append(' ');
                }
                gap = true;
            } else {
                sb.append(c);
                gap = false;
            }
        }
        return sb.toString().trim();
    }

    private static boolean ascii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    /** Lässt den Strom des Aufrufers offen, wenn der Base64-Kodierer schließt. */
    private static final class Unclosed extends FilterOutputStream {

        Unclosed(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
