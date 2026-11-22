package com.aresstack.spaceharvester.deck.editor;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Wandelt Dateibytes in Text und zurück, ohne Inhalte zu verfälschen: UTF-8 (mit oder ohne BOM), wenn die Bytes
 * gültiges UTF-8 sind, sonst Windows-1252 (der übliche Zeichensatz älterer Dateien unter Windows). Binärdateien
 * (Nullbytes am Anfang) werden erkannt, damit der Editor sie nicht zerstört. Ablagen anderer Systeme (etwa EBCDIC über
 * FTP) bringen ihren Zeichensatz selbst mit.
 */
public final class TextDecoding {

    /** Größte Datei, die der Editor öffnet. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    /** Kennung für UTF-8 mit Byte-Order-Mark, damit das BOM beim Speichern erhalten bleibt. */
    public static final String UTF8_BOM = "UTF-8 (BOM)";
    private static final String WINDOWS_1252 = "windows-1252";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextDecoding() {
    }

    /** Ein dekodierter Text (Zeilenenden {@code \n}) samt erkanntem Zeichensatz und Zeilenende der Datei. */
    public record Decoded(String text, String charset, String lineEnding) {
    }

    /**
     * Das Zeilenende der Datei: {@code \r\n}, sobald es vorkommt (Windows), sonst {@code \r} oder {@code \n}.
     * Gemischte Dateien werden beim Speichern einheitlich.
     */
    public static String lineEnding(String text) {
        if (text.contains("\r\n")) {
            return "\r\n";
        }
        return text.indexOf('\r') >= 0 ? "\r" : "\n";
    }

    private static Decoded decoded(String raw, String charset) {
        return new Decoded(raw.replace("\r\n", "\n").replace('\r', '\n'), charset, lineEnding(raw));
    }

    /** Warum {@code bytes} nicht als Text geöffnet werden sollte; leer, wenn es Text ist. */
    public static Optional<String> problem(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            return Optional.of("Die Datei ist größer als " + (MAX_BYTES / (1024 * 1024)) + " MB.");
        }
        int probe = Math.min(bytes.length, 8192);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return Optional.of("Das ist keine Textdatei.");
            }
        }
        return Optional.empty();
    }

    public static Decoded decode(byte[] bytes) {
        if (startsWithBom(bytes)) {
            return decoded(new String(bytes, BOM.length, bytes.length - BOM.length, StandardCharsets.UTF_8),
                    UTF8_BOM);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return decoded(text, StandardCharsets.UTF_8.name());
        } catch (CharacterCodingException e) {
            return decoded(new String(bytes, Charset.forName(WINDOWS_1252)), WINDOWS_1252);
        }
    }

    /**
     * Wandelt {@code text} (Zeilenenden {@code \n}) mit {@code charset} und {@code lineEnding} zurück in Bytes; nicht
     * darstellbare Zeichen sind ein Fehler.
     */
    public static byte[] encode(String text, String charset, String lineEnding) throws DocumentException {
        String raw = "\n".equals(lineEnding) ? text : text.replace("\n", lineEnding);
        boolean bom = UTF8_BOM.equals(charset);
        Charset target = bom ? StandardCharsets.UTF_8 : Charset.forName(charset);
        CharsetEncoder encoder = target.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded;
        try {
            encoded = encoder.encode(CharBuffer.wrap(raw));
        } catch (CharacterCodingException e) {
            throw new DocumentException("Der Text enthält Zeichen, die " + target.displayName()
                    + " nicht darstellen kann.", e);
        }
        byte[] body = new byte[encoded.remaining()];
        encoded.get(body);
        if (!bom) {
            return body;
        }
        byte[] withBom = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, withBom, 0, BOM.length);
        System.arraycopy(body, 0, withBom, BOM.length, body.length);
        return withBom;
    }

    private static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2];
    }
}
