package com.aresstack.writing4j.memory;

import com.aresstack.writing4j.memory.EvidenceVerdict.Kind;
import com.aresstack.writing4j.memory.LeadDecision.Action;
import com.aresstack.writing4j.memory.LeadDecision.Target;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 7, single test (pure program test): fixed records in, lists and counts out. No model, no search, no
 * stock. No field may claim a question is answered.
 */
public class ResearchMemoryTest {

    static final String Q = "F01";

    static final String TEXT_A = "Der Betriebsrat hat nach § 87 Abs. 1 Nr. 6 BetrVG ein Mitbestimmungsrecht, "
            + "wenn technische Einrichtungen zur Überwachung der Beschäftigten geeignet sind.";
    static final String TEXT_B = "Eine Datenschutz-Folgenabschätzung ist nach Art. 35 DSGVO Pflicht, wenn die "
            + "Verarbeitung voraussichtlich ein hohes Risiko für die Rechte der Betroffenen birgt.";
    static final String TEXT_C = "Für KI-Assistenten im Kundendienst empfehlen die Aufsichtsbehörden eine "
            + "schriftliche Dokumentation der Zwecke, der Datenkategorien und der Löschfristen.";

    static ResearchMemory memoryWithQuestion() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.found("T-recht", "Recht, Datenschutz und Betriebsrat", "", null, "", 1L)
                .withFence(ResearchTopic.Fence.INNEN, "", "", 1L));
        memory.addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat bei KI-Systemen?", "T-recht",
                ResearchQuestion.Origin.NUTZER, 1L));
        return memory;
    }

    static EvidenceVerdict direct(String passage, String source, String url, String text) {
        return direct(Q, passage, source, url, text, 1);
    }

    static EvidenceVerdict direct(String question, String passage, String source, String url, String text,
                                  int round) {
        return EvidenceVerdict.judged(question, passage, source, url, text, Kind.BELEGT,
                quoteOf(text),
                "nennt die Regel", "gemma4:12b", round, 1000L + round);
    }

    /** The first words of {@code text}, up to about 60 characters, ending at a word break. */
    static String quoteOf(String text) {
        return text.substring(0, text.lastIndexOf(' ', 60));
    }

    static EvidenceVerdict lead(String passage, String source, String text) {
        return EvidenceVerdict.judged(Q, passage, source, "", text, Kind.HINWEIS, "", "nur benachbart",
                "gemma4:12b", 1, 1000L);
    }

    static EvidenceVerdict none(String question, String passage, String text, int round) {
        return EvidenceVerdict.judged(question, passage, "s-" + passage, "", text, Kind.KEIN, "", "anderes Thema",
                "gemma4:12b", round, 1000L + round);
    }

    // ------------------------------------------------------------------------- evidence and sources

    @Test
    public void threeDirectPassagesFromOneSourceAreOneSource() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://example.org/a", TEXT_A));
        memory.record(direct("p2", "s1", "https://example.org/a", TEXT_B));
        memory.record(direct("p3", "s1", "https://example.org/a", TEXT_C));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(3, evidence.getDirectCount());
        assertEquals(1, evidence.getIndependentSources());
        assertEquals("Belegt: 3 (1 Quelle) · Weiterführender Hinweis: 0", evidence.summary());
    }

    @Test
    public void theSameTextOnTheSameDomainIsOneEvidenceFromOneSource() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://www.example.org/a", TEXT_A));
        memory.record(direct("p2", "s2", "https://print.example.org/a", "  " + TEXT_A.replace(" ", "\n") + " "));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(1, evidence.getDirectCount());
        assertEquals(1, evidence.getIndependentSources());
    }

    /** The same law on two portals, a press release copied by others: one source group on two domains. */
    @Test
    public void theSameTextOnTwoDomainsIsOneSourceGroupOnTwoDomains() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://www.gesetze-im-internet.de/betrvg/__87.html", TEXT_A));
        memory.record(direct("p2", "s2", "https://www.arbeitsschutzgesetze.com/betrvg-87", TEXT_A));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(1, evidence.getDirectCount());
        assertEquals(1, evidence.getIndependentSources());
        assertEquals(2, evidence.getDistinctDomains());
        assertEquals(Integer.valueOf(2), evidence.counts().get("domains"));
    }

    @Test
    public void differentTextsOnOneDomainAreTwoSourcesOnOneDomain() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://example.org/a", TEXT_A));
        memory.record(direct("p2", "s2", "https://example.org/b", TEXT_B));

        assertEquals(2, memory.evidenceOf(Q).getIndependentSources());
        assertEquals(1, memory.evidenceOf(Q).getDistinctDomains());
    }

    @Test
    public void aTextSharedByTwoSourcesJoinsThemAlthoughTheyAlsoCarryOtherTexts() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://a.example/x", TEXT_A));
        memory.record(direct("p2", "s1", "https://a.example/x", TEXT_B));
        memory.record(direct("p3", "s2", "https://b.example/y", TEXT_B));
        memory.record(direct("p4", "s3", "https://c.example/z", TEXT_C));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(3, evidence.getDirectCount());
        assertEquals("s1 and s2 share a text", 2, evidence.getIndependentSources());
        assertEquals(3, evidence.getDistinctDomains());
    }

    @Test
    public void onlyLeadsAreLeadsNotEvidence() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(lead("p1", "s1", TEXT_B));
        memory.record(lead("p2", "s2", TEXT_C));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(0, evidence.getDirectCount());
        assertEquals(2, evidence.getLeadCount());
        assertEquals("Belegt: 0 · Weiterführender Hinweis: 2", evidence.summary());
        assertTrue(memory.report(), memory.report().contains("Weiterführender Hinweis (2)"));
        assertFalse(memory.report(), memory.report().contains("Kein Beleg gefunden"));
    }

    @Test
    public void aQuestionWithoutEvidenceShowsKeinBelegGefunden() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(none(Q, "p1", TEXT_C, 1));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals("Kein Beleg gefunden", evidence.summary());
        assertEquals(1, evidence.getJudgedPassages());
        assertTrue(memory.report(), memory.report().contains("Kein Beleg gefunden (1 Passagen geprüft)"));
    }

    @Test
    public void directEvidenceAndLeadsAreCountedApart() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://a.example/x", TEXT_A));
        memory.record(direct("p2", "s2", "https://b.example/y", TEXT_B));
        memory.record(lead("p3", "s3", TEXT_C));
        memory.record(lead("p4", "s4", TEXT_A));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(2, evidence.getDirectCount());
        assertEquals("a text that is direct evidence is no lead as well", 1, evidence.getLeadCount());
        assertEquals("Belegt: 2 (2 Quellen) · Weiterführender Hinweis: 1", evidence.summary());
        assertEquals(Integer.valueOf(2), evidence.counts().get("direkt"));
        assertEquals(Integer.valueOf(1), evidence.counts().get("hinweise"));
        assertEquals(Integer.valueOf(4), evidence.counts().get("geprueft"));
    }

    @Test
    public void theLatestVerdictOnAPassageHolds() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "", TEXT_A));
        memory.record(none(Q, "p1", TEXT_A, 2));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(0, evidence.getDirectCount());
        assertEquals(1, evidence.getJudgedPassages());
        assertEquals(Kind.KEIN, memory.verdict(Q, "p1").getKind());
    }

    // ------------------------------------------------------------------------------- quote check

    @Test
    public void aBelegtWhoseQuoteIsNotInThePassageBecomesALeadAndKeepsBothVerdicts() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        EvidenceVerdict invented = EvidenceVerdict.judged(Q, "p1", "s1", "", TEXT_A, Kind.BELEGT,
                "Der Betriebsrat muss jedem KI-System ausdrücklich zustimmen.", "sagt es", "m", 1, 1L);
        memory.record(invented);

        assertEquals(Kind.BELEGT, invented.getJudgedKind());
        assertEquals(Kind.HINWEIS, invented.getKind());
        assertTrue(invented.isDowngraded());
        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(0, evidence.getDirectCount());
        assertEquals(1, evidence.getLeadCount());
        assertEquals(1, evidence.getDowngraded());
    }

    @Test
    public void theQuoteCheckIgnoresLineBreaksHyphenationAndQuoteMarks() {
        String passage = "Der Betriebsrat hat nach § 87 Abs. 1 Nr. 6 BetrVG ein Mitbestim-\nmungsrecht, wenn "
                + "technische Ein­richtungen zur „Überwachung“ geeignet sind.";
        assertTrue(PassageText.holdsQuote(passage, "\"ein Mitbestimmungsrecht, wenn technische Einrichtungen\""));
        assertTrue(PassageText.holdsQuote(passage, "hat nach § 87 Abs. 1 ... zur \"Überwachung\" geeignet"));
        assertTrue("the parts of a shortened quote may come in any order",
                PassageText.holdsQuote(passage, "zur \"Überwachung\" geeignet ... hat nach § 87 Abs. 1"));
        assertFalse("but each at a place of its own",
                PassageText.holdsQuote(passage, "ein Mitbestimmungsrecht ... ein Mitbestimmungsrecht"));
        assertFalse("too short to prove anything", PassageText.holdsQuote(passage, "Betriebsrat"));
        assertFalse("a changed word is no quote",
                PassageText.holdsQuote(passage, "ein Zustimmungsrecht, wenn technische Einrichtungen"));
        assertFalse("no quote, no evidence", PassageText.holdsQuote(passage, ""));
        String fee = "Die Maßnahme ist unwirksam und verursacht erhebliche Kosten von 125 Euro pro Monat dauerhaft.";
        assertFalse("a part starts at a word break",
                PassageText.holdsQuote(fee, "wirksam und verursacht erhebliche Kosten"));
        assertFalse("a number is a whole word", PassageText.holdsQuote(fee, "25 Euro pro Monat dauerhaft"));
        assertFalse("a part ends at a word break", PassageText.holdsQuote(fee, "Die Maßnahme ist unwirk"));
        assertTrue(PassageText.holdsQuote(fee, "unwirksam und verursacht erhebliche Kosten"));
        assertTrue(PassageText.holdsQuote(fee, "Die Maßnahme ist unwirksam ... 125 Euro pro Monat"));
        assertFalse("every part of a shortened quote needs 6 letters",
                PassageText.holdsQuote(passage, "Der ... ein ... wenn technische Einrichtungen"));
    }

    @Test
    public void aNumberInTheReasonMustStandInTheQuote() {
        String passage = "Im Pilotprojekt sanken die meldepflichtigen Unfälle um 30 Prozent, die Beinaheunfälle "
                + "um 1.500 Fälle im Jahr. Die Westen kosten 2,5 Tausend Euro je Stück.";
        String quote = "Im Pilotprojekt sanken die meldepflichtigen Unfälle um 30 Prozent";
        EvidenceVerdict held = EvidenceVerdict.judged(Q, "p1", "s1", "", passage, Kind.BELEGT, quote,
                "Die Unfälle sanken im Pilotprojekt um 30 %.", "m", 1, 1L);
        assertEquals(Kind.BELEGT, held.getKind());
        EvidenceVerdict invented = EvidenceVerdict.judged(Q, "p1", "s1", "", passage, Kind.BELEGT, quote,
                "Die Unfälle sanken um 40 %.", "m", 1, 1L);
        assertEquals("a number of the reason that the quote does not hold", Kind.HINWEIS, invented.getKind());
        assertEquals(Kind.BELEGT, invented.getJudgedKind());
        assertTrue(invented.isDowngraded());
        EvidenceVerdict elsewhere = EvidenceVerdict.judged(Q, "p1", "s1", "", passage, Kind.BELEGT, quote,
                "Die Unfälle sanken um 30 %, die Beinaheunfälle um 1500 Fälle.", "m", 1, 1L);
        assertEquals("1500 stands in the passage but not in the quote", Kind.HINWEIS, elsewhere.getKind());
        assertEquals(Kind.HINWEIS, EvidenceVerdict.judged(Q, "p1", "s1", "", passage, Kind.HINWEIS, "",
                "Es nennt 40 %.", "m", 1, 1L).getKind());

        assertTrue(PassageText.numbersHeld("um 1500 Fälle", "die Beinaheunfälle um 1.500 Fälle im Jahr"));
        assertTrue(PassageText.numbersHeld("2.5 Tausend Euro", "kosten 2,5 Tausend Euro je Stück"));
        assertTrue("no number, nothing to hold", PassageText.numbersHeld("Die Westen sind teuer.", ""));
        assertFalse(PassageText.numbersHeld("§ 87 Abs. 1 Nr. 6 BetrVG", "nach § 87 Abs. 1 BetrVG"));
        assertFalse("a decimal is no thousands", PassageText.numbersHeld("25 Euro", "2,5 Euro"));
        assertFalse("two numbers stay two", PassageText.numbersHeld("2023.30", "im Jahr 2023 30 Unfälle"));
    }

    @Test
    public void layoutAndQuotingDoNotCountButCollagesDo() {
        String broken = "Die Ampel-\nanlage der Verkehrs-\r\nLeitzentrale meldet jeden Stau sofort an die Polizei.";
        assertTrue("hyphen at a line end kept",
                PassageText.holdsQuote(broken, "Die Ampel-anlage der Verkehrsleitzentrale"));
        assertTrue("hyphen at a line end dropped",
                PassageText.holdsQuote(broken, "Die Ampelanlage der Verkehrs-Leitzentrale"));
        assertTrue("capital after a line-end hyphen",
                PassageText.holdsQuote(broken, "der Verkehrs-Leitzentrale meldet jeden Stau"));
        String sentence = "Nach Angaben der Stadt \u2013 die Zahl der Unfälle sank im ersten Jahr deutlich.";
        assertTrue("capital letter at the start of a quote",
                PassageText.holdsQuote(sentence, "Die Zahl der Unfälle sank im ersten Jahr deutlich."));
        String list = "Gefahren entstehen insbesondere durch:\n- den fließenden Verkehr,\n\u2022 Ein- und Ausfahrten "
                + "der Baufahrzeuge,\n- Lärm und Abgase.";
        assertTrue("list marks at a line start are layout", PassageText.holdsQuote(list,
                "durch: den fließenden Verkehr, Ein- und Ausfahrten der Baufahrzeuge"));
        String report = "Die Sensoren übertragen die Messwerte an die Leitstelle, wo sie der Auswertung und der "
                + "Sicherung der Daten dienen.";
        assertTrue("a short last part of six letters", PassageText.holdsQuote(report,
                "Die Sensoren übertragen die Messwerte an die Leitstelle, wo sie [...] dienen."));

        String three = "Tempo 30 senkt den Lärm. Anwohner schlafen besser. Die Zahl der Unfälle sinkt um 2,5 Prozent.";
        assertTrue("a short sentence left out without an ellipsis, in order (component 6, 23.11.)",
                PassageText.holdsQuote(three, "Tempo 30 senkt den Lärm. Die Zahl der Unfälle sinkt"));
        assertTrue("the same with an ellipsis",
                PassageText.holdsQuote(three, "Tempo 30 senkt den Lärm. [...] Die Zahl der Unfälle sinkt"));
        assertFalse("sentences swapped",
                PassageText.holdsQuote(three, "Anwohner schlafen besser. Tempo 30 senkt den Lärm."));
        assertFalse("numbers keep their signs",
                PassageText.holdsQuote(three, "Die Zahl der Unfälle sinkt um 25 Prozent."));
        assertTrue(PassageText.holdsQuote(three, "die Zahl der Unfälle sinkt um 2,5 Prozent"));
        assertTrue("the decisive sentence first, an earlier one after the ellipsis", PassageText.holdsQuote(three,
                "Die Zahl der Unfälle sinkt um 2,5 Prozent. [...] Tempo 30 senkt den Lärm."));
        assertFalse("a word changed in a part", PassageText.holdsQuote(three,
                "Die Zahl der Unfälle sinkt um 2,5 Prozent. [...] Tempo 30 senkt den Verkehr."));
    }

    /** Stock test of component 6 on 23.11.2026: the model leaves a few words out without marking it. */
    @Test
    public void shortUnmarkedGapsHoldButNotOnesThatChangeTheMeaning() {
        String rule = "Radfahrer müssen auf gemeinsamen Geh- und Radwegen, wie es die Verordnung verlangt, auf "
                + "Fußgänger Rücksicht nehmen und dürfen dort nicht schneller als 10 km/h fahren, außer nachts.";
        assertTrue("a clause left out", PassageText.holdsQuote(rule,
                "Radfahrer müssen auf gemeinsamen Geh- und Radwegen auf Fußgänger Rücksicht nehmen"));
        assertTrue("two gaps", PassageText.holdsQuote(rule,
                "Radfahrer müssen auf gemeinsamen Radwegen, wie es die Verordnung verlangt, auf Fußgänger Rücksicht"));
        assertFalse("a left-out negation", PassageText.holdsQuote(rule,
                "auf Fußgänger Rücksicht nehmen und dürfen dort schneller als 10 km/h fahren"));
        assertFalse("a left-out number", PassageText.holdsQuote(rule,
                "dürfen dort nicht schneller als km/h fahren, außer nachts"));
        assertFalse("a run shorter than four words", PassageText.holdsQuote(rule,
                "Radfahrer müssen Rücksicht nehmen und dürfen dort nicht schneller als 10 km/h fahren"));
        assertFalse("a changed word", PassageText.holdsQuote(rule,
                "Radfahrer müssen auf gemeinsamen Geh- und Radwegen auf Autofahrer Rücksicht nehmen"));
        String longer = "Die Stadt prüft jede neue Fahrradstraße zuerst ein ganzes Jahr lang mit Zählstellen an allen "
                + "Kreuzungen und erst danach entscheidet der Rat über die dauerhafte Einrichtung der Straße.";
        assertFalse("a gap of more than eight words", PassageText.holdsQuote(longer,
                "Die Stadt prüft jede neue Fahrradstraße erst danach entscheidet der Rat"));
    }

    @Test
    public void punctuationBetweenWordsDoesNotCountWordsAndWordBreaksDo() {
        String rule = "Die Daten (z. B. der Standort) werden nach 30 Tagen gelöscht; Ausnahmen regelt die "
                + "Betriebsvereinbarung.";
        assertTrue("brackets and commas", PassageText.holdsQuote(rule,
                "Die Daten, z.B. der Standort, werden nach 30 Tagen gelöscht."));
        assertTrue("a colon for a semicolon", PassageText.holdsQuote(rule,
                "nach 30 Tagen gelöscht: Ausnahmen regelt die Betriebsvereinbarung"));
        assertFalse("a word break that is not in the passage", PassageText.holdsQuote(rule,
                "Ausnahmen regelt die Betriebs vereinbarung"));
        assertFalse("another number", PassageText.holdsQuote(rule, "werden nach 3 0 Tagen gelöscht"));
    }

    // ------------------------------------------------------------------------------------ errors

    @Test
    public void aFailedJudgementIsAnErrorNotAKeinAndIsOfferedAgain() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(EvidenceVerdict.failed(Q, "p1", "s1", "", TEXT_A, "answer was no JSON", "gemma4:e2b", 1, 1L));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(1, evidence.getErrors());
        assertEquals("a failed call is no judged passage", 0, evidence.getJudgedPassages());
        assertFalse(memory.isJudged(Q, "p1"));
        assertEquals(1, memory.toRetry().size());
        assertEquals("p1", memory.toRetry().get(0).getPassageId());
        assertTrue(memory.report(), memory.report().contains("Fehler: 1 Passagen, werden erneut geprüft"));

        memory.record(direct("p1", "s1", "", TEXT_A));
        assertTrue(memory.isJudged(Q, "p1"));
        assertTrue(memory.toRetry().isEmpty());
        assertEquals(0, memory.evidenceOf(Q).getErrors());
    }

    @Test
    public void aFailedJudgementOfAMergedQuestionIsNotOfferedOnceTheSurvivorJudgedThePassage() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addQuestion(ResearchQuestion.open("N01", "Wann darf der Betriebsrat bei KI mitbestimmen?", "T-recht",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.record(EvidenceVerdict.failed("N01", "p1", "s1", "", TEXT_A, "timeout", "m", 1, 1L));
        memory.updateQuestion(memory.question(Q).withMerged(Collections.singletonList(memory.question("N01")), 3L));
        assertEquals("the merged question's error counts for the survivor", 1, memory.toRetry().size());

        memory.record(direct(Q, "p1", "s1", "", TEXT_A, 2));
        assertTrue(memory.isJudged(Q, "p1"));
        assertTrue("a FEHLER that a later verdict replaced is not offered again", memory.toRetry().isEmpty());
    }

    @Test
    public void aVerdictOnTheSameTextIsOfferedForReuse() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://a.example/x", TEXT_A));
        memory.record(EvidenceVerdict.failed(Q, "p2", "s2", "", TEXT_B, "timeout", "m", 1, 1L));

        EvidenceVerdict reused = memory.verdictOnSameText(Q, TEXT_A.replace(" ", "  "));
        assertEquals("p1", reused.getPassageId());
        assertNull("an error is never reused", memory.verdictOnSameText(Q, TEXT_B));
        assertNull(memory.verdictOnSameText(Q, TEXT_C));
    }

    @Test
    public void aTextKeyCutOnABlankStillFindsItsVerdict() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append("Messwerte ");
        }
        String key = PassageText.key(text.toString());
        assertEquals(PassageText.KEY_CHARS - 1, key.length());
        assertEquals(key.trim(), key);
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://a.example/x", text.toString()));
        assertEquals(key, direct("p1", "s1", "", text.toString()).getTextKey());
        assertEquals("p1", memory.verdictOnSameText(Q, text.toString()).getPassageId());
    }

    // ---------------------------------------------------------------------------------- questions

    @Test
    public void aFoundQuestionWithoutATopicIsKept() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addQuestion(ResearchQuestion.open("N01", "Wie gehen Gewerkschaften mit KI-Monitoring um?", "",
                ResearchQuestion.Origin.FINDER, 3L));

        assertEquals(2, memory.questions().size());
        ResearchQuestion held = memory.question("N01");
        assertEquals(ResearchQuestion.Origin.FINDER, held.getOrigin());
        assertEquals("", held.getTopicId());
        assertEquals("Kein Beleg gefunden", memory.evidenceOf("N01").summary());
    }

    /** Architect review of 21.11.: a question worded like one held keeps its own origin as a merged record. */
    @Test
    public void theSameWordingStandsOnceAndKeepsItsOrigin() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addTopic(ResearchTopic.found("T-gew", "Gewerkschaftliche Praxis", "", null, "gemma4:e2b", 2L));
        ResearchQuestion again = ResearchQuestion.open("N02", "welche Rechte hat der Betriebsrat bei KI-Systemen",
                "T-gew", ResearchQuestion.Origin.FINDER, 2L);

        ResearchQuestion stands = memory.addQuestion(again);

        assertEquals(Q, stands.getId());
        assertEquals(Collections.singletonList("N02"), stands.getMergedFrom());
        assertEquals(1, memory.questions().size());
        ResearchQuestion kept = memory.question("N02");
        assertEquals(ResearchQuestion.Origin.FINDER, kept.getOrigin());
        assertEquals("T-gew", kept.getTopicId());
        assertEquals(Q, memory.currentQuestion("N02").getId());
        assertSame("the same question again changes nothing", memory.question(Q),
                memory.addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat bei KI-Systemen?",
                        "T-recht", ResearchQuestion.Origin.NUTZER, 5L)));
    }

    @Test
    public void unknownIdsAreRefused() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        try {
            memory.record(none("F99", "p1", TEXT_A, 1));
            fail("verdict on an unknown question");
        } catch (IllegalArgumentException expected) {
            assertNull(memory.evidenceOf("F99"));
        }
        try {
            memory.addQuestion(ResearchQuestion.open("F02", "Was kostet ein Sensor?", "T-unbekannt",
                    ResearchQuestion.Origin.NUTZER, 1L));
            fail("question on an unknown topic");
        } catch (IllegalArgumentException expected) {
            assertNull(memory.question("F02"));
        }
        try {
            memory.record(new SearchRecord("S1", "Betriebsrat KI", Arrays.asList("F99"), null, "", "PLANER", 1,
                    null, 1L));
            fail("search for an unknown question");
        } catch (IllegalArgumentException expected) {
            assertTrue(memory.searches().isEmpty());
        }
        try {
            new SearchRecord("S2", "Betriebsrat KI", null, null, "", "PLANER", 1, null, 1L);
            fail("a search names a question or a topic");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("names no question and no topic"));
        }
        try {
            memory.record(new LeadDecision(1, Target.FRAGE, "F99", Action.ZURUECKSTELLEN, "", null, "", 1L));
            fail("decision about an unknown question");
        } catch (IllegalArgumentException expected) {
            assertTrue(memory.decisions().isEmpty());
        }
    }

    @Test
    public void aSetAsideQuestionLeavesTheOpenListButKeepsItsEvidence() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(lead("p1", "s1", TEXT_B));
        memory.record(new LeadDecision(4, Target.FRAGE, Q, Action.ZURUECKSTELLEN, "zwei Strategien ohne Ertrag",
                memory.evidenceOf(Q).counts(), "gemma4:12b", 9L));
        memory.updateQuestion(memory.question(Q).withState(ResearchQuestion.State.ZURUECKGESTELLT, 9L));

        assertTrue(memory.openQuestions().isEmpty());
        assertEquals(1, memory.questions().size());
        assertEquals(1, memory.evidenceOf(Q).getLeadCount());
        assertEquals(Action.ZURUECKSTELLEN, memory.decisions().get(0).getAction());
        assertEquals(Integer.valueOf(1), memory.decisions().get(0).getCounts().get("hinweise"));
        assertTrue(memory.report(), memory.report().contains("(zurückgestellt)"));
    }

    @Test
    public void roundsAndSearchesWithoutNewEvidenceCountFromTheLastNewText() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct(Q, "p1", "s1", "", TEXT_A, 2));
        memory.record(none(Q, "p2", TEXT_B, 3));
        memory.record(direct(Q, "p3", "s2", "", TEXT_A, 4));
        memory.record(none(Q, "p4", TEXT_C, 5));
        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals("the same text in round 4 is nothing new", 2, evidence.getLastRoundWithNewEvidence());
        assertEquals("rounds 3, 4 and 5 judged passages without a new text", 3,
                evidence.getRoundsWithoutNewEvidence());

        memory.record(new SearchRecord("S1", "Betriebsrat KI Mitbestimmung", Arrays.asList(Q), null, "Wortwahl",
                "PLANER", 2, new SearchRecord.Yield(4, 30, 1, 0), 1L));
        memory.record(new SearchRecord("S2", "BetrVG 87 KI", Arrays.asList(Q), null, "Wortwahl", "PLANER", 3,
                new SearchRecord.Yield(3, 12, 0, 0), 2L));
        SearchRecord running = new SearchRecord("S3", "Mitbestimmung Algorithmus", Arrays.asList(Q), null,
                "andere Quellenart", "LEITUNG", 4, null, 3L);
        memory.record(running);
        assertEquals("a running search does not count", 1, memory.evidenceOf(Q).getSearchesWithoutNewEvidence());
        memory.record(running.withYield(new SearchRecord.Yield(2, 8, 0, 1), 4L));
        assertEquals(2, memory.evidenceOf(Q).getSearchesWithoutNewEvidence());
        assertEquals(3, memory.searches().size());
        assertEquals(1, memory.searches().get(2).getYield().getNewTopics());
    }

    @Test
    public void aMergedQuestionsEvidenceCountsForTheQuestionItWasMergedInto() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addQuestion(ResearchQuestion.open("N01", "Wann darf der Betriebsrat bei KI mitbestimmen?", "T-recht",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.record(direct(Q, "p1", "s1", "https://a.example/x", TEXT_A, 1));
        memory.record(direct("N01", "p2", "s2", "https://b.example/y", TEXT_B, 2));
        memory.record(new SearchRecord("S1", "BetrVG KI", Arrays.asList("N01"), null, "", "PLANER", 2,
                new SearchRecord.Yield(1, 5, 0, 0), 1L));

        ResearchQuestion merged = memory.question(Q).withMerged(Collections.singletonList(memory.question("N01")),
                3L);
        memory.updateQuestion(merged);

        assertEquals(1, memory.questions().size());
        assertEquals(Q, memory.currentQuestion("N01").getId());
        assertEquals(2, memory.evidenceOf(Q).getDirectCount());
        assertEquals(2, memory.evidenceOf(Q).getIndependentSources());
        assertEquals(1, memory.evidenceOf(Q).getSearchesWithoutNewEvidence());
        assertTrue("judged for the merged question is judged for the survivor", memory.isJudged(Q, "p2"));

        memory.updateQuestion(memory.question(Q).withoutMerged(Collections.singletonList("N01"), 4L));
        assertEquals("a merge can be taken back", 2, memory.questions().size());
        assertEquals(1, memory.evidenceOf(Q).getDirectCount());
        assertEquals(1, memory.evidenceOf("N01").getDirectCount());
    }

    @Test
    public void aQuestionCannotBeMergedIntoItself() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addQuestion(ResearchQuestion.open("N01", "Wann darf der Betriebsrat bei KI mitbestimmen?", "T-recht",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.updateQuestion(memory.question(Q).withMerged(Collections.singletonList(memory.question("N01")), 3L));
        try {
            memory.updateQuestion(memory.question("N01").withMerged(Collections.singletonList(memory.question(Q)),
                    4L));
            fail("a cycle of merges");
        } catch (IllegalArgumentException expected) {
            assertEquals(Q, memory.currentQuestion("N01").getId());
        }
    }

    // ------------------------------------------------------------------------------------- topics

    @Test
    public void topicsKeepTheirIdWhenMergedAndCountOverAllTheirQuestions() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        ResearchTopic union = ResearchTopic.found("T-gew", "Gewerkschaftliche Praxis", "Wie Gewerkschaften KI "
                        + "begleiten",
                Collections.singletonList(new ResearchTopic.Finding("s7", "p7", "Die IG Metall")), "gemma4:e2b", 2L);
        ResearchTopic works = ResearchTopic.found("T-bv", "Betriebsvereinbarungen zu KI", "",
                Arrays.asList(new ResearchTopic.Finding("s8", "p8", "Musterbetriebsvereinbarung"),
                        new ResearchTopic.Finding("s7", "p7", "Die IG Metall")), "gemma4:e2b", 2L);
        memory.addTopic(union);
        memory.addTopic(works);
        memory.addQuestion(ResearchQuestion.open("N01", "Was regeln Betriebsvereinbarungen zu KI?", "T-bv",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.addQuestion(ResearchQuestion.open("N02", "Wie begleiten Gewerkschaften KI-Einführungen?", "T-gew",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.record(direct("N01", "p1", "s1", "https://a.example/x", TEXT_A, 2));
        memory.record(direct("N02", "p2", "s2", "https://b.example/y", TEXT_B, 2));

        memory.updateTopic(union.withMerged(Collections.singletonList(works), "gemma4:12b", 3L)
                .withFence(ResearchTopic.Fence.INNEN, "betrifft Mitbestimmung bei KI", "gemma4:12b", 3L));

        assertEquals(Arrays.asList("T-recht", "T-gew"), ids(memory));
        assertEquals("T-gew", memory.currentTopic("T-bv").getId());
        TopicEvidence evidence = memory.evidenceOfTopic("T-gew");
        assertEquals(Arrays.asList("N01", "N02"), evidence.getQuestionIds());
        assertEquals(2, evidence.getDirectCount());
        assertEquals(2, evidence.getIndependentSources());
        assertEquals("the same place found twice counts once", 2, evidence.getFindings());
        assertEquals(ResearchTopic.Fence.INNEN, memory.topic("T-gew").getFence());
        assertEquals(ResearchTopic.Fence.INNEN, memory.topic("T-recht").getFence());
    }

    private static java.util.List<String> ids(ResearchMemory memory) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (ResearchTopic topic : memory.topics()) {
            ids.add(topic.getId());
        }
        return ids;
    }

    // --------------------------------------------------------------------------- no completeness claim

    @Test
    public void noFieldClaimsAQuestionIsAnswered() {
        Pattern claim = Pattern.compile("(?i).*(answer|beantwort|complete|vollst|closed|done|finish|resolved|"
                + "covered|abgeschlossen|erledigt).*");
        for (Class<?> type : Arrays.asList(QuestionEvidence.class, ResearchQuestion.class, TopicEvidence.class,
                ResearchMemory.class)) {
            for (Method method : type.getMethods()) {
                assertFalse(type.getSimpleName() + "." + method.getName(), claim.matcher(method.getName()).matches());
            }
        }
        for (ResearchQuestion.State state : ResearchQuestion.State.values()) {
            assertFalse(state.name(), claim.matcher(state.name()).matches());
        }
        assertEquals(2, ResearchQuestion.State.values().length);
    }

    // ------------------------------------------------------------------------------------- domains

    @Test
    public void theDomainIsTheRegistrableName() {
        assertEquals("bmas.de", SourceDomain.of("https://www.bmas.de/DE/Arbeit/x.html"));
        assertEquals("bmas.de", SourceDomain.of("https://service.bmas.de/print"));
        assertEquals("ico.org.uk", SourceDomain.of("https://ico.org.uk/for-organisations/"));
        assertEquals("bka.gv.at", SourceDomain.of("https://www.ris.bka.gv.at/x"));
        assertEquals("bsi.bund.de", SourceDomain.of("https://www.bsi.bund.de/DE/x"));
        assertEquals("bmas.bund.de", SourceDomain.of("https://bmas.bund.de/y"));
        assertEquals("ldi.nrw.de", SourceDomain.of("https://www.ldi.nrw.de/a"));
        assertEquals("arbeitsschutz.nrw.de", SourceDomain.of("https://www.arbeitsschutz.nrw.de/b"));
        assertEquals("lgl.bayern.de", SourceDomain.of("https://www.lgl.bayern.de/c"));
        assertEquals("osha.europa.eu", SourceDomain.of("https://osha.europa.eu/de"));
        assertEquals("eur-lex.europa.eu", SourceDomain.of("https://eur-lex.europa.eu/legal-content/x"));
        assertEquals("seco.admin.ch", SourceDomain.of("https://www.seco.admin.ch/z"));
        assertEquals("bund.de", SourceDomain.of("https://www.bund.de/"));
        assertEquals("bsi.bund.de", SourceDomain.of("https://web.bsi.bund.de/x"));
        assertEquals("example.org", SourceDomain.of("example.org/a"));
        assertEquals("", SourceDomain.of(""));
        assertEquals("", SourceDomain.of("not a url"));
    }

    static Map<String, Integer> counts(int direct) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("direkt", direct);
        return counts;
    }

    // ------------------------------------------------------------------------- issues #285 and #286

    /** #286: judging the same passage again in a later round does not reset the standstill count. */
    @Test
    public void aPassageJudgedAgainKeepsTheRoundItWasFirstFoundIn() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct(Q, "p1", "s1", "", TEXT_A, 1));
        memory.record(none(Q, "p2", TEXT_B, 2));
        assertEquals(1, memory.evidenceOf(Q).getLastRoundWithNewEvidence());
        assertEquals(1, memory.evidenceOf(Q).getRoundsWithoutNewEvidence());

        memory.record(direct(Q, "p1", "s1", "", TEXT_A, 3));
        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals("p1 was found in round 1, reading it again is nothing new", 1,
                evidence.getLastRoundWithNewEvidence());
        assertEquals("rounds 2 and 3 brought no new text", 2, evidence.getRoundsWithoutNewEvidence());
        assertEquals(1, evidence.getDirectCount());
    }

    /** #286: the first round survives reopening the memory (it is rebuilt from the log). */
    @Test
    public void theFirstRoundSurvivesARestore() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.restore(direct(Q, "p1", "s1", "", TEXT_A, 1));
        memory.restore(none(Q, "p2", TEXT_B, 2));
        memory.restore(direct(Q, "p1", "s1", "", TEXT_A, 3));
        assertEquals(1, memory.evidenceOf(Q).getLastRoundWithNewEvidence());
        assertEquals(2, memory.evidenceOf(Q).getRoundsWithoutNewEvidence());
    }

    /** #285: a question of topic B merged into a question of topic A still counts, with its evidence, for B. */
    @Test
    public void aQuestionMergedAcrossTopicsCountsForBothTopics() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addTopic(ResearchTopic.found("T-dsgvo", "Datenschutz-Folgenabschätzung", "", null, "", 1L).withFence(ResearchTopic.Fence.INNEN, "", "", 1L));
        memory.addQuestion(ResearchQuestion.open("N01", "Wann braucht ein KI-System eine Folgenabschätzung?",
                "T-dsgvo", ResearchQuestion.Origin.NUTZER, 2L));
        memory.record(direct("N01", "p2", "s2", "https://b.example/y", TEXT_B, 1));
        assertEquals(1, memory.evidenceOfTopic("T-dsgvo").getDirectCount());

        memory.updateQuestion(memory.question(Q).withMerged(Collections.singletonList(memory.question("N01")), 3L));

        TopicEvidence b = memory.evidenceOfTopic("T-dsgvo");
        assertEquals("the merged question's topic keeps it, resolved to the survivor", Arrays.asList(Q),
                b.getQuestionIds());
        assertEquals("its evidence is still there", 1, b.getDirectCount());
        TopicEvidence a = memory.evidenceOfTopic("T-recht");
        assertEquals(Arrays.asList(Q), a.getQuestionIds());
        assertEquals("counted once for the survivor's topic", 1, a.getDirectCount());
    }
}
