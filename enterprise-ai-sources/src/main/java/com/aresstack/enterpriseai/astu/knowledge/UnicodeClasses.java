package com.aresstack.enterpriseai.astu.knowledge;

/**
 * Feste, versionierte Zeichenklassen für Chunker, Satzzerlegung und Token-Zähler.
 *
 * <p>Die Klassifizierung verwendet keine Unicode-Daten des laufenden JDK ({@code Character.getType},
 * {@code isLetter}, {@code isWhitespace} ...), weil sich deren Unicode-Version zwischen JDK 8 und neueren JDKs
 * unterscheidet und Chunks, Tokenzahlen und Satzgrenzen auf allen JDKs identisch sein müssen. Stattdessen gilt
 * dieser kleine, explizit versionierte Vertrag ({@link #VERSION}); {@code UnicodeClassesTest} friert die
 * vollständige Tabelle über einen Hash ein. Jede Änderung an der Tabelle erhöht die Version und damit
 * {@link KnowledgeChunkingPolicy#ALGORITHM_VERSION}.
 *
 * <p>Auflösung des Vertrags, bewusst keine vollständige Unicode-Datenbank:
 * <ul>
 * <li>Fein aufgelöst: ASCII, Latin-1, Latein erweitert, Griechisch, Kyrillisch, kombinierende Zeichen,
 * allgemeine Interpunktion, Symbole, Pfeile, Emoji/Piktogramme, CJK-Satzzeichen, Voll-/Halbbreitformen.</li>
 * <li>Blockweise: alle übrigen Schriften (Hebräisch, Arabisch, indische Schriften, CJK-Ideogramme, Hangul,
 * ergänzende Ebenen) gelten komplett als {@link CharClass#LETTER}, auch ihre Satzzeichen, Ziffern und noch
 * unbelegten Codepunkte. Ziffern sind nur ASCII- und Vollbreitziffern.</li>
 * <li>Groß-/Titelbuchstaben werden für Latein, Griechisch, Kyrillisch, Deseret, Osage und Adlam erkannt;
 * Schriften ohne Groß-/Kleinschreibung beginnen keinen Satz.</li>
 * <li>Private Nutzung, Ersatzzeichen (Surrogate) und alles Unbekannte sind {@link CharClass#OTHER}.</li>
 * </ul>
 */
final class UnicodeClasses {

    /** Version dieses Vertrags; bei jeder Tabellenänderung erhöhen. */
    static final String VERSION = "unicode-classes-v1";

    /** Die für Chunking relevanten Klassen; feiner wird nicht unterschieden. */
    enum CharClass {
        /** Buchstabe (inkl. blockweise als Buchstabe gezählter Schriften); Wortbestandteil. */
        LETTER,
        /** Kombinierendes oder unsichtbares verbindendes Zeichen (Akzent, ZWJ, weiches Trennzeichen); Wortbestandteil. */
        MARK,
        /** Dezimalziffer 0–9 (ASCII oder vollbreit); Wortbestandteil. */
        DIGIT,
        /** Leerraum inkl. geschützter Leerzeichen und Zeilen-/Absatztrenner. */
        WHITESPACE,
        /** Bindestrich, Gedankenstrich, Minus. */
        DASH,
        /** Anführungszeichen aller Formen. */
        QUOTE,
        /** Sonstige Interpunktion. */
        PUNCTUATION,
        /** Währungs-, Mathematik-, technische Symbole, Pfeile, Emoji und Piktogramme. */
        SYMBOL,
        /** Steuerzeichen (C0/C1) außer Leerraum. */
        CONTROL,
        /** Private Nutzung, Surrogate, unbekannt. */
        OTHER
    }

    private UnicodeClasses() {
    }

    static CharClass classify(int cp) {
        if (cp < 0 || cp > 0x10FFFF) {
            return CharClass.OTHER;
        }
        if (cp < 0x80) {
            return ascii(cp);
        }
        if (cp < 0x100) {
            return latin1(cp);
        }
        if (cp < 0x10000) {
            return basicPlane(cp);
        }
        return supplementary(cp);
    }

