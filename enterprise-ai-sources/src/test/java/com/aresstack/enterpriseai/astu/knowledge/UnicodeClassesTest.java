package com.aresstack.enterpriseai.astu.knowledge;

import com.aresstack.enterpriseai.astu.knowledge.UnicodeClasses.CharClass;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Der Zeichenklassen-Vertrag des Chunkers. Die Tests laufen in der CI auf JDK 8 und JDK 21 mit denselben
 * Erwartungswerten; dass sie auf beiden grün sind, belegt die JDK-Unabhängigkeit.
 */
public class UnicodeClassesTest {

    /**
     * SHA-256 über die Klasse und das Großbuchstaben-Bit aller 1.114.112 Codepunkte für
     * {@link UnicodeClasses#VERSION}. Ändert sich die Tabelle, ändern sich dieser Wert, die Version und
     * {@link KnowledgeChunkingPolicy#ALGORITHM_VERSION} gemeinsam.
     */
    private static final String FROZEN_TABLE_V1 = "5b406c9ef4c1d53c78b9228ce0ebbecf0a57f065bf1ff2581fc86a72951ea826";

    @Test
    public void everyCodePointHasAClassAndTheTableIsFrozenForVersion1() throws NoSuchAlgorithmException {
        assertEquals("unicode-classes-v1", UnicodeClasses.VERSION);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            CharClass c = UnicodeClasses.classify(cp);
            boolean upper = UnicodeClasses.isUpperCase(cp);
            if (upper) {
                assertEquals("Großbuchstabe muss LETTER sein: U+" + Integer.toHexString(cp), CharClass.LETTER, c);
            }
            digest.update((byte) (c.ordinal() * 2 + (upper ? 1 : 0)));
        }
        assertEquals(FROZEN_TABLE_V1, hex(digest.digest()));
        assertEquals(CharClass.OTHER, UnicodeClasses.classify(-1));
        assertEquals(CharClass.OTHER, UnicodeClasses.classify(0x110000));
    }

    @Test
    public void lettersIncludeLatinGreekCyrillicBlockScriptsAndSupplementaryPlanes() {
        assertClass(CharClass.LETTER, 'a', 'Z', 'ä', 'ß', 'Ø', 'ÿ', 'ł', 'Ǆ', 'ʒ', 'α', 'Ω', 'ж', 'Я', 'ѣ', 'א', 'ع',
                'अ', 'ก', 'ქ', 'あ', 'ア', '漢', '한', 'ﬁ', 'ｱ', 0xAA, 0xB5, 0xBA, 0x3005, 0x10400, 0x1D400, 0x1E900,
                0x20000, 0x2F800);
        // Blockregel: unbelegte Codepunkte innerhalb einer Schrift sind Buchstaben, auf jedem JDK.
        assertClass(CharClass.LETTER, 0x378, 0x9FFF, 0x2FA1F);
        // Andere Ziffernsysteme gelten als Buchstaben (Wortbestandteil), nur ASCII und Vollbreite sind DIGIT.
        assertClass(CharClass.LETTER, '٣', '३');
    }

    @Test
    public void marksCoverCombiningDiacriticsJoinersAndVariationSelectors() {
        assertClass(CharClass.MARK, 0x300, 0x308, 0x36F, 0x483, 0x1AB0, 0x1DC0, 0x20D0, 0x2DE0, 0x302A,
                0xAD, 0x200B, 0x200C, 0x200D, 0x2060, 0x2066, 0xFE0F, 0xFE20, 0xFEFF, 0xE0020, 0xE0100);
    }

    @Test
    public void digitsAreAsciiAndFullWidthOnly() {
        assertClass(CharClass.DIGIT, '0', '9', '０', '９');
        assertFalse(UnicodeClasses.isDigit('²'));
        assertFalse(UnicodeClasses.isDigit('Ⅳ'));
    }

    @Test
    public void whitespaceIncludesNonBreakingAndLineSeparators() {
        assertClass(CharClass.WHITESPACE, ' ', '\t', '\n', 0x0B, 0x0C, '\r', 0x85, 0xA0, 0x1680, 0x2000, 0x2007,
                0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000);
        assertClass(CharClass.CONTROL, 0x00, 0x08, 0x1C, 0x1F, 0x7F, 0x80, 0x9F);
    }

    @Test
    public void dashesQuotesAndPunctuationAreSeparated() {
        assertClass(CharClass.DASH, '-', 0x2010, 0x2013, 0x2014, 0x2015, 0x2053, 0x2E3A, 0x301C, 0x3030, 0xFE58,
                0xFE63, '－', 0x58A);
        assertClass(CharClass.QUOTE, '"', '\'', '«', '»', '‘', '’', '‚', '‛', '“', '”', '„', '‟', '‹', '›',
                '「', '』', '＂', '＇', 0xFF62);
        assertClass(CharClass.PUNCTUATION, '.', ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}', '/', '\\',
                '@', '#', '%', '&', '*', '_', '¡', '¿', '§', '¶', '·', '…', '†', '‰', '‧', '⁇', '‖', 0x37E, 0x387,
                '。', '、', '〈', '』' == 0 ? '〉' : '〉', '【', 0x2E00, 0xFE10, 0xFE50, '！', '？', '，', '．', 0xFF5F);
    }

    @Test
    public void symbolsCoverCurrencyMathArrowsEmojiAndPictographs() {
        assertClass(CharClass.SYMBOL, '$', '+', '<', '=', '>', '^', '`', '|', '~', '¢', '£', '¤', '¥', '©', '®',
                '°', '±', '²', '×', '÷', '€', '₿', '™', '←', '→', '∑', '≠', '√', '∞', '⌘', '①', '■', '☀', '☺',
                '✓', '⟨', '⬛', 0x2044, 0x2052, 0x3004, 0x3012, 0x3200, 0x3300, '￥', 0xFFFD,
                0x1D000, 0x1D100, 0x1D800, 0x1F004, 0x1F1E9, 0x1F300, 0x1F468, 0x1F600, 0x1F3FD, 0x1F9D1, 0x1FA80,
                0x1FBFF);
    }

    @Test
    public void privateUseSurrogatesAndUnassignedPlanesAreOther() {
        assertClass(CharClass.OTHER, 0xD800, 0xDFFF, 0xE000, 0xF8FF, 0xFFFE, 0xFFFF, 0x40000, 0xDFFFF, 0xF0000,
                0x10FFFF);
    }

    @Test
    public void upperCaseCoversLatinGreekCyrillicTitleCaseAndCasedSupplementaryScripts() {
        for (int cp : new int[]{'A', 'Z', 'Ä', 'Ö', 'Ü', 'É', 'Ø', 'Þ', 'Ā', 'Ć', 'Ł', 'Ŋ', 'Ÿ', 'Ž', 'Ǆ', 'ǅ', 'Ǉ', 'ǈ',
                'Ǎ', 'Ș', 'Ȣ', 'Ⱥ', 'Ά', 'Α', 'Ω', 'Ϊ', 'Ϙ', 'Ϸ', 'Ѐ', 'А', 'Я', 'Ѡ', 'Ҁ', 'Ҋ', 'Ӏ', 'Ӂ', 'Ԁ', 'Ḁ',
                'ẞ', 'Ạ', 'Ỿ', 'Ἀ', 'ᾈ', 'Ὼ', 'Ａ', 'Ｚ', 0x10400, 0x10427, 0x104B0, 0x1E900, 0x1E921}) {
            assertTrue("U+" + Integer.toHexString(cp), UnicodeClasses.isUpperCase(cp));
        }
        for (int cp : new int[]{'a', 'z', 'ä', 'ß', 'ÿ', 'ł', 'ǆ', 'ǉ', 'ǎ', 'ș', 'α', 'ω', 'ά', 'ς', 'ϙ', 'а', 'я',
                'ѡ', 'ҁ', 'ӂ', 'ḁ', 'ạ', 'ἀ', 'ᾀ', 'ａ', '×', 'µ', 'ª', '1', '漢', 'א', 'ع', 'Ⅳ', 'Ⓐ', 0x10428, 0x104D8,
                0x1E922, 0x1F600}) {
            assertFalse("U+" + Integer.toHexString(cp), UnicodeClasses.isUpperCase(cp));
        }
    }

    /**
     * Auf Latein, Griechisch, Kyrillisch und deren Erweiterungen stimmt der Vertrag für jeden Codepunkt, den das
     * laufende JDK kennt, mit dessen Groß-/Titelbuchstaben überein. Codepunkte, die das JDK noch nicht kennt
     * (JDK 8 hat Unicode 6.2, z. B. ohne U+037F oder U+052E), werden ausgelassen: Der Vertrag entscheidet dort
     * allein. Der Vergleich schützt also nur die Tabelle vor Tippfehlern, nicht umgekehrt.
     */
    @Test
    public void upperCaseAgreesWithTheJdkOnTheCodePointsItKnows() {
        int[][] ranges = {{0x41, 0x24F}, {0x370, 0x3FF}, {0x400, 0x52F}, {0x1E00, 0x1FFF}};
        int compared = 0;
        for (int[] range : ranges) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                if (!Character.isDefined(cp)) {
                    continue;
                }
                assertEquals("U+" + Integer.toHexString(cp), Character.isUpperCase(cp) || Character.isTitleCase(cp),
                        UnicodeClasses.isUpperCase(cp));
                compared++;
            }
        }
        assertTrue("verglichene Codepunkte: " + compared, compared > 1000);
    }

    @Test
    public void wordPartsAreLettersDigitsAndMarks() {
        assertTrue(UnicodeClasses.isWordPart('a'));
        assertTrue(UnicodeClasses.isWordPart('7'));
        assertTrue(UnicodeClasses.isWordPart(0x308));
        assertTrue(UnicodeClasses.isWordPart(0x20000));
        assertFalse(UnicodeClasses.isWordPart('-'));
        assertFalse(UnicodeClasses.isWordPart(' '));
        assertFalse(UnicodeClasses.isWordPart(0x1F600));
        assertFalse(UnicodeClasses.isWordPart('_'));
    }

    private static void assertClass(CharClass expected, int... codePoints) {
        for (int cp : codePoints) {
            assertEquals("U+" + Integer.toHexString(cp).toUpperCase() + " (" + new String(Character.toChars(cp)) + ")",
                    expected, UnicodeClasses.classify(cp));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
