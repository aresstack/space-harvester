package com.aresstack.enterpriseai.astu.acropolis.textmining;

import com.aresstack.enterpriseai.astu.acropolis.passage.SentenceSegmenter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Long forms in these tests are invented; none names a real system. */
public class AcronymExtractorTest {

    private final AcronymExtractor extractor = new AcronymExtractor(SentenceSegmenter.splitter());

    private AcronymAnalysis analyze(String text) {
        return extractor.analyze(text, AcronymSettings.defaults());
    }

    private static List<String> normalized(List<AcronymFinding> findings) {
        List<String> names = new ArrayList<String>();
        for (AcronymFinding finding : findings) {
            names.add(finding.normalized());
        }
        return names;
    }

    @Test
    public void findsPlainAcronymsWithEveryOccurrence() {
        String text = "Die IAR liefert Daten an NIZZA. Danach prüft ZAUF die Daten, und die IAR quittiert.";
        AcronymAnalysis analysis = analyze(text);
        assertEquals(Arrays.asList("IAR", "NIZZA", "ZAUF"), normalized(analysis.acronyms()));
        AcronymFinding iar = analysis.acronym("IAR");
        assertEquals(2, iar.occurrences().size());
        assertEquals("IAR", iar.occurrences().get(1).of(text));
        assertEquals(FindingStatus.CANDIDATE, iar.status());
        assertTrue(iar.longForms().isEmpty());
    }

    @Test
    public void readsDottedHyphenatedAndPluralForms() {
        String text = "Die IAR-Schnittstelle, zwei IARs und die U.S.A. sowie REST/HTTP.";
        AcronymAnalysis analysis = analyze(text);
        assertEquals(Arrays.asList("IAR", "USA", "REST", "HTTP"), normalized(analysis.acronyms()));
        AcronymFinding iar = analysis.acronym("IAR");
        assertEquals(Arrays.asList("IAR", "IARs"), iar.surfaceForms());
        assertEquals("IAR", iar.occurrences().get(0).of(text));
        assertEquals("U.S.A", analysis.acronym("USA").occurrences().get(0).of(text));
    }

    @Test
    public void ignoresNormalCapitalizationSingleLettersNumbersAndShouting() {
        String text = "Ein Satz mit Substantiven, A und B, 2026 und 12.10.2026.\n"
                + "ACHTUNG BITTE DIESEN ABSCHNITT LESEN\n"
                + "Normaler Text mit IAR.";
        AcronymAnalysis analysis = analyze(text);
        assertEquals(Collections.singletonList("IAR"), normalized(analysis.acronyms()));
        assertTrue(analysis.identifiers().isEmpty());
    }

    @Test
    public void leavesOutCodeAddressesAndFileNames() {
        String text = "Siehe https://example.org/IAR/HTTP und mail@IAR.example.org.\n"
                + "Die Datei NIZZA.xml liegt unter C:\\ZAUF\\XML neben `REST` und [Link](http://x.example/SOAP).\n"
                + "```\nint IAR_MAX = 1; // JSON\n```\n"
                + "Nur ZAUF zählt.";
        AcronymAnalysis analysis = analyze(text);
        assertEquals(Collections.singletonList("ZAUF"), normalized(analysis.acronyms()));
    }

    @Test
    public void separatesTechnicalIdentifiersFromAcronyms() {
        String text = "Der Auftrag ABC-1234 nutzt MAX_SIZE, B2B und X12Y7.";
        AcronymAnalysis analysis = analyze(text);
        assertEquals(Collections.singletonList("B2B"), normalized(analysis.acronyms()));
        assertEquals(Arrays.asList("ABC-1234", "MAX_SIZE", "X12Y7"), normalized(analysis.identifiers()));
    }

    @Test
    public void longFormBeforeParenthesesIsEvidenced() {
        String text = "Die Daten kommen aus dem Inventar aller Regenschirme (IAR). Später nur noch IAR.";
        AcronymFinding iar = analyze(text).acronym("IAR");
        assertEquals(FindingStatus.EVIDENCED, iar.status());
        assertEquals(Collections.singletonList("Inventar aller Regenschirme"), iar.longForms());
        AcronymDefinition definition = iar.definitions().get(0);
        assertEquals(AcronymDefinition.Pattern.LONG_FORM_FIRST, definition.pattern());
        assertEquals("Inventar aller Regenschirme", definition.longSpan().of(text));
        assertEquals("IAR", definition.shortSpan().of(text));
        assertEquals(2, iar.occurrences().size());
    }