    static boolean isSpace(int cp) {
        return classify(cp) == CharClass.WHITESPACE;
    }

    static boolean isDigit(int cp) {
        return classify(cp) == CharClass.DIGIT;
    }

    static boolean isLetter(int cp) {
        return classify(cp) == CharClass.LETTER;
    }

    /** Buchstabe, Ziffer oder Mark: setzt ein Wort fort. */
    static boolean isWordPart(int cp) {
        CharClass c = classify(cp);
        return c == CharClass.LETTER || c == CharClass.DIGIT || c == CharClass.MARK;
    }

    /** Groß- oder Titelbuchstabe der im Klassenkommentar genannten Schriften. */
    static boolean isUpperCase(int cp) {
        if (cp >= 0xFF21 && cp <= 0xFF3A) {
            return true;
        }
        if (cp < 0x80) {
            return cp >= 'A' && cp <= 'Z';
        }
        if (cp < 0x100) {
            return cp >= 0xC0 && cp <= 0xDE && cp != 0xD7;
        }
        if (cp < 0x250) {
            return upperLatinExtended(cp);
        }
        if (cp >= 0x370 && cp < 0x400) {
            return upperGreek(cp);
        }
        if (cp >= 0x400 && cp < 0x530) {
            return upperCyrillic(cp);
        }
        if (cp >= 0x1E00 && cp < 0x1F00) {
            return (cp <= 0x1E95 || cp >= 0x1EA0) ? even(cp) : cp == 0x1E9E;
        }
        if (cp >= 0x1F00 && cp < 0x2000) {
            return upperGreekExtended(cp);
        }
        return in(cp, 0x10400, 0x10427) || in(cp, 0x104B0, 0x104D3) || in(cp, 0x1E900, 0x1E921);
    }

    // ---- Klassen -------------------------------------------------------------------------------------------

