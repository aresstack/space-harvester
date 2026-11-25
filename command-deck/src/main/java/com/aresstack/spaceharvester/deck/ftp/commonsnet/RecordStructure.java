package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.TextEncodingException;
import com.aresstack.spaceharvester.deck.ftp.TextTransform;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * Satzstruktur von MVS-Textdateien bei {@code STRU R}: Satzende, Dateiende und Füllbyte aus den Einstellungen
 * ({@link TextTransform}, Standard {@code FF01}/{@code FF02}/{@code 00}). Übernommen aus MainframeMate
 * ({@code RecordStructureCodec}), dort im Einsatz erprobt.
 *
 * <p>Host → Editor: Füllbytes {@code 00} sind beim Lesen schon entfernt; Satzenden werden Zeilenumbrüche, das
 * Dateiende fällt weg, ebenso der letzte Umbruch, und jede Zeile verliert nachlaufende Leer- und Steuerzeichen (FB-Sätze
 * sind auf LRECL aufgefüllt). Editor → Host: jede Zeile ohne Nachlauf plus Satzende, am Schluss das Dateiende.
 */
final class RecordStructure {

    /** Satz- und Dateiende der Standard-Einstellungen ({@link TextTransform#DEFAULT}). */
    static final byte[] RECORD_END = TextTransform.DEFAULT.recordMarkerBytes();
    static final byte[] FILE_END = TextTransform.DEFAULT.fileEndMarkerBytes();

    private RecordStructure() {
    }

    static String decode(byte[] remote, Charset charset) {
        return decode(remote, charset, TextTransform.DEFAULT);
    }

    /** Wie {@code RecordStructureCodec.decodeForEditor} in MainframeMate, mit den Werten aus den Einstellungen. */
    static String decode(byte[] remote, Charset charset, TextTransform transform) {
        if (remote == null || remote.length == 0) {
            return "";
        }
        byte[] recordMarker = transform.recordMarkerBytes();
        byte[] endMarker = transform.fileEndMarkerBytes();
        byte[] transformed = recordMarker == null || recordMarker.length == 0 ? remote
                : replace(remote, recordMarker, "\n".getBytes(charset));
        if (endMarker != null && endMarker.length > 0 && endsWith(transformed, endMarker)) {
            transformed = Arrays.copyOf(transformed, transformed.length - endMarker.length);
        }
        if (transform.removeFinalNewline() && transformed.length > 0
                && transformed[transformed.length - 1] == (byte) 0x0A) {
            transformed = Arrays.copyOf(transformed, transformed.length - 1);
        }
        return stripLines(new String(transformed, charset));
    }

    /** Detect suspicious control bytes before the lossy editor conversion. */
    static boolean suspectBinary(byte[] raw, Charset charset, TextTransform transform, boolean framed) {
        byte[] marker = transform.recordMarkerBytes();
        byte[] end = transform.fileEndMarkerBytes();
        int limit = raw.length;
        if (framed && end != null && end.length > 0 && endsWith(raw, end)) limit -= end.length;
        int start = 0;
        for (int i = 0; i <= limit; i++) {
            boolean boundary = framed && marker != null && marker.length > 0
                    && i + marker.length <= limit && matchesAt(raw, i, marker);
            if (i == limit || boundary) {
                int stop = i;
                Byte pad = transform.paddingByte();
                if (framed && pad != null) {
                    while (stop > start && raw[stop - 1] == pad) stop--;
                }
                String text = new String(raw, start, stop - start, charset);
                if (text.codePoints().anyMatch(c -> c == 0xfffd || (Character.isISOControl(c)
                        && c != '\n' && c != '\r' && c != '\t' && c != '\f'))) return true;
                if (boundary) {
                    i += marker.length - 1;
                    start = i + 1;
                }
            }
        }
        return false;
    }

    static byte[] encode(String text, Charset charset) throws TextEncodingException {
        return encode(text, charset, TextTransform.DEFAULT);
    }

    /** Wie {@code RecordStructureCodec.encodeForRemote} in MainframeMate, mit den Werten aus den Einstellungen. */
    static byte[] encode(String text, Charset charset, TextTransform transform) throws TextEncodingException {
        byte[] recordMarker = transform.recordMarkerBytes();
        byte[] endMarker = transform.fileEndMarkerBytes();
        String[] lines = (text == null ? "" : text).split("\n", -1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < lines.length; i++) {
            out.writeBytes(bytes(rtrim(lines[i]), charset, i + 1));
            if (recordMarker != null && recordMarker.length > 0) {
                out.writeBytes(recordMarker);
            }
        }
        if (endMarker != null && endMarker.length > 0) {
            out.writeBytes(endMarker);
        }
        return out.toByteArray();
    }

    /**
     * Kodiert einen ganzen Text; ein Zeichen, das der Zeichensatz nicht kennt, bricht mit Grund ab (nie still „?“).
     */
    static byte[] text(String text, Charset charset) throws TextEncodingException {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            bytes(lines[i], charset, i + 1);
        }
        return (text == null ? "" : text).getBytes(charset);
    }

    private static byte[] bytes(String line, Charset charset, int lineNumber) throws TextEncodingException {
        CharsetEncoder encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(line));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        } catch (CharacterCodingException e) {
            int offset = 0;
            while (offset < line.length()) {
                int codePoint = line.codePointAt(offset);
                if (!charset.newEncoder().canEncode(new String(Character.toChars(codePoint)))) {
                    break;
                }
                offset += Character.charCount(codePoint);
            }
            String character = offset < line.length()
                    ? new String(Character.toChars(line.codePointAt(offset))) : "?";
            throw new TextEncodingException("Nicht gespeichert: Das Zeichen „" + character + "“ in Zeile " + lineNumber
                    + ", Spalte " + (offset + 1) + " gibt es im Zeichensatz " + charset.name()
                    + " der Verbindung nicht. Bitte ersetzen oder unter „Erweitert“ einen anderen Zeichensatz wählen.");
        }
    }

    static byte[] withoutPadding(byte[] bytes) {
        return withoutPadding(bytes, TextTransform.DEFAULT);
    }

    /** Entfernt alle Füllbytes wie {@code readAllBytes} in MainframeMate; ohne Füllbyte bleibt alles. */
    static byte[] withoutPadding(byte[] bytes, TextTransform transform) {
        Byte padding = transform.paddingByte();
        if (padding == null) {
            return bytes;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (byte b : bytes) {
            if (b != padding) {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    static String stripLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            result.append(rtrim(lines[i]));
        }
        return result.toString();
    }

    static String rtrim(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) <= ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    private static byte[] replace(byte[] source, byte[] search, byte[] replacement) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length);
        int i = 0;
        while (i < source.length) {
            if (i + search.length <= source.length && matchesAt(source, i, search)) {
                out.writeBytes(replacement);
                i += search.length;
            } else {
                out.write(source[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    private static boolean matchesAt(byte[] source, int at, byte[] search) {
        for (int j = 0; j < search.length; j++) {
            if (source[at + j] != search[j]) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(byte[] source, byte[] suffix) {
        return source.length >= suffix.length
                && matchesAt(source, source.length - suffix.length, suffix);
    }
}