    @Test
    public void germanCompoundsWithHyphenAlign() {
        String text = "Wir nutzen die Import- und Abgleichsroutine für Regenschirme (IAR) täglich.";
        AcronymFinding iar = analyze(text).acronym("IAR");
        assertEquals(Collections.singletonList("Import- und Abgleichsroutine für Regenschirme"), iar.longForms());
    }

    @Test
    public void shortFormBeforeParenthesesIsEvidenced() {
        String text = "Der Dienst spricht HTTP (Hypertext Transfer Protocol) mit dem Server.";
        AcronymFinding http = analyze(text).acronym("HTTP");
        assertEquals(Collections.singletonList("Hypertext Transfer Protocol"), http.longForms());
        assertEquals(AcronymDefinition.Pattern.SHORT_FORM_FIRST, http.definitions().get(0).pattern());
    }

    @Test
    public void noLongFormIsInventedWhenLettersDoNotAlign() {
        String text = "Wir nutzen das neue Werkzeug (IAR) und die Ablage (NIZZA, siehe unten).";
        AcronymAnalysis analysis = analyze(text);
        assertEquals(FindingStatus.CANDIDATE, analysis.acronym("IAR").status());
        assertEquals(FindingStatus.CANDIDATE, analysis.acronym("NIZZA").status());
    }

    @Test
    public void parenthesesWithOtherContentAreNoDefinition() {
        String text = "Die IAR (siehe Abschnitt 3) und ZAUF (12 Einträge) laufen.";
        AcronymAnalysis analysis = analyze(text);
        assertTrue(analysis.acronym("IAR").definitions().isEmpty());
        assertTrue(analysis.acronym("ZAUF").definitions().isEmpty());
    }

    @Test
    public void conflictingLongFormsStaySeparate() {
        String text = "Das Inventar aller Regenschirme (IAR) ist alt.\n"
                + "Die Initiative autonomer Roboter (IAR) ist neu.\n"
                + "Nochmals das Inventar aller Regenschirme (IAR).";
        AcronymFinding iar = analyze(text).acronym("IAR");
        assertEquals(Arrays.asList("Inventar aller Regenschirme", "Initiative autonomer Roboter"), iar.longForms());
        assertTrue(iar.hasConflictingLongForms());
        assertEquals(3, iar.definitions().size());
    }

    @Test
    public void definitionNeverCrossesSentenceOrLine() {
        String text = "Inventar aller Regenschirme.\n(IAR) steht allein.";
        assertEquals(FindingStatus.CANDIDATE, analyze(text).acronym("IAR").status());
    }

    @Test
    public void unicodeAndUmlautsAreWordCharacters() {
        String text = "Über die Ämter-Übersicht (ÄÜ) und ÖPNV-Daten.";
        AcronymAnalysis analysis = analyze(text);
        assertEquals(Arrays.asList("ÄÜ", "ÖPNV"), normalized(analysis.acronyms()));
        assertEquals(Collections.singletonList("Ämter-Übersicht"), analysis.acronym("ÄÜ").longForms());
    }

    @Test
    public void usesTheSentencesOfTheSegmenter() {
        final List<String> asked = new ArrayList<String>();
        SentenceSegmenter model = new SentenceSegmenter() {
            @Override
            public List<String> sentences(String paragraph) {
                asked.add(paragraph);
                return SentenceSegmenter.splitter().sentences(paragraph);
            }

            @Override
            public String id() {
                return "model-test";
            }
        };
        AcronymAnalysis analysis = new AcronymExtractor(model).analyze("Erst IAR. Dann ZAUF.", null);
        assertEquals(Collections.singletonList("Erst IAR. Dann ZAUF."), asked);
        assertEquals("model-test", analysis.sentenceMethod());
    }

    @Test
    public void emptyTextHasNoFindings() {
        assertTrue(analyze("  ").findings().isEmpty());
        assertNull(analyze("Nur Text.").acronym("IAR"));
    }

    @Test
    public void thresholdsComeFromTheSettings() {
        String text = "Die NIZZA läuft.";
        assertNotNull(analyze(text).acronym("NIZZA"));
        assertFalse(extractor.analyze(text, new AcronymSettings(4, 60, 3)).acronyms().size() > 0);
    }
}