    private static CharClass ascii(int cp) {
        if (cp >= '0' && cp <= '9') {
            return CharClass.DIGIT;
        }
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) {
            return CharClass.LETTER;
        }
        switch (cp) {
            case ' ': case '\t': case '\n': case 0x0B: case 0x0C: case '\r':
                return CharClass.WHITESPACE;
            case '-':
                return CharClass.DASH;
            case '"': case '\'':
                return CharClass.QUOTE;
            case '!': case '#': case '%': case '&': case '(': case ')': case '*': case ',': case '.': case '/':
            case ':': case ';': case '?': case '@': case '[': case '\\': case ']': case '_': case '{': case '}':
                return CharClass.PUNCTUATION;
            case '$': case '+': case '<': case '=': case '>': case '^': case '`': case '|': case '~':
                return CharClass.SYMBOL;
            default:
                return CharClass.CONTROL; // 0x00–0x08, 0x0E–0x1F, 0x7F
        }
    }

    private static CharClass latin1(int cp) {
        if (cp < 0xA0) {
            return cp == 0x85 ? CharClass.WHITESPACE : CharClass.CONTROL;
        }
        switch (cp) {
            case 0xA0:
                return CharClass.WHITESPACE;
            case 0xAB: case 0xBB:
                return CharClass.QUOTE;
            case 0xAD:
                return CharClass.MARK; // weiches Trennzeichen
            case 0xA1: case 0xA7: case 0xB6: case 0xB7: case 0xBF:
                return CharClass.PUNCTUATION;
            case 0xAA: case 0xB5: case 0xBA:
                return CharClass.LETTER;
            case 0xD7: case 0xF7:
                return CharClass.SYMBOL;
            default:
                return cp >= 0xC0 ? CharClass.LETTER : CharClass.SYMBOL; // ¢ £ ¤ ¥ ¦ ¨ © ¬ ® ¯ ° ± ² ³ ´ ¸ ¹ ¼ ½ ¾
        }
    }

    private static CharClass basicPlane(int cp) {
        if (cp < 0x370) {
            return in(cp, 0x300, 0x36F) ? CharClass.MARK : CharClass.LETTER; // Latein erweitert, IPA, Modifier
        }
        if (cp < 0x400) {
            return (cp == 0x37E || cp == 0x387) ? CharClass.PUNCTUATION : CharClass.LETTER; // Griechisch
        }
        if (cp < 0x530) {
            return in(cp, 0x483, 0x489) ? CharClass.MARK : CharClass.LETTER; // Kyrillisch
        }
        if (cp < 0x2000) {
            return otherScripts(cp);
        }
        if (cp < 0x2070) {
            return generalPunctuation(cp);
        }
        if (cp < 0x2C00) {
            return in(cp, 0x20D0, 0x20FF) ? CharClass.MARK : CharClass.SYMBOL; // Hoch-/Tiefstellung, Währung, Pfeile ...
        }
        if (cp < 0x2E00) {
            return in(cp, 0x2DE0, 0x2DFF) ? CharClass.MARK : CharClass.LETTER; // Glagolitisch, Koptisch, Tifinagh ...
        }
        if (cp < 0x2E80) {
            return (cp == 0x2E17 || cp == 0x2E1A || cp == 0x2E3A || cp == 0x2E3B || cp == 0x2E40 || cp == 0x2E5D)
                    ? CharClass.DASH : CharClass.PUNCTUATION;
        }
        if (cp < 0x3000) {
            return CharClass.LETTER; // CJK-Radikale
        }
        if (cp < 0x3040) {
            return cjkPunctuation(cp);
        }
        if (cp < 0xA000) {
            return in(cp, 0x3200, 0x33FF) ? CharClass.SYMBOL : CharClass.LETTER; // Kana, CJK-Ideogramme
        }
        if (cp < 0xD800) {
            return CharClass.LETTER; // Yi, Hangul u. a.
        }
        if (cp < 0xF900) {
            return CharClass.OTHER; // Surrogate, private Nutzung
        }
        if (cp < 0xFE00) {
            return CharClass.LETTER; // CJK-Kompatibilität, Präsentationsformen
        }
        if (cp < 0xFF00) {
            return compatibilityForms(cp);
        }
        return halfAndFullWidth(cp);
    }

    /** Hebräisch bis Griechisch erweitert: blockweise Buchstaben; nur Leerraum und Mark-Blöcke feiner. */
    private static CharClass otherScripts(int cp) {
        if (cp == 0x1680) {
            return CharClass.WHITESPACE;
        }
        if (cp == 0x58A) {
            return CharClass.DASH;
        }
        if (in(cp, 0x1AB0, 0x1AFF) || in(cp, 0x1DC0, 0x1DFF)) {
            return CharClass.MARK;
        }
        return CharClass.LETTER;
    }

    private static CharClass generalPunctuation(int cp) {
        if (cp <= 0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F) {
            return CharClass.WHITESPACE;
        }
        if (in(cp, 0x200B, 0x200F) || in(cp, 0x202A, 0x202E) || in(cp, 0x2060, 0x206F)) {
            return CharClass.MARK; // ZWSP, ZWNJ, ZWJ, Richtungs- und Formatzeichen
        }
        if (in(cp, 0x2010, 0x2015) || cp == 0x2053) {
            return CharClass.DASH;
        }
        if (in(cp, 0x2018, 0x201F) || cp == 0x2039 || cp == 0x203A) {
            return CharClass.QUOTE;
        }
        if (cp == 0x2044 || cp == 0x2052) {
            return CharClass.SYMBOL;
        }
        return CharClass.PUNCTUATION;
    }

    private static CharClass cjkPunctuation(int cp) {
        if (cp == 0x3000) {
            return CharClass.WHITESPACE;
        }
        if (in(cp, 0x300C, 0x300F)) {
            return CharClass.QUOTE;
        }
        if (cp == 0x301C || cp == 0x3030) {
            return CharClass.DASH;
        }
        if (in(cp, 0x3001, 0x3003) || in(cp, 0x3008, 0x3011) || in(cp, 0x3014, 0x301F) || cp == 0x303D) {
            return CharClass.PUNCTUATION;
        }
        if (cp == 0x3004 || cp == 0x3012 || cp == 0x3013 || cp == 0x3020 || in(cp, 0x3036, 0x3037)
                || cp == 0x303E || cp == 0x303F) {
            return CharClass.SYMBOL;
        }
        if (in(cp, 0x302A, 0x302F)) {
            return CharClass.MARK;
        }
        return CharClass.LETTER;
    }

    private static CharClass compatibilityForms(int cp) {
        if (in(cp, 0xFE00, 0xFE0F) || in(cp, 0xFE20, 0xFE2F) || cp == 0xFEFF) {
            return CharClass.MARK; // Variantenselektoren, kombinierende Halbzeichen, BOM
        }
        if (cp < 0xFE70) {
            if (cp == 0xFE31 || cp == 0xFE32 || cp == 0xFE58 || cp == 0xFE63) {
                return CharClass.DASH;
            }
            if (cp == 0xFE62 || in(cp, 0xFE64, 0xFE66) || cp == 0xFE69) {
                return CharClass.SYMBOL;
            }
            return CharClass.PUNCTUATION; // vertikale und kleine Formen
        }
        return CharClass.LETTER; // arabische Präsentationsformen B
    }

    private static CharClass halfAndFullWidth(int cp) {
        if (in(cp, 0xFF01, 0xFF5E)) {
            return ascii(cp - 0xFEE0);
        }
        if (in(cp, 0xFF5F, 0xFF65)) {
            return (cp == 0xFF62 || cp == 0xFF63) ? CharClass.QUOTE : CharClass.PUNCTUATION;
        }
        if (in(cp, 0xFF66, 0xFFDC)) {
            return CharClass.LETTER; // Halbbreit-Katakana und -Hangul
        }
        if (in(cp, 0xFFE0, 0xFFEE) || cp == 0xFFFC || cp == 0xFFFD) {
            return CharClass.SYMBOL;
        }
        if (in(cp, 0xFFF9, 0xFFFB)) {
            return CharClass.MARK;
        }
        return CharClass.OTHER;
    }

    private static CharClass supplementary(int cp) {
        if (cp < 0x1D000) {
            return CharClass.LETTER; // historische und neuere Schriften
        }
        if (cp < 0x1D400) {
            return CharClass.SYMBOL; // Notenschrift, Tai-Xuan-Jing, Zählstäbe
        }
        if (cp < 0x1D800) {
            return CharClass.LETTER; // mathematische Buchstaben
        }
        if (cp < 0x1E000) {
            return CharClass.SYMBOL; // Sutton SignWriting
        }
        if (cp < 0x1F000) {
            return CharClass.LETTER; // Glagolitisch ergänzt, Adlam, Mende Kikakui ...
        }
        if (cp < 0x20000) {
            return CharClass.SYMBOL; // Emoji, Piktogramme, eingekreiste Zeichen, Mahjong, Domino
        }
        if (cp < 0x40000) {
            return CharClass.LETTER; // CJK-Ideogramme Erweiterungen B–I
        }
        if (in(cp, 0xE0000, 0xE007F) || in(cp, 0xE0100, 0xE01EF)) {
            return CharClass.MARK; // Tags, Variantenselektoren ergänzt
        }
        return CharClass.OTHER; // unbelegte Ebenen, private Nutzung
    }

    // ---- Großbuchstaben --------------------------------------------------------------------------------------

    private static boolean upperLatinExtended(int cp) {
        if (cp < 0x180) {
            if (cp <= 0x137 || in(cp, 0x14A, 0x177)) {
                return even(cp);
            }
            if (in(cp, 0x139, 0x148) || in(cp, 0x179, 0x17E)) {
                return odd(cp);
            }
            return cp == 0x178;
        }
        if (in(cp, 0x1CD, 0x1DC)) {
            return odd(cp);
        }
        if (in(cp, 0x1DE, 0x1EF) || in(cp, 0x1F8, 0x21F) || in(cp, 0x222, 0x233) || in(cp, 0x246, 0x24F)) {
            return even(cp);
        }
        switch (cp) {
            case 0x181: case 0x182: case 0x184: case 0x186: case 0x187: case 0x189: case 0x18A: case 0x18B:
            case 0x18E: case 0x18F: case 0x190: case 0x191: case 0x193: case 0x194: case 0x196: case 0x197:
            case 0x198: case 0x19C: case 0x19D: case 0x19F: case 0x1A0: case 0x1A2: case 0x1A4: case 0x1A6:
            case 0x1A7: case 0x1A9: case 0x1AC: case 0x1AE: case 0x1AF: case 0x1B1: case 0x1B2: case 0x1B3:
            case 0x1B5: case 0x1B7: case 0x1B8: case 0x1BC: case 0x1C4: case 0x1C5: case 0x1C7: case 0x1C8:
            case 0x1CA: case 0x1CB: case 0x1F1: case 0x1F2: case 0x1F4: case 0x1F6: case 0x1F7: case 0x220:
            case 0x23A: case 0x23B: case 0x23D: case 0x23E: case 0x241: case 0x243: case 0x244: case 0x245:
                return true;
            default:
                return false;
        }
    }

    private static boolean upperGreek(int cp) {
        if (in(cp, 0x391, 0x3A1) || in(cp, 0x3A3, 0x3AB) || in(cp, 0x388, 0x38A) || in(cp, 0x38E, 0x38F)
                || in(cp, 0x3D2, 0x3D4) || in(cp, 0x3FD, 0x3FF)) {
            return true;
        }
        if (in(cp, 0x3D8, 0x3EF)) {
            return even(cp);
        }
        return cp == 0x370 || cp == 0x372 || cp == 0x376 || cp == 0x37F || cp == 0x386 || cp == 0x38C
                || cp == 0x3CF || cp == 0x3F4 || cp == 0x3F7 || cp == 0x3F9 || cp == 0x3FA;
    }

    private static boolean upperCyrillic(int cp) {
        if (cp <= 0x42F) {
            return true;
        }
        if (in(cp, 0x460, 0x481) || in(cp, 0x48A, 0x4BF) || in(cp, 0x4D0, 0x52F)) {
            return even(cp);
        }
        if (in(cp, 0x4C1, 0x4CE)) {
            return odd(cp);
        }
        return cp == 0x4C0;
    }

    private static boolean upperGreekExtended(int cp) {
        int low = cp & 0xF;
        switch (cp & 0xFFF0) {
            case 0x1F00: case 0x1F20: case 0x1F30: case 0x1F60: case 0x1F80: case 0x1F90: case 0x1FA0:
                return low >= 8;
            case 0x1F10: case 0x1F40:
                return low >= 8 && low <= 0xD;
            case 0x1F50:
                return low == 9 || low == 0xB || low == 0xD || low == 0xF;
            case 0x1FB0: case 0x1FC0: case 0x1FF0:
                return low >= 8 && low <= 0xC;
            case 0x1FD0:
                return low >= 8 && low <= 0xB;
            case 0x1FE0:
                return low >= 8 && low <= 0xC;
            default:
                return false;
        }
    }

    private static boolean in(int cp, int from, int to) {
        return cp >= from && cp <= to;
    }

    private static boolean even(int cp) {
        return (cp & 1) == 0;
    }

    private static boolean odd(int cp) {
        return (cp & 1) == 1;
    }
}
