package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.astu.acropolis.speech.SpeechChunking;
import com.aresstack.enterpriseai.astu.acropolis.speech.SpeechText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vorlesen spricht, was die Anzeige zeigt: dieselbe Markdown-Deutung, Tabellenzeilen als Sätze, nichts Unsichtbares
 * (Issue 88).
 */
class MarkdownSpeechTest {

    @Test
    void tableRowsBecomeSentencesWithTheirColumnHeaders() {
        String spoken = MarkdownSpeech.spokenText("Die Vorsysteme:\n\n| Element | Bedeutung |\n|---|---|\n"
                + "| NIZZA | Vorsystem |\n| REST API | Schnittstelle |\n\nDanach folgt Text.");
        assertEquals("Die Vorsysteme:\n\nElement: NIZZA, Bedeutung: Vorsystem.\n"
                + "Element: REST API, Bedeutung: Schnittstelle.\n\nDanach folgt Text.", spoken);
        assertFalse(spoken.contains("|"), spoken);
        assertFalse(spoken.contains("---"), spoken);
    }

    @Test
    void hiddenMarkupIsNeverSpoken() {
        String spoken = MarkdownSpeech.spokenText("Erster Satz.<!-- 12345 versteckt -->\n\n"
                + "<!-- Kommentarblock 678 -->\n\nPreis &amp; Leistung&nbsp;sind gut. "
                + "Ein <span title=\"999\">Wort</span>​ mit­ Trenn﻿zeichen.\n\n"
                + "Siehe [Handbuch](https://example.invalid/seite?id=4711) und https://example.invalid/42.\n\n"
                + "```java\nint x = 31337;\n```\n\n```mermaid\ngraph TD; A-->B\n```\n\n[ref]: https://example.invalid/7");
        assertFalse(spoken.contains("12345"), spoken);
        assertFalse(spoken.contains("678"), spoken);
        assertFalse(spoken.contains("999"), spoken);
        assertFalse(spoken.contains("4711"), spoken);
        assertFalse(spoken.contains("42"), spoken);
        assertFalse(spoken.contains("31337"), spoken);
        assertFalse(spoken.contains("graph"), spoken);
        assertFalse(spoken.contains("&amp;"), spoken);
        assertFalse(spoken.contains("<"), spoken);
        assertTrue(spoken.contains("Preis & Leistung sind gut."), spoken);
        assertTrue(spoken.contains("Ein Wort mit Trennzeichen."), spoken);
        assertTrue(spoken.contains("Siehe Handbuch und"), spoken);
        for (char c : spoken.toCharArray()) {
            assertFalse(Character.getType(c) == Character.FORMAT, "unsichtbares Zeichen U+" + Integer.toHexString(c));
        }
    }

    @Test
    void anAnswerWrappedInAMarkdownFenceIsReadLikeItIsShown() {
        String spoken = MarkdownSpeech.spokenText("```markdown\n## Ergebnis\n\nDer Dienst läuft.\n```");
        assertEquals("Ergebnis.\nDer Dienst läuft.", spoken);
    }

    @Test
    void headingsListsAndParagraphsKeepTheirBoundaries() {
        String spoken = MarkdownSpeech.spokenText("# Betrieb\nErster Absatz\nmit Umbruch.\n\n- Punkt eins\n"
                + "- Punkt **zwei**\n  1. Unterpunkt\n\n> Zitat.\n\n---\n\nEnde");
        assertEquals("Betrieb.\nErster Absatz mit Umbruch.\n\nPunkt eins.\nPunkt zwei.\nUnterpunkt.\n\nZitat."
                + "\n\nEnde", spoken);
        List<String> paragraphs = SpeechText.chunks(spoken, SpeechChunking.PARAGRAPHS);
        assertEquals(4, paragraphs.size());
        assertEquals("Betrieb. Erster Absatz mit Umbruch.", paragraphs.get(0));
    }

    @Test
    void pictographsDoNotReachTheVoice() {
        String spoken = MarkdownSpeech.spokenText("Status ✅ fertig → weiter 🚀️.");
        assertEquals("Status fertig weiter.", spoken);
    }

    @Test
    void plainTextStaysAsItIs() {
        // Der Lesebereich gibt nach einem Doppelklick schon gesprochenen Text weiter: er bleibt Wort für Wort gleich.
        assertEquals("Betrieb. Erster Absatz.\n\nElement: NIZZA, Bedeutung: Vorsystem.",
                MarkdownSpeech.spokenText("Betrieb.\nErster Absatz.\n\nElement: NIZZA, Bedeutung: Vorsystem."));
        assertEquals("", MarkdownSpeech.spokenText(null));
        assertEquals("", MarkdownSpeech.spokenText(" \n "));
    }

    @Test
    void consecutiveHeadingsAreAllSpoken() {
        assertEquals("Betrieb.\n\nNachtlauf.\nDer Dienst läuft.",
                MarkdownSpeech.spokenText("# Betrieb\n\n## Nachtlauf\n\nDer Dienst läuft."));
    }
}
