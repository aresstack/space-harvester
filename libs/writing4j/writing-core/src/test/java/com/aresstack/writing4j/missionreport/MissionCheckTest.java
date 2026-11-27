package com.aresstack.writing4j.missionreport;

import com.aresstack.writing4j.manuscript.ManuscriptCitation;
import com.aresstack.writing4j.manuscript.SectionRevision;
import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.memory.ResearchMemory;
import com.aresstack.writing4j.memory.ResearchQuestion;
import com.aresstack.writing4j.memory.ResearchTopic;
import com.aresstack.writing4j.sentencecheck.SentenceCheck;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Attribution;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Jurisdiction;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Kind;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Verdict;
import com.aresstack.writing4j.sentencecheck.SentenceVerdicts;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The mission report on a small self-written book about city traffic: what it lists and what it never claims. */
public class MissionCheckTest {

    static final String P1 = "In den Tempo-30-Zonen der Stadt sank die Zahl der Unfälle mit Verletzten im ersten Jahr "
            + "um 25 Prozent. Die Verwaltung führt das auf die kürzeren Bremswege zurück.";
    static final String P2 = "Geschützte Radwege trennen den Rad- vom Autoverkehr durch Poller und Bordsteine. Seit "
            + "dem "
            + "Umbau melden die Radfahrer weniger Beinaheunfälle.";
    static final String P3 = "Der Gemeinderat berät im Herbst über neue Gebühren für das Anwohnerparken in der "
            + "Innenstadt.";
    static final String P4 = "Über Tempo 30 auf Hauptstraßen entscheidet in Österreich die Bezirkshauptmannschaft nach "
            + "einer Prüfung der Verkehrssicherheit.";
    static final String P5 = "An drei Grundschulen sichern ehrenamtliche Lotsen die Schulwege am Morgen.";
    static final String P6 = "Lieferwagen halten in der Altstadt oft in zweiter Reihe, weil Ladezonen fehlen.";
    static final String P7 = "Elterntaxis vor den Schulen gefährden Kinder, die zu Fuß kommen.";

    static final String S1 = "In Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten um 25 Prozent [1].";
    static final String S2 = "Lieferwagen halten oft in zweiter Reihe [2].";
    static final String S3 = "Hauptstraßen bleiben meist bei Tempo 50.";
    static final String S4 = "Die Stadt plant weitere Zonen [1, 3].";
    static final String S5 = "Geschützte Radwege trennen Rad- und Autoverkehr [1].";
    static final String S6 = "Schulwege werden mit Lotsen gesichert [1].";

    static final long TIME = 1_763_800_000_000L;

