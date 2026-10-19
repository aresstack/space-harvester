package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Stabile Resource-Identifier für Wiki-Seiten: {@code wiki:<siteKey>/<Titel>} (paketintern).
 *
 * <p>Der Titel wird wie von MediaWiki normalisiert verwendet (Leerzeichen als Unterstrich) und nach
 * RFC 3986 prozentkodiert; Buchstaben, Ziffern und {@code -._~:/()!,'*} bleiben lesbar. Die ID hängt damit
 * weder an der Page-ID (ändert sich bei Löschen und Wiederherstellen) noch an der URL der Installation
 * (ändert sich bei Umzug). Konzept: MainframeMate {@code WikiSourceScanner} ({@code wiki://site/Titel}) und
 * corenth {@code ResourceScheme.WIKI}; hier als opake URI ohne Authority, damit Titel mit Sonderzeichen
 * eindeutig rücklesbar sind.
 */
final class WikiResourceIds {

    static final String SCHEME = "wiki";
    private static final String SAFE = "-._~:/()!,'*";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private WikiResourceIds() {
    }

    static String canonicalTitle(String title) {
        return title.trim().replace(' ', '_');
    }

    /** Anzeigeform des Titels, wie MediaWiki ihn liefert (Unterstriche als Leerzeichen). */
    static String displayTitle(String title) {
        return title.replace('_', ' ');
    }

    static String idFor(String siteKey, String title) {
        return SCHEME + ":" + siteKey + "/" + encode(canonicalTitle(title));
    }

    /**
     * @return der Titel (mit Leerzeichen) oder {@code null}, wenn die ID nicht zu dieser Site gehört bzw.
     *         keine gültige {@code wiki:}-ID ist
     */
    static String titleOf(String siteKey, String id) {
        String prefix = SCHEME + ":" + siteKey + "/";
        if (id == null || !id.startsWith(prefix) || id.length() == prefix.length()) {
            return null;
        }
        String decoded = decode(id.substring(prefix.length()));
        return decoded == null ? null : displayTitle(decoded);
    }

    static String encode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || (c < 0x80 && SAFE.indexOf(c) >= 0)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    static String decode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    return null;
                }
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write((hi << 4) | lo);
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                return null;
            }
        }
        try {
            // Ungültiges UTF-8 ablehnen statt durch U+FFFD zu ersetzen, sonst fallen verschiedene IDs zusammen.
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
