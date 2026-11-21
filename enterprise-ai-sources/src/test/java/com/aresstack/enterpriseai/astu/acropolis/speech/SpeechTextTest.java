package com.aresstack.enterpriseai.astu.acropolis.speech;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Markdown headings and list items keep the blank lines between them, so PARAGRAPHS chunking sees each section. */
public class SpeechTextTest {

    static final String NUMBERED_SECTIONS = "Einleitung.\n\n"
            + "### 1. **Fragen beantworten**\n"
            + "* **Allgemeinwissen** und anderes.\n"
            + "* **Fachwissen** und anderes.\n\n"
            + "### 2. **Dokumente**\n"
            + "* **Durchsuchen** von Anhängen.\n\n"
            + "### 3. **Wissensdatenbank**\n"
            + "* **Interne Quellen** durchsuchen.";

    @Test
    public void markdownListsKeepTheirParagraphBoundariesForReadAloud() {
        String plain = SpeechText.plainText(NUMBERED_SECTIONS);

        String[] paragraphs = plain.split("\\n\\s*\\n");
        assertEquals(plain, 4, paragraphs.length);
        assertEquals("Einleitung.", paragraphs[0]);
        assertEquals("1. Fragen beantworten\nAllgemeinwissen und anderes.\nFachwissen und anderes.", paragraphs[1]);
        assertEquals("2. Dokumente\nDurchsuchen von Anhängen.", paragraphs[2]);
        assertEquals("3. Wissensdatenbank\nInterne Quellen durchsuchen.", paragraphs[3]);
    }

    @Test
    public void windowsLineEndsAndTableRulesNeverMergeParagraphs() {
        String markdown = "Vorher.\r\n\r\n- Punkt eins\r\n- Punkt zwei\r\n\r\n| A | B |\n|---|---|\n| 1 | 2 |\n\nNachher.";

        List<String> chunks = SpeechText.chunks(SpeechText.plainText(markdown), SpeechChunking.PARAGRAPHS);

        assertEquals(chunks.toString(), 4, chunks.size());
        assertEquals("Vorher.", chunks.get(0));
        assertTrue(chunks.get(1), chunks.get(1).startsWith("Punkt eins"));
        assertTrue(chunks.get(2), chunks.get(2).contains("1 2"));
        assertEquals("Nachher.", chunks.get(3));
    }

    @Test
    public void pageTextForReadAloudDropsImagesDiagramsAndTheirDescriptions() {
        String page = "# Betrieb\n\n"
                + "Einleitender Absatz.\n\n"
                + "![Architekturskizze mit Pfeilen](bilder/skizze.png)\n\n"
                + "[![Vorschaubild](thumb.png)](gross.png)\n\n"
                + "<figure><img src=\"a.png\" alt=\"Bildtext\"/><figcaption>Abbildung 1: Ablauf</figcaption></figure>\n\n"
                + "<svg width=\"10\"><text>Knoten A</text></svg>\n\n"
                + "```mermaid\ngraph TD; A-->B;\n```\n\n"
                + "- Punkt eins\n- Punkt zwei\n\n"
                + "| Spalte | Wert |\n|---|---|\n| Dienst | aktiv |\n\n"
                + "Schluss mit [Link](seite.html).";

        String spoken = SpeechText.plainText(SpeechText.withoutVisuals(page));

        assertTrue(spoken, spoken.startsWith("Betrieb"));
        assertTrue(spoken, spoken.contains("Einleitender Absatz."));
        assertTrue(spoken, spoken.contains("Punkt eins"));
        assertTrue(spoken, spoken.contains("Dienst aktiv"));
        assertTrue(spoken, spoken.contains("Schluss mit Link."));
        for (String visual : new String[] {"Architekturskizze", "Vorschaubild", "Bildtext", "Abbildung",
                "Knoten A", "graph", "png", "svg", "<"}) {
            assertFalse(visual + " in: " + spoken, spoken.contains(visual));
        }
    }

    @Test
    public void voiceStabilityFixEndsLongUnitsAtSentenceBoundariesAroundFiveHundredCharacters() {
        String sentence = "Dieser Satz beschreibt einen technischen Abschnitt mit genügend Wörtern für einen "
                + "realistischen Vorlesetest und endet bewusst an einer klaren Satzgrenze.";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(sentence);
        }

        List<String> legacy = SpeechText.chunks(text.toString(), SpeechChunking.ANSWER);
        List<String> stable = SpeechText.chunks(text.toString(), SpeechChunking.ANSWER, true);

        assertEquals("legacy stays one unit below 1200 characters", 1, legacy.size());
        assertTrue(stable.toString(), stable.size() > 1);
        for (int i = 0; i < stable.size(); i++) {
            String chunk = stable.get(i);
            assertTrue(chunk, chunk.endsWith("."));
            assertTrue(chunk, chunk.length() < SpeechText.VOICE_STABILITY_TARGET_CHARS
                    + SpeechText.MAX_CHUNK_CHARS + 2);
            if (i < stable.size() - 1) {
                assertTrue(chunk, chunk.length() >= SpeechText.VOICE_STABILITY_TARGET_CHARS);
            }
        }
    }

    @Test
    public void pageTextWithoutVisualsKeepsNullSafe() {
        assertEquals("", SpeechText.withoutVisuals(null));
        assertEquals("Nur Text.", SpeechText.withoutVisuals("Nur Text."));
    }
}