    static ResearchMemory memory() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.found("K1", "Tempo 30", "", null, "", TIME).withFence(ResearchTopic.Fence.INNEN, "", "", TIME));
        memory.addTopic(ResearchTopic.found("K2", "Radwege", "", null, "", TIME).withFence(ResearchTopic.Fence.INNEN, "", "", TIME));
        memory.addTopic(ResearchTopic.found("K3", "Parken", "", null, "", TIME).withFence(ResearchTopic.Fence.INNEN, "", "", TIME));
        ResearchTopic schools = ResearchTopic.found("T1", "Schulwege", "", Collections.singletonList(
                new ResearchTopic.Finding("s-schule", "p5", "ehrenamtliche Lotsen")), "gemma4:e2b", TIME);
        memory.addTopic(schools);
        memory.addTopic(found("T2", "Lieferverkehr", "p6"));
        memory.addTopic(found("T3", "Fahrradhelme im Ausland", "p8"));
        memory.addTopic(found("T4", "E-Scooter", "p9"));
        memory.addTopic(found("T5", "Carsharing", "p10"));
        memory.addTopic(found("T6", "Ladezonen", "p11"));
        ResearchTopic parentsTaxis = found("T7", "Elterntaxis", "p7");
        memory.addTopic(parentsTaxis);
        memory.updateTopic(schools.withFence(ResearchTopic.Fence.INNEN, "Schulwege sind Stadtverkehr", "m", TIME)
                .withMerged(Collections.singletonList(parentsTaxis), "m", TIME));
        fence(memory, "T2", ResearchTopic.Fence.INNEN, "");
        fence(memory, "T3", ResearchTopic.Fence.AUSSEN, "anderer Rechtsraum");
        fence(memory, "T4", ResearchTopic.Fence.UNKLAR, "Grenzfall");
        fence(memory, "T5", ResearchTopic.Fence.INNEN, "");
        fence(memory, "T6", ResearchTopic.Fence.INNEN, "");

        memory.addQuestion(question("F1", "Wie wirkt Tempo 30 auf die Zahl der Unfälle?", "K1"));
        memory.addQuestion(question("F2", "Welche Radwege trennen Rad- und Autoverkehr sicher?", "K2"));
        memory.addQuestion(question("F3", "Was kostet Anwohnerparken?", "K3"));
        memory.addQuestion(question("F4", "Wer entscheidet über Tempo 30 auf Hauptstraßen?", "K1"));
        memory.record(EvidenceVerdict.judged("F1", "p1", "s-amt", "https://amt.example/tempo30", P1,
                EvidenceVerdict.Kind.BELEGT, "sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 Prozent",
                "nennt den Rückgang", "gemma4:12b", 1, TIME));
        memory.record(EvidenceVerdict.judged("F2", "p2", "s-verband", "https://verband.example/radwege", P2,
                EvidenceVerdict.Kind.BELEGT, "trennen den Rad- vom Autoverkehr durch Poller und Bordsteine",
                "nennt die Trennung", "gemma4:12b", 1, TIME));
        memory.record(EvidenceVerdict.judged("F3", "p3", "s-rat", "https://rat.example/parken", P3,
                EvidenceVerdict.Kind.HINWEIS, "", "nur eine Beratung", "gemma4:12b", 1, TIME));
        memory.record(EvidenceVerdict.judged("F4", "p4", "s-kanzlei", "https://kanzlei.example/at", P4,
                EvidenceVerdict.Kind.KEIN, "", "Österreich", "gemma4:12b", 1, TIME));
        return memory;
    }

    static ResearchTopic found(String id, String name, String passageId) {
        return ResearchTopic.found(id, name, "", Collections.singletonList(
                new ResearchTopic.Finding("s-" + id, passageId, name)), "gemma4:e2b", TIME);
    }

    static void fence(ResearchMemory memory, String id, ResearchTopic.Fence fence, String reason) throws Exception {
        memory.updateTopic(memory.topic(id).withFence(fence, reason, "gemma4:12b", TIME));
    }

    static ResearchQuestion question(String id, String text, String topicId) {
        return ResearchQuestion.open(id, text, topicId, ResearchQuestion.Origin.NUTZER, TIME);
    }

    static List<BookSection> sections() {
        return Arrays.asList(
                new BookSection("s1", "Tempo 30 in der Stadt", 120, S1 + " " + S2 + " " + S3 + "\n\n" + S4,
                        Arrays.asList(new BookSection.Citation(1, "p1", "s-amt", P1),
                                new BookSection.Citation(2, "p6", "s-hersteller", P6),
                                new BookSection.Citation(3, "p4", "s-kanzlei", P4))),
                new BookSection("s2", "Radwege", 80, S5,
                        Collections.singletonList(new BookSection.Citation(1, "p2", "s-verband", P2))),
                new BookSection("s3", "Schulwege", 60, S6,
                        Collections.singletonList(new BookSection.Citation(1, "p5", "s-schule", P5))));
    }

    /** The sentence check's findings by the sentence without markers. */
    static SentenceLookup lookup() {
        Map<String, SentenceLookup.State> states = new HashMap<String, SentenceLookup.State>();
        states.put(SentenceVerdict.plain(S1), new SentenceLookup.State(Verdict.GEDECKT, false, false));
        states.put(SentenceVerdict.plain(S2), new SentenceLookup.State(Verdict.TEILWEISE, false, false));
        states.put(SentenceVerdict.plain(S4), new SentenceLookup.State(Verdict.NICHT_GEDECKT, false, true));
        states.put(SentenceVerdict.plain(S5), new SentenceLookup.State(Verdict.GEDECKT, true, false));
        return (sentence, cited) -> {
            SentenceLookup.State state = states.get(SentenceVerdict.plain(sentence));
            return state == null ? SentenceLookup.State.UNCHECKED : state;
        };
    }

    static List<Placement> placements() {
        return Arrays.asList(
                Placement.ownSection("K1", "s1", null),
                Placement.ownSection("K2", "s2", null),
                Placement.notTaken("K3", "nur ein Hinweis"),
                Placement.ownSection("T1", "s3", null),
                Placement.notTaken("T7", "steht unter Schulwege"),
                Placement.mergedInto("T2", "s1", "zu klein für einen eigenen Abschnitt", null),
                Placement.notTaken("T6", "zu wenig Stoff"),
                Placement.notTaken("T99", "unbekannt"));
    }

    static Map<String, MissionCheck.SourceInfo> sources() {
        Map<String, MissionCheck.SourceInfo> sources = new LinkedHashMap<String, MissionCheck.SourceInfo>();
        sources.put("s-amt", new MissionCheck.SourceInfo("BEHOERDE_UNFALLVERSICHERUNG", "DE"));
        sources.put("s-hersteller", new MissionCheck.SourceInfo("HERSTELLER_HANDEL", "DE"));
        sources.put("s-kanzlei", new MissionCheck.SourceInfo("KANZLEI_BERATUNG", "AT"));
        sources.put("s-verband", new MissionCheck.SourceInfo("verband", "de"));
        return sources;
    }

    static List<MissionCheck.ContradictionRecord> contradictions() {
        return Arrays.asList(
                new MissionCheck.ContradictionRecord("W1", "FRAGE", "F1", "https://amt.example/tempo30",
                        "https://kanzlei.example/at", "WIDERSPRUCH", "OFFEN"),
                new MissionCheck.ContradictionRecord("W2", "THEMA", "K2", "https://verband.example/radwege",
                        "https://rat.example/parken", "UNKLAR", ""),
                new MissionCheck.ContradictionRecord("W3", "FRAGE", "F1", "a", "b", "WIDERSPRUCH",
                        "IM_TEXT_DARGESTELLT"),
                new MissionCheck.ContradictionRecord("W4", "FRAGE", "F2", "a", "b", "VEREINBAR", "OFFEN"),
                new MissionCheck.ContradictionRecord("W5", "FRAGE", "F2", "a", "b", "", "OFFEN"),
                new MissionCheck.ContradictionRecord("W6", "THEMA", "K1", "a", "b", "WIDERSPRUCH",
                        "VOM_NUTZER_ENTSCHIEDEN"));
    }

    static MissionReport report() throws Exception {
        return new MissionCheck(memory()).sections(sections()).sentences(lookup()).placements(placements())
                .contradictions(contradictions()).sources(sources()).requested(6000, 9000).report(TIME);
    }

    static MissionReport.TopicLine topic(List<MissionReport.TopicLine> lines, String id) {
        for (MissionReport.TopicLine line : lines) {
            if (line.getTopic().getId().equals(id)) {
                return line;
            }
        }
        return null;
    }

    static MissionReport.QuestionLine question(MissionReport report, String id) {
        for (MissionReport.QuestionLine line : report.getQuestions()) {
            if (line.getQuestion().getId().equals(id)) {
                return line;
            }
        }
        return null;
    }

    @Test
    public void aTopicIsTreatedOnlyWithACoveredSentenceThatCitesOneOfItsPassages() throws Exception {
        MissionReport report = report();
        MissionReport.TopicLine speed = topic(report.getFoundTopics(), "K1");
        assertTrue(speed.isTreated());
        assertEquals("S1 (GEDECKT) and S4 (NICHT_GEDECKT) cite p1, the evidence of F1", 2,
                speed.getCounts().getSentences());
        assertEquals(1, speed.getCounts().getCovered());
        assertEquals(1, speed.getCounts().getNotCovered());
        assertTrue(topic(report.getFoundTopics(), "K2").isTreated());

        MissionReport.TopicLine parking = topic(report.getFoundTopics(), "K3");
        assertFalse("a lead nobody cites treats nothing", parking.isTreated());
        assertEquals(0, parking.getCounts().getSentences());

        MissionReport.TopicLine schools = topic(report.getFoundTopics(), "T1");
        assertFalse("its own section with a heading, but its only sentence is unchecked", schools.isTreated());
        assertEquals(1, schools.getCounts().getUnchecked());
        assertEquals(Placement.Fate.EIGENER_ABSCHNITT, schools.getPlacement().getFate());
        assertEquals("the placement of a merged topic does not take the best fate away", "Schulwege",
                schools.getSectionTitle());
        assertEquals("the findings of the merged topic count", 2, schools.getFindings());

        MissionReport.TopicLine delivery = topic(report.getFoundTopics(), "T2");
        assertFalse("TEILWEISE treats nothing", delivery.isTreated());
        assertEquals(1, delivery.getCounts().getPartial());

        String text = report.text(ZoneId.of("Europe/Berlin"));
        assertTrue(text, text.contains("  Tempo 30: behandelt, 1 gedeckter Satz (0 teilweise, 1 nicht gedeckt, 0 nicht "
                + "geprüft). Eigener Abschnitt „Tempo 30 in der Stadt“. Zaun: innen. Fundstellen: 0. 2 Fragen; Belegt: 1 "
                + "(1 Quelle) · "
                + "Weiterführender Hinweis: 0.\n"));
        assertTrue(text, text.contains("  Parken: nicht behandelt, kein gedeckter Satz (0 teilweise, 0 nicht gedeckt, "
                + "0 nicht geprüft). Nicht aufgenommen: nur ein Hinweis. Zaun: innen. Fundstellen: 0. 1 Frage; Belegt: 0 · "
                + "Weiterführender "
                + "Hinweis: 1.\n"));
        assertTrue(text, text.contains("„Behandelt“ heißt: mindestens ein Satz im Text, den der Satzprüfer als gedeckt "
                + "beurteilt hat"));
    }

    @Test
    public void everyFoundTopicHasItsFateAndReasonAndUnknownPlanEntriesAreNamed() throws Exception {
        MissionReport report = report();
        List<String> order = new ArrayList<String>();
        for (MissionReport.TopicLine line : report.getFoundTopics()) {
            order.add(line.getTopic().getId());
        }
        assertEquals("placed, merged, left out, outside, unclear, no fate; T7 stands in T1",
                Arrays.asList("K1", "K2", "T1", "T2", "K3", "T6", "T3", "T4", "T5"), order);
        assertEquals(Collections.singletonList("T99"), report.getUnknownPlacements());
        String text = report.text(ZoneId.of("Europe/Berlin"));
        assertTrue(text, text.contains("  Schulwege: nicht behandelt, kein gedeckter Satz (0 teilweise, 0 nicht "
                + "gedeckt, 1 nicht geprüft). Eigener Abschnitt „Schulwege“. Zaun: innen. Zusammengeführt aus 2 "
                + "Vorschlägen. Fundstellen: 2. 0 Fragen; Kein Beleg gefunden.\n"));
        assertTrue(text, text.contains("Lieferverkehr: nicht behandelt, kein gedeckter Satz (1 teilweise, 0 nicht "
                + "gedeckt, 0 nicht geprüft). Aufgegangen in „Tempo 30 in der Stadt“ (zu klein für einen eigenen "
                + "Abschnitt)."));
        assertTrue(text, text.contains("Ladezonen: nicht behandelt, kein gedeckter Satz (0 teilweise, 0 nicht gedeckt, "
                + "0 nicht geprüft). Nicht aufgenommen: zu wenig Stoff."));
        assertTrue(text, text.contains("Fahrradhelme im Ausland: nicht behandelt, kein gedeckter Satz (0 teilweise, 0 "
                + "nicht gedeckt, 0 nicht geprüft). Nicht im Buch: außerhalb des Weidezauns (anderer Rechtsraum). "
                + "Zaun: außen."));
        assertTrue(text, text.contains("E-Scooter: nicht behandelt, kein gedeckter Satz (0 teilweise, 0 nicht gedeckt, "
                + "0 nicht geprüft). Nicht im Buch: Zugehörigkeit unklar, wartet auf eine Entscheidung (Grenzfall). "
                + "Zaun: unklar."));
        assertTrue(text, text.contains("Carsharing: nicht behandelt, kein gedeckter Satz (0 teilweise, 0 nicht "
                + "gedeckt, "
                + "0 nicht geprüft). Kein Verbleib im Abschnittsplan vermerkt. Zaun: innen."));
        assertTrue(text, text.contains("  Der Abschnittsplan nennt 1 Thema, das das Gedächtnis nicht kennt: T99.\n"));
        assertTrue(text, text.contains("\nThemen (9)\n"));
    }

    @Test
    public void everyQuestionIsListedWithItsEvidenceOrKeinBelegAndTheSentencesThatCiteIt() throws Exception {
        MissionReport report = report();
        assertEquals(4, report.getQuestions().size());
        MissionReport.QuestionLine speed = question(report, "F1");
        assertEquals(2, speed.getCitedSentences());
        assertEquals(1, speed.getCoveredSentences());
        assertEquals("Tempo 30", speed.getTopicName());
        assertEquals(0, question(report, "F3").getCitedSentences());
        assertEquals("a passage judged KEIN for the question is no evidence of it, even when cited", 0,
                question(report, "F4").getCitedSentences());
        String text = report.text(ZoneId.of("Europe/Berlin"));
        assertTrue(text, text.contains("  F1 Wie wirkt Tempo 30 auf die Zahl der Unfälle? [Tempo 30]: Belegt: 1 (1 "
                + "Quelle) · Weiterführender Hinweis: 0. Im Text zitiert: 2 Sätze, davon 1 gedeckt.\n"));
        assertTrue(text, text.contains("  F3 Was kostet Anwohnerparken? [Parken]: Belegt: 0 · Weiterführender Hinweis: "
                + "1. Im Text zitiert: 0 Sätze.\n"));
        assertTrue(text, text.contains("  F4 Wer entscheidet über Tempo 30 auf Hauptstraßen? [Tempo 30]: Kein Beleg "
                + "gefunden (1 Passage geprüft). Im Text zitiert: 0 Sätze.\n"));
        JsonObject json = report.toJson();
        assertEquals("KEIN_BELEG", json.getAsJsonArray("fragen").get(3).getAsJsonObject().get("stand").getAsString());
        assertEquals("HINWEIS", json.getAsJsonArray("fragen").get(2).getAsJsonObject().get("stand").getAsString());
    }

    @Test
    public void sentencesLengthAndTheKindsOfCitedSourcesAreCounted() throws Exception {
        MissionReport report = report();
        MissionReport.Counts all = report.getSentences();
        assertEquals(6, all.getSentences());
        assertEquals(2, all.getCovered());
        assertEquals(1, all.getPartial());
        assertEquals(1, all.getNotCovered());
        assertEquals(1, all.getUnchecked());
        assertEquals(1, all.getUncited());
        assertEquals(1, report.getStatedAsFact());
        assertEquals(1, report.getForeignRuleUnmarked());
        assertEquals("12 + 6 + 6 + 5, 6, 5; markers are no words", 40, report.getWrittenWords());
        assertEquals(29, report.getSections().get(0).getWrittenWords());
        assertEquals(6, report.getCitations());
        assertEquals(5, report.getCitedSources());
        Map<String, Integer> kinds = new LinkedHashMap<String, Integer>();
        kinds.put("BEHOERDE_UNFALLVERSICHERUNG", 2);
        kinds.put("HERSTELLER_HANDEL", 1);
        kinds.put("KANZLEI_BERATUNG", 1);
        kinds.put(MissionCheck.UNRATED, 1);
        kinds.put("VERBAND", 1);
        assertEquals(kinds, report.getCitationsByKind());
        assertEquals(Arrays.asList("BEHOERDE_UNFALLVERSICHERUNG", "HERSTELLER_HANDEL", "KANZLEI_BERATUNG",
                MissionCheck.UNRATED, "VERBAND"), new ArrayList<String>(report.getCitationsByKind().keySet()));
        Map<String, Integer> spaces = new LinkedHashMap<String, Integer>();
        spaces.put("DE", 4);
        spaces.put("AT", 1);
        assertEquals(spaces, report.getCitationsByJurisdiction());
        String text = report.text(ZoneId.of("Europe/Berlin"));
        assertTrue(text, text.contains("  Verlangt: 6.000 bis 9.000 Wörter. Geschrieben: 40 Wörter in 3 "
                + "Abschnitten.\n"));
        assertTrue(text, text.contains("  „Tempo 30 in der Stadt“: Ziel 120 Wörter, geschrieben 29; 4 Sätze: 1 "
                + "gedeckt, "
                + "1 teilweise, 1 nicht gedeckt, 0 nicht geprüft, 1 ohne Quellenangabe\n"));
        assertTrue(text, text.contains("  6 Sätze: 2 gedeckt, 1 teilweise, 1 nicht gedeckt, 1 nicht geprüft, 1 ohne "
                + "Quellenangabe.\n"));
        assertTrue(text, text.contains("als Tatsache wiedergegeben: 1. Regeln eines anderen Rechtsraums ohne Angabe, "
                + "wo "
                + "sie gelten: 1.\n"));
        assertTrue(text, text.contains("  6 Quellenangaben aus 5 Quellen: BEHOERDE_UNFALLVERSICHERUNG 2 (33 %), "
                + "HERSTELLER_HANDEL 1 (17 %), KANZLEI_BERATUNG 1 (17 %), OHNE_BEWERTUNG 1 (17 %), VERBAND 1 (17 "
                + "%).\n"));
        assertTrue(text, text.contains("  Rechtsraum: DE 4 (80 %), AT 1 (20 %).\n"));
        assertTrue(text, text.startsWith("Auftragsabgleich vom 22.11.2025, "));
    }

    @Test
    public void openContradictionsAreListedTheOthersCountedAndAMissingCheckIsSaid() throws Exception {
        MissionReport report = report();
        assertEquals(2, report.getOpenContradictions().size());
        assertEquals("Wie wirkt Tempo 30 auf die Zahl der Unfälle?",
                report.getOpenContradictions().get(0).getScopeName());
        assertEquals("Radwege", report.getOpenContradictions().get(1).getScopeName());
        Map<String, Integer> others = new LinkedHashMap<String, Integer>();
        others.put("IM_TEXT_DARGESTELLT", 1);
        others.put("VEREINBAR", 1);
        others.put("UNBEURTEILT", 1);
        others.put("VOM_NUTZER_ENTSCHIEDEN", 1);
        assertEquals(others, report.getContradictionStates());
        String text = report.text(ZoneId.of("Europe/Berlin"));
        assertTrue(text, text.contains("\nOffene Widersprüche (2)\n  Frage „Wie wirkt Tempo 30 auf die Zahl der "
                + "Unfälle?“: https://amt.example/tempo30 und https://kanzlei.example/at (Widerspruch)\n  Thema "
                + "„Radwege“: https://verband.example/radwege und https://rat.example/parken (unklar)\n  Sonst: im "
                + "Text "
                + "dargestellt 1, vereinbar 1, nicht beurteilt 1, vom Nutzer entschieden 1.\n"));

        String none = new MissionCheck(memory()).report(TIME).text(ZoneId.of("UTC"));
        assertTrue(none, none.contains("\nOffene Widersprüche (0)\n  Keine Widerspruchsprüfung gespeichert.\n"));
        String empty = new MissionCheck(memory())
                .contradictions(Collections.<MissionCheck.ContradictionRecord>emptyList()).report(TIME)
                .text(ZoneId.of("UTC"));
        assertTrue(empty, empty.contains("\nOffene Widersprüche (0)\n  Keine offenen Widersprüche gespeichert.\n"));
    }

    @Test
    public void withoutTheSentenceCheckNothingIsTreatedAndWithoutAPlanNoFateIsInvented() throws Exception {
        MissionReport report = new MissionCheck(memory()).sections(sections()).report(TIME);
        assertEquals(5, report.getSentences().getUnchecked());
        assertEquals(0, report.getSentences().getCovered());
        for (MissionReport.TopicLine line : report.getFoundTopics()) {
            assertFalse(line.getTopic().getName(), line.isTreated());
            assertNull(line.getPlacement());
        }
        String text = report.text(ZoneId.of("UTC"));
        assertTrue(text, text.contains("  Tempo 30: nicht behandelt, kein gedeckter Satz (0 teilweise, 0 nicht "
                + "gedeckt, "
                + "2 nicht geprüft). Kein Verbleib im Abschnittsplan vermerkt."));
        assertTrue(text, text.contains("  Verlangt: keine Länge angegeben."));
        assertTrue(text, text.contains("  6 Quellenangaben aus 5 Quellen: OHNE_BEWERTUNG 6 (100 %).\n"));
    }

    /** Build plan: the report lists and never says "erfüllt" or "vollständig", even when every sentence is covered. */
    @Test
    public void theReportNeverClaimsThatTheBookOrTheResearchIsDone() throws Exception {
        SentenceLookup everything = (sentence, cited) -> new SentenceLookup.State(Verdict.GEDECKT, false, false);
        for (MissionReport report : new MissionReport[]{report(),
                new MissionCheck(memory()).sections(sections()).sentences(everything).placements(placements())
                        .requested(30, 0).report(TIME)}) {
            String text = report.text(ZoneId.of("UTC"));
            for (MissionReport.QuestionLine line : report.getQuestions()) {
                text = text.replace(line.getQuestion().getText(), "");
            }
            String lower = text.toLowerCase(Locale.GERMANY);
            for (String claim : new String[]{"erfüllt", "vollständig", "abgedeckt", "beantwortet", "alle ", "fertig",
                    "ausreichend", "genügt"}) {
                assertFalse(claim + " in\n" + text, lower.contains(claim));
            }
            assertFalse("no sentence of the text in the report", text.contains("Lotsen gesichert"));
            String json = report.toJson().toString();
            for (String passage : new String[]{P1, P2, P4, P5, P6}) {
                assertFalse("no passage text in the JSON", json.contains(passage.substring(0, 30)));
            }
            assertFalse("no sentence in the JSON", json.contains("Lieferwagen halten"));
        }
    }

    /** Component 11's stored verdicts: per cited passage, combined as the sentence check combines them. */
    @Test
    public void theLookupReadsTheSentenceChecksVerdictsPerCitedPassage() throws Exception {
        SentenceVerdicts store = new SentenceVerdicts();
        BookSection.Citation first = new BookSection.Citation(1, "p1", "s-amt", P1);
        BookSection.Citation second = new BookSection.Citation(2, "p4", "s-kanzlei", P4);
        String sentence = "In Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 Prozent [1, "
                + "2].";
        SentenceLookup lookup = SentenceLookup.of(store);
        assertNull("nothing checked yet", lookup.of(sentence, Arrays.asList(first, second)).getVerdict());

        store.record(checked(sentence, second, Verdict.NICHT_GEDECKT, "", Attribution.NICHT_GEFRAGT,
                Jurisdiction.NICHT_GEKENNZEICHNET));
        SentenceLookup.State half = lookup.of(sentence, Arrays.asList(first, second));
        assertNull("one passage not checked: no NICHT_GEDECKT yet", half.getVerdict());
        assertTrue(half.isForeignRuleUnmarked());

        store.record(checked("In  Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 "
                        + "Prozent [7].", first, Verdict.GEDECKT, "In den Tempo-30-Zonen der Stadt sank die Zahl der "
                        + "Unfälle mit Verletzten im ersten Jahr um 25 Prozent", Attribution.ALS_TATSACHE,
                Jurisdiction.NICHT_GEFRAGT));
        SentenceLookup.State both = lookup.of(sentence, Arrays.asList(first, second));
        assertEquals("other markers and spaces: the same sentence", Verdict.GEDECKT, both.getVerdict());
        assertTrue(both.isStatedAsFact());
        assertEquals(Verdict.NICHT_GEDECKT, lookup.of(sentence, Collections.singletonList(second)).getVerdict());
        assertNull("another passage text is another check", lookup.of(sentence, Collections.singletonList(
                new BookSection.Citation(1, "p4", "s-kanzlei", P4 + " Neu."))).getVerdict());
    }

    static SentenceVerdict checked(String sentence, BookSection.Citation citation, Verdict said, String quote,
                                   Attribution attribution, Jurisdiction jurisdiction) {
        return SentenceVerdict.checked("S", sentence, citation.getPassageId(), citation.getSourceId(),
                citation.getPassageText(), said, Kind.TEXT, quote, "Die Passage sagt es so.", attribution,
                jurisdiction, "gemma4:e2b", SentenceCheck.VERSION, TIME, 5L);
    }

    @Test
    public void aSectionOfTheManuscriptKeepsItsTextCitationsAndTarget() {
        SectionRevision revision = SectionRevision.builder().text(S1 + " " + S2)
                .citations(Arrays.asList(new ManuscriptCitation("1", "s-amt", "abc", P1, "p1", null),
                        new ManuscriptCitation("x", "s-amt", "abc", P1, "p1", null),
                        new ManuscriptCitation("2", "s-hersteller", "def", P6, "p6", null)))
                .wordTarget(0).build(3, TIME);
        BookSection section = BookSection.of("s1", "Tempo 30", revision, 150);
        assertEquals(150, section.getTargetWords());
        assertEquals("p6", section.citation(2).getPassageId());
        assertEquals(P1, section.citation(1).getPassageText());
        assertEquals("a marker that is no number is left out", 2, section.getCitations().size());
        assertNull(section.citation(3));
        assertEquals(12, MissionCheck.words("Ein Satz [1], noch einer [2; 3] – und 25 Prozent .. ! mehr Wörter z. B. "
                + "hier"));
    }

    @Test
    public void aPlanEntryNeedsItsSectionOrItsReason() {
        for (Runnable bad : new Runnable[]{
                () -> Placement.ownSection("T1", "", null),
                () -> Placement.mergedInto("T1", " ", "", null),
                () -> Placement.notTaken("T1", " "),
                () -> Placement.notTaken("", "Grund")}) {
            try {
                bad.run();
                throw new AssertionError("accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("a "));
            }
        }
        assertEquals(Arrays.asList("p1", "p2"), Placement.ownSection("T1", "s1", Arrays.asList("p1", " ", "p2", "p1",
                null)).getPassageIds());
    }
}
