package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Wandelt MVS-Satzstruktur (Satzende {@code FF01}, Dateiende {@code FF02}) in Text mit Zeilenumbrüchen; Leerraum am
 * Satzende (Auffüllung auf LRECL) wird entfernt.
 *
 * <p>Lesehälfte von {@code RecordStructureCodec} aus MainframeMate mit dessen Standardwerten.
 */
final class RecordStructureText {

    private static final byte[] RECORD_MARKER = {(byte) 0xFF, 0x01};
    private static final byte[] END_MARKER = {(byte) 0xFF, 0x02};

    private RecordStructureText() {
    }

    static String decode(byte[] remoteBytes, Charset charset, boolean recordStructure) {
        if (remoteBytes == null || remoteBytes.length == 0) {
            return "";
        }
        if (!recordStructure) {
            return stripTrailingWhitespacePerLine(new String(remoteBytes, charset));
        }
        byte[] transformed = replace(remoteBytes, RECORD_MARKER, "\n".getBytes(charset));
        if (endsWith(transformed, END_MARKER)) {
            transformed = Arrays.copyOf(transformed, transformed.length - END_MARKER.length);
        }
        if (transformed.length > 0 && transformed[transformed.length - 1] == (byte) 0x0A) {
            transformed = Arrays.copyOf(transformed, transformed.length - 1);
        }
        return stripTrailingWhitespacePerLine(new String(transformed, charset));
    }

    private static byte[] replace(byte[] input, byte[] target, byte[] replacement) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        int i = 0;
        while (i < input.length) {
            if (matches(input, i, target)) {
                out.write(replacement, 0, replacement.length);
                i += target.length;
            } else {
                out.write(input[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    private static boolean matches(byte[] input, int offset, byte[] target) {
        if (offset + target.length > input.length) {
            return false;
        }
        for (int j = 0; j < target.length; j++) {
            if (input[offset + j] != target[j]) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(byte[] input, byte[] suffix) {
        return input.length >= suffix.length && matches(input, input.length - suffix.length, suffix);
    }

    private static String stripTrailingWhitespacePerLine(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) <= ' ') {
                end--;
            }
            sb.append(line, 0, end);
        }
        return sb.toString();
    }
}
