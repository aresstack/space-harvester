package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Repair plan step 1: the length stated in a mission reaches the later phases in the one form they read, whatever
 * dash, word or separator the user typed.
 */
public class DeliverableLengthTextTest {

    private static String read(String text) {
        return DeliverableLengthText.describe(DeliverableLengthText.read(text));
    }

    /** The mission of the 17 Nov run (workspace 18fbde60), verbatim; its frozen concept had no length. */
    static final String MISSION_17_NOV = "Erstelle das Konzept für ein kompaktes, vollständig belegtes deutsches "
            + "Praxisbuch \"Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz\" für "
            + "Sicherheitsfachkräfte, Umfang 20-30 Seiten. Vier Hauptthemen: (1) Technologien und Sensortypen, (2) "
            + "reale Arbeitsschutz-Gefährdungen und praktische Wirksamkeit, (3) Recht, Datenschutz und Betriebsrat in "
            + "Deutschland, (4) Implementierung mit Kosten, Grenzen und Praxisbeispielen. Bevorzuge frei zugängliche "
            + "Primärquellen (DGUV, BAuA, EU-OSHA, Gesetze, wissenschaftliche Studien); keine Buchhändler, "
            + "Shopkataloge oder Verkaufsseiten als Nachweise.";

    @Test
    public void theMissionOfThe17NovRunGivesItsLength() {
        assertEquals("20-30 pages", DeliverableLengthText.effective(ResearchDeliverable.unspecified(),
                MISSION_17_NOV, true));
        assertEquals("20-30 pages", read(MISSION_17_NOV.replace("20-30", "20–30")));
    }

    @Test
    public void readsTheVariantsOfARange() {
        assertEquals("20-30 pages", read("Ein Praxisbuch mit 20-30 Seiten über Wearables"));
        assertEquals("20-30 pages", read("Ein Praxisbuch mit 20–30 Seiten über Wearables"));
        assertEquals("20-30 pages", read("Ein Praxisbuch mit 20 — 30 Seiten"));
        assertEquals("20-30 pages", read("Umfang: 20 bis 30 Seiten, Zielgruppe Betriebsräte"));
        assertEquals("20-30 pages", read("Umfang 20 bis zu 30 DIN-A4-Seiten"));
        assertEquals("20-30 pages", read("a practical book of 20 to 30 pages"));
        assertEquals("20-30 pages", read("a practical book of 20-30 pages"));
    }

    @Test
    public void readsSingleLengthsAndWordCounts() {
        assertEquals("25 pages", read("ca. 25 Seiten"));
        assertEquals("25 pages", read("etwa 25 Seiten"));
        assertEquals("8000 words", read("rund 8.000 Wörter"));
        assertEquals("8000 words", read("about 8,000 words"));
        assertEquals("8000 words", read("8 000 Woerter"));
        assertEquals("6000-9000 words", read("6.000–9.000 Wörter"));
        assertEquals("120 pages", read("120 pages"));
        assertEquals("the singular unit as before", "1 pages", read("1 page"));
        assertEquals("20000-30000 words", read("20000-30000 words"));
    }

    @Test
    public void namesNoLengthWhereThereIsNone() {
        assertNull(DeliverableLengthText.read("Wearables am Arbeitsplatz: Technik, Recht, Praxis"));
        assertNull(DeliverableLengthText.read("2,5 Seiten"));
        assertNull(DeliverableLengthText.read(""));
        assertNull(DeliverableLengthText.read(null));
        assertNull(DeliverableLengthText.read("Seite 3 des Leitfadens"));
        assertEquals("", read("4 Hauptthemen und 3 Zielgruppen"));
    }

    /** The five cases of the architect's review of PR #256 (2026-11-21). */
    @Test
    public void aLengthPerPartIsNoBookLength() {
        assertEquals("30 pages", read("Kapitel je 5 Seiten, insgesamt 30 Seiten"));
        assertEquals("20-30 pages", read("2 Seiten je Kapitel; Umfang 20\u201330 Seiten"));
        assertEquals("20-30 pages", read("3 pages per chapter, 20 to 30 pages in total"));
        assertEquals("30 pages", read("je Kapitel 5 Seiten, insgesamt 30 Seiten"));
        assertEquals("25 pages", read("Vorlage 10 Seiten; Umfang ca. 25 Seiten"));
        assertNull("an approximate length per chapter", DeliverableLengthText.read("je Kapitel ca. 5 Seiten"));
    }

    @Test
    public void readsZwischenUndAsARange() {
        assertEquals("20-30 pages", read("zwischen 20 und 30 Seiten"));
        assertEquals("20-30 pages", read("between 20 and 30 pages"));
        assertEquals("30 pages", read("Kapitel 3 und 30 Seiten"));
    }

    @Test
    public void aPageReferenceOrAnAbbreviationIsNoLength() {
        assertNull(DeliverableLengthText.read("DGUV Information 211-042 Seite 3"));
        assertNull(DeliverableLengthText.read("nach 30 s. Messdauer"));
        assertNull(DeliverableLengthText.read("siehe pp. 12"));
        assertEquals("20-30 pages", read("Praxisbuch, 20-30 Seiten, Hinweise aus DGUV Information 211-042 Seite 3"));
    }

    @Test
    public void severalLengthsWithoutAWholeWordGiveNone() {
        assertNull(DeliverableLengthText.read("ein Leitfaden mit 10 Seiten oder ein Buch mit 40 Seiten"));
        assertEquals(java.util.Arrays.asList("10 pages", "40 pages"),
                DeliverableLengthText.candidates("ein Leitfaden mit 10 Seiten oder ein Buch mit 40 Seiten"));
        assertEquals("40 pages", read("Vorlage hatte 10 Seiten; Umfang 40 Seiten"));
        assertEquals("the same length twice is one length", "20-30 pages",
                read("20-30 Seiten. Bitte 20 bis 30 Seiten einhalten."));
        assertEquals("20-30 pages", read("Umfang 20-30 Seiten je nach Bedarf"));
    }

    @Test
    public void aReversedRangeIsOrdered() {
        assertEquals("20-30 pages", read("30 bis 20 Seiten"));
    }

    @Test
    public void theStatedLengthWinsAndTheMissionOnlyFillsAGap() {
        ResearchDeliverable stated = new ResearchDeliverable(40, 50, ResearchDeliverable.LengthUnit.PAGES, null);
        assertEquals("40-50 pages", DeliverableLengthText.effective(stated, "20 bis 30 Seiten", true));
        assertEquals("20-30 pages", DeliverableLengthText.effective(ResearchDeliverable.unspecified(),
                "20 bis 30 Seiten", true));
        assertEquals("", DeliverableLengthText.effective(ResearchDeliverable.unspecified(), "20 bis 30 Seiten",
                false));
        assertEquals("", DeliverableLengthText.effective(null, "ohne Umfang", true));
    }
}
