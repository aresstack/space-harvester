package com.aresstack.writing4j.sectionplan;

import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.memory.QuestionEvidence;
import com.aresstack.writing4j.memory.ResearchMemory;
import com.aresstack.writing4j.memory.ResearchQuestion;
import com.aresstack.writing4j.memory.ResearchTopic;
import com.aresstack.writing4j.missionreport.MissionCheck;
import com.aresstack.writing4j.missionreport.Placement;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The section plan on self-written research memories about city traffic. */
public class SectionPlannerTest {

    static final long TIME = 1_763_800_000_000L;

    final ResearchMemory memory = new ResearchMemory();
    private int texts;
    private int questions;

    // ------------------------------------------------------------------------------------------------- data

    void card(String id, String name, double share) throws IOException {
        memory.addTopic(ResearchTopic.found(id, name, "", null, "", TIME).withFence(ResearchTopic.Fence.INNEN, "", "", TIME));
    }

    void found(String id, String name, ResearchTopic.Fence fence) throws IOException {
        found(id, name, "", fence, "");
    }

    void found(String id, String name, String description, ResearchTopic.Fence fence, String cardAnchorId)
            throws IOException {
        memory.addTopic(new ResearchTopic(id, name, description, null, null, fence,
                fence == ResearchTopic.Fence.UNGEPRUEFT ? "" : "Prüfung des Zauns", "", TIME));
    }

    String question(String topicId) throws IOException {
        String id = "frage-" + (++questions);
        memory.addQuestion(ResearchQuestion.open(id, "Was gilt für " + topicId + " (" + questions + ")?", topicId,
                ResearchQuestion.Origin.NUTZER, TIME));
        return id;
    }

    static String text(int number) {
        return "Text " + number + ": Radfahrer müssen in der Stadt auf Fußgänger achten und genug Abstand halten.";
    }

    /** A question on the topic with {@code direct} texts of direct evidence from {@code sources} sources. */
    List<String> evidence(String topicId, int direct, int sources) throws IOException {
        String questionId = question(topicId);
        List<String> written = new ArrayList<String>();
        for (int i = 0; i < direct; i++) {
            String text = text(++texts);
            verdict(questionId, "p" + texts, topicId + "-q" + (i % sources), text, EvidenceVerdict.Kind.BELEGT);
            written.add(text);
        }
        return written;
    }

    void verdict(String questionId, String passageId, String source, String text, EvidenceVerdict.Kind kind)
            throws IOException {
        memory.record(EvidenceVerdict.judged(questionId, passageId, source, "https://" + source + ".example/a", text,
                kind, text, "die Passage trägt die Frage", "test", 1, TIME));
    }

    /** The words of step 5a alone; the bound by material has tests of its own ({@link #bounded}). */
    SectionPlanner planner(int words) {
        return new SectionPlanner(memory).targetWords(words).wordsPerPassage(0);
    }

    /** With the bound by material, as in the product. */
    SectionPlanner bounded(int words) {
        return new SectionPlanner(memory).targetWords(words);
    }

    static Placement placement(SectionPlan plan, String topicId) {
        SectionPlan.TopicLine line = plan.topic(topicId);
        assertNotNull("no line for " + topicId, line);
        return line.getPlacement();
    }

    static List<String> chapterIds(SectionPlan plan) {
        List<String> ids = new ArrayList<String>();
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            ids.add(chapter.getId());
        }
        return ids;
    }

    /** Four cards, subtopics under three of them, a heavy free topic, a small one with shared evidence, a stray one. */
    void city() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        card("karte-fuss", "Fußverkehr", 0);
        card("karte-parken", "Parken", 0);
        card("karte-recht", "Recht", 0);
        found("t-lastenrad", "Lastenräder", ResearchTopic.Fence.INNEN);
        found("t-radweg", "Radwege", ResearchTopic.Fence.INNEN);
        found("t-schulweg", "Schulwege", ResearchTopic.Fence.INNEN);
        found("t-anwohner", "Anwohnerparken", ResearchTopic.Fence.INNEN);
        found("t-bussgeld", "Bußgelder", ResearchTopic.Fence.INNEN);
        found("t-tempo30", "Tempo 30 in Wohngebieten", ResearchTopic.Fence.INNEN);
        found("t-carsharing", "Carsharing", ResearchTopic.Fence.INNEN);
        found("t-laerm", "Lärm an Hauptstraßen", ResearchTopic.Fence.INNEN);
        evidence("karte-rad", 8, 4);
        evidence("karte-fuss", 6, 3);
        List<String> parking = evidence("karte-parken", 4, 2);
        evidence("karte-recht", 5, 5);
        evidence("t-lastenrad", 5, 3);
        evidence("t-radweg", 2, 1);
        evidence("t-schulweg", 4, 2);
        evidence("t-anwohner", 1, 1);
        evidence("t-bussgeld", 3, 3);
        evidence("t-tempo30", 10, 6);
        verdict(question("t-carsharing"), "p-car", "carsharing-q0", parking.get(0), EvidenceVerdict.Kind.BELEGT);
        evidence("t-laerm", 1, 1);
    }

    static final Map<String, String> CITY_PARENTS = new HashMap<String, String>();

    static {
        CITY_PARENTS.put("t-lastenrad", "karte-rad");
        CITY_PARENTS.put("t-radweg", "karte-rad");
        CITY_PARENTS.put("t-schulweg", "karte-fuss");
        CITY_PARENTS.put("t-anwohner", "karte-parken");
        CITY_PARENTS.put("t-bussgeld", "karte-recht");
    }

    static String parent(String id) {
        return CITY_PARENTS.getOrDefault(id, "");
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    public void everyTopicHasAFateAndALeftOutTopicItsReason() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        card("karte-kosten", "Kosten", 0);
        found("t-aussen", "Autobahnbau", ResearchTopic.Fence.AUSSEN);
        found("t-unklar", "Elektroroller", ResearchTopic.Fence.UNKLAR);
        found("t-neu", "Ampelphasen", ResearchTopic.Fence.UNGEPRUEFT);
        found("t-leer", "Fahrradstraßen", ResearchTopic.Fence.INNEN);
        found("t-hinweis", "Abbiegeassistenten", ResearchTopic.Fence.INNEN);
        found("t-nichts", "Radschnellwege", ResearchTopic.Fence.INNEN);
        evidence("karte-rad", 3, 2);
        question("karte-kosten");
        evidence("t-aussen", 4, 4);
        evidence("t-unklar", 2, 2);
        evidence("t-neu", 2, 2);
        verdict(question("t-hinweis"), "p-h", "hinweis-q", text(900), EvidenceVerdict.Kind.HINWEIS);
        verdict(question("t-nichts"), "p-k", "kein-q", text(901), EvidenceVerdict.Kind.KEIN);

        SectionPlan plan = planner(1000).plan(TIME);

        assertEquals(8, plan.getTopics().size());
        assertEquals(Placement.Fate.EIGENER_ABSCHNITT, placement(plan, "karte-rad").getFate());
        assertEquals("abschnitt-karte-rad", placement(plan, "karte-rad").getSectionId());
        Map<String, String> reasons = new HashMap<String, String>();
        reasons.put("karte-kosten", "Kein Beleg gefunden");
        reasons.put("t-aussen", "außerhalb des Weidezauns: Prüfung des Zauns");
        reasons.put("t-unklar", "Weidezaun unklar: Prüfung des Zauns");
        reasons.put("t-neu", "Weidezaun noch nicht geprüft");
        reasons.put("t-leer", "noch keine Frage zu diesem Thema gestellt");
        reasons.put("t-hinweis", "keine direkten Belege, nur weiterführende Hinweise");
        reasons.put("t-nichts", QuestionEvidence.LABEL_NONE);
        for (Map.Entry<String, String> expected : reasons.entrySet()) {
            Placement placement = placement(plan, expected.getKey());
            assertEquals(expected.getKey(), Placement.Fate.NICHT_AUFGENOMMEN, placement.getFate());
            assertEquals(expected.getKey(), expected.getValue(), placement.getReason());
        }
        assertEquals(Collections.singletonList("kapitel-karte-rad"), chapterIds(plan));
        assertEquals(1000, plan.getPlannedWords());
        assertEquals(5L, plan.topic("karte-rad").getWeight());
        assertEquals(0L, plan.topic("t-aussen").getWeight());
    }


    static List<String> sectionIds(SectionPlan.Chapter chapter) {
        List<String> ids = new ArrayList<String>();
        for (SectionPlan.Section section : chapter.getSections()) {
            ids.add(section.getId());
        }
        return ids;
    }

    /** Four cards with shares, subtopics under two of them, and two found topics that fit no chapter. */
    void sharedCity(double fourth) throws IOException {
        card("k1", "Radverkehr", 0.4);
        card("k2", "Fußverkehr", 0.3);
        card("k3", "Parken", 0.2);
        card("k4", "Recht", fourth);
        for (String[] sub : new String[][]{{"s-lasten", "Lastenräder", "k1"}, {"s-wege", "Radwege", "k1"},
                {"s-helm", "Fahrradhelme", "k1"}, {"s-geh", "Gehwege", "k2"}, {"s-zebra", "Zebrastreifen", "k2"}}) {
            found(sub[0], sub[1], "", ResearchTopic.Fence.INNEN, "anchor-concept-" + sub[2]);
            evidence(sub[0], 2, 1);
        }
        found("t-laerm", "Lärmschutz an Schulen", ResearchTopic.Fence.INNEN);
        found("t-bau", "Baustellen im Winter", ResearchTopic.Fence.INNEN);
        evidence("k1", 6, 3);
        evidence("k2", 5, 3);
        evidence("k3", 4, 2);
        evidence("k4", 3, 2);
        evidence("t-laerm", 3, 2);
        evidence("t-bau", 2, 2);
    }




    @Test
    public void aHeavySectionIsSplitByItsQuestionsWithDirectEvidence() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        String first = evidenceQuestion("karte-rad", 4, 2);
        String second = evidenceQuestion("karte-rad", 3, 3);
        String third = evidenceQuestion("karte-rad", 2, 1);
        String fourth = evidenceQuestion("karte-rad", 1, 1);
        String lead = question("karte-rad");
        verdict(lead, "p-h", "hinweis-q", text(900), EvidenceVerdict.Kind.HINWEIS);
        List<MissionCheck.ContradictionRecord> records = Arrays.asList(
                new MissionCheck.ContradictionRecord("w1", "FRAGE", third, "a.example", "b.example", "WIDERSPRUCH",
                        "OFFEN"),
                new MissionCheck.ContradictionRecord("w2", "THEMA", "karte-rad", "c.example", "d.example", "UNKLAR",
                        "OFFEN"));

        SectionPlan plan = planner(2000).contradictions(records).plan(TIME);

        // 2000 words hold four sections; the card is the only topic, and its four questions with direct evidence
        // carry four parts of at least 450 words.
        List<SectionPlan.Section> sections = plan.getSections();
        assertEquals(Arrays.asList("abschnitt-karte-rad", "abschnitt-karte-rad-teil-2", "abschnitt-karte-rad-teil-3",
                "abschnitt-karte-rad-teil-4"), sectionIds(plan.getChapters().get(0)));
        assertEquals("Radverkehr", sections.get(0).getTitle());
        assertEquals(memory.question(second).getText(), sections.get(1).getTitle());
        assertEquals(Arrays.asList(first, lead), questionIds(sections.get(0)));
        assertEquals(Collections.singletonList(second), questionIds(sections.get(1)));
        assertEquals(Collections.singletonList(third), questionIds(sections.get(2)));
        assertEquals(Collections.singletonList(fourth), questionIds(sections.get(3)));
        // Weights 6, 6, 3, 2: 350 each, the remaining 600 by weight.
        assertEquals(Arrays.asList(562, 562, 456, 420), words(sections));
        assertEquals(4, sections.get(0).getEvidence().size());
        assertEquals(1, sections.get(0).getLeads().size());
        for (SectionPlan.Section section : sections) {
            assertEquals("karte-rad", section.getTopicId());
        }
        assertEquals("w2", sections.get(0).getContradictions().get(0).getId());
        assertEquals("w1", sections.get(2).getContradictions().get(0).getId());
        assertEquals(1, sections.get(2).getContradictions().size());
        Placement card = placement(plan, "karte-rad");
        assertEquals(Placement.Fate.EIGENER_ABSCHNITT, card.getFate());
        assertEquals("abschnitt-karte-rad", card.getSectionId());
        assertEquals(10, card.getPassageIds().size());
        assertEquals(plan.toJson(), SectionPlan.fromJson(plan.toJson()).toJson());
    }



    /** The bound comes before the 450 words of a split part: a section splits only where its material holds them. */
    @Test
    public void theBoundComesBeforeTheSplit() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        evidenceQuestion("karte-rad", 8, 4);
        evidenceQuestion("karte-rad", 4, 2);
        evidenceQuestion("karte-rad", 2, 1);

        // Without the bound 2000 words make three parts (see the split test); 14 texts hold 980 words: two parts
        // of at least 450, each within the bound of its own texts.
        SectionPlan plan = bounded(2000).wordsPerPassage(70).plan(TIME);

        List<SectionPlan.Section> sections = plan.getSections();
        assertEquals(Arrays.asList("abschnitt-karte-rad", "abschnitt-karte-rad-teil-2"),
                sectionIds(plan.getChapters().get(0)));
        int planned = 0;
        for (SectionPlan.Section section : sections) {
            assertTrue(section.getId() + " " + section.getWords(), section.getWords()
                    <= 70 * (section.getEvidence().size() + section.getLeads().size()));
            planned += section.getWords();
        }
        assertEquals(980, planned);
        assertEquals(1020, plan.getChapters().get(0).getShortfall());
        assertTrue(plan.getNotes().toString(), plan.getNotes().contains("Das Kapitel „Radverkehr“ hat Stoff für 980 "
                + "seiner 2000 Wörter (höchstens 70 je Passage mit Text); es fehlen 1020 Wörter, seine Abschnitte "
                + "werden kürzer."));
    }

    /** Like {@link #evidence}, but returns the question's id. */
    String evidenceQuestion(String topicId, int direct, int sources) throws IOException {
        evidence(topicId, direct, sources);
        return "frage-" + questions;
    }

    static List<String> questionIds(SectionPlan.Section section) {
        List<String> ids = new ArrayList<String>();
        for (SectionPlan.Question question : section.getQuestions()) {
            ids.add(question.getId());
        }
        return ids;
    }

    static List<Integer> words(List<SectionPlan.Section> sections) {
        List<Integer> words = new ArrayList<Integer>();
        for (SectionPlan.Section section : sections) {
            words.add(section.getWords());
        }
        return words;
    }

    @Test
    public void theBookFollowsTheOrderOfItsTopicsAndAShortBookCarriesItsTitle() throws IOException {
        // the outline's order: basics first, then the light topic, then the heaviest
        card("kapitel-1", "Grundlagen", 0);
        card("kapitel-2", "Werkzeug", 0);
        card("kapitel-3", "Schnitt", 0);
        evidence("kapitel-1", 4, 2);
        evidence("kapitel-2", 2, 1);
        evidence("kapitel-3", 5, 3);

        SectionPlan plan = planner(1200).bookTitle("Obstbäume schneiden").plan(TIME);

        assertEquals("no topic earns a chapter of its own in so few words", 1, plan.getChapters().size());
        SectionPlan.Chapter chapter = plan.getChapters().get(0);
        assertEquals("the one chapter is the book, not a remainder", "Obstbäume schneiden", chapter.getTitle());
        assertEquals("weight picks the sections, the order of the topics orders them",
                Arrays.asList("abschnitt-kapitel-1", "abschnitt-kapitel-3"), sectionIds(chapter));
        assertEquals(SectionPlanner.COLLECTION_TITLE, planner(1200).plan(TIME).getChapters().get(0).getTitle());
    }

    @Test
    public void theCardKeepsItsSectionAndOnlyLighterTopicsGoIntoOthers() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        found("t-a", "Lastenräder", ResearchTopic.Fence.INNEN);
        found("t-b", "Radwege", ResearchTopic.Fence.INNEN);
        found("t-c", "Fahrradparken", ResearchTopic.Fence.INNEN);
        found("t-d", "Radverleih", ResearchTopic.Fence.INNEN);
        evidence("karte-rad", 2, 2);
        evidence("t-a", 5, 4);
        evidence("t-b", 4, 3);
        evidence("t-c", 3, 2);
        evidence("t-d", 2, 1);
        Map<String, String> parents = new HashMap<String, String>();
        for (String id : Arrays.asList("t-a", "t-b", "t-c", "t-d")) {
            parents.put(id, "karte-rad");
        }

        SectionPlan plan = planner(1400).parents(id -> parents.getOrDefault(id, "")).plan(TIME);

        SectionPlan.Chapter chapter = plan.getChapters().get(0);
        assertEquals(Arrays.asList("abschnitt-karte-rad", "abschnitt-t-a", "abschnitt-t-b"), sectionIds(chapter));
        for (String light : Arrays.asList("t-c", "t-d")) {
            Placement placement = placement(plan, light);
            assertEquals(Placement.Fate.AUFGEGANGEN_IN, placement.getFate());
            assertTrue(placement.getReason(), placement.getReason().startsWith("kein eigener Abschnitt: das Kapitel "
                    + "hat Wörter für 3 Abschnitte, Gewicht " + plan.topic(light).getWeight() + "; "));
        }
        assertTrue(plan.topic("t-b").getWeight() > plan.topic("t-c").getWeight());
        assertEquals(1400, plan.getPlannedWords());
    }




    @Test
    public void aMergedTopicFollowsTheTopicItWentInto() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        card("karte-kosten", "Kosten", 0);
        found("t-dup", "Radfahren in der Stadt", ResearchTopic.Fence.INNEN);
        found("t-preis", "Preise", ResearchTopic.Fence.INNEN);
        evidence("karte-rad", 3, 2);
        evidence("t-dup", 2, 2);
        question("karte-kosten");
        memory.updateTopic(memory.topic("karte-rad").withMerged(Collections.singletonList(memory.topic("t-dup")),
                "test", TIME));
        memory.updateTopic(memory.topic("karte-kosten").withMerged(Collections.singletonList(memory.topic("t-preis")),
                "test", TIME));

        SectionPlan plan = planner(1000).plan(TIME);

        Placement dup = placement(plan, "t-dup");
        assertEquals(Placement.Fate.AUFGEGANGEN_IN, dup.getFate());
        assertEquals("abschnitt-karte-rad", dup.getSectionId());
        assertEquals("zusammengeführt mit „Radverkehr“", dup.getReason());
        Placement price = placement(plan, "t-preis");
        assertEquals(Placement.Fate.NICHT_AUFGENOMMEN, price.getFate());
        assertEquals("zusammengeführt mit „Kosten“: Kein Beleg gefunden", price.getReason());
        assertEquals(4, plan.getTopics().size());
        // The merged topic's evidence counts for the card it went into.
        assertEquals(5, plan.topic("karte-rad").getDirect());
    }

    /** A chooser that answers from a map: topic name to the chosen name, "" for none, null for a failure. */
    static final class MapChooser implements HostChooser {
        final Map<String, String> answers = new HashMap<String, String>();
        final List<List<String>> asked = new ArrayList<List<String>>();

        public Choice choose(Option topic, List<Option> candidates) {
            List<String> names = new ArrayList<String>();
            for (Option candidate : candidates) {
                names.add(candidate.getName() + "|" + candidate.getChapterTitle());
            }
            asked.add(names);
            String wanted = answers.get(topic.getName());
            if (wanted == null) {
                return Choice.failed("Zeitüberschreitung", null);
            }
            for (Option candidate : candidates) {
                if (candidate.getName().equals(wanted)) {
                    return Choice.of(candidate.getTopicId(), "passt zum Radverkehr", null);
                }
            }
            return Choice.none("passt nirgends", null);
        }

        public String modelName() {
            return "skript";
        }
    }


    static String chapterOf(SectionPlan plan, String sectionId) {
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            for (SectionPlan.Section section : chapter.getSections()) {
                if (section.getId().equals(sectionId)) {
                    return chapter.getId();
                }
            }
        }
        return null;
    }

    @Test
    public void withoutAModelSharedWordsPlaceASmallFoundTopic() throws IOException {
        card("karte-parken", "Parken in der Innenstadt", 0);
        found("t-park", "Parken am Bahnhof", ResearchTopic.Fence.INNEN);
        evidence("karte-parken", 6, 3);
        evidence("t-park", 1, 1);

        SectionPlan plan = planner(800).plan(TIME);

        assertNull(chapterOf(plan, "abschnitt-t-park"));
        assertEquals("abschnitt-karte-parken", placement(plan, "t-park").getSectionId());
        assertTrue(placement(plan, "t-park").getReason(), placement(plan, "t-park").getReason()
                .endsWith("; gemeinsame Wörter mit „Parken in der Innenstadt“"));
        assertTrue(SectionPlanner.sameStem("parken", "parkenden"));
        assertFalse(SectionPlanner.sameStem("park", "parkgebühren"));
    }

    @Test
    public void aSectionListsItsEvidenceOncePerTextWithTheQuestionsAndKeepsLeadsApart() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        String first = question("karte-rad");
        String second = question("karte-rad");
        String third = question("karte-rad");
        verdict(first, "p1", "quelle-a", text(1), EvidenceVerdict.Kind.BELEGT);
        verdict(second, "p1b", "quelle-b", text(1), EvidenceVerdict.Kind.BELEGT);
        verdict(second, "p2", "quelle-c", text(2), EvidenceVerdict.Kind.HINWEIS);
        verdict(first, "p3", "quelle-d", text(3), EvidenceVerdict.Kind.HINWEIS);
        verdict(second, "p3b", "quelle-d", text(3), EvidenceVerdict.Kind.BELEGT);

        SectionPlan plan = planner(500).plan(TIME);

        SectionPlan.Section section = plan.getSections().get(0);
        assertEquals(2, section.getEvidence().size());
        SectionPlan.Evidence shared = section.getEvidence().get(0);
        assertEquals("p1", shared.getPassageId());
        assertEquals(Arrays.asList(first, second), shared.getQuestionIds());
        assertEquals(Collections.singletonList("karte-rad"), shared.getTopicIds());
        assertEquals("p3b", section.getEvidence().get(1).getPassageId());
        assertEquals(1, section.getLeads().size());
        assertEquals("p2", section.getLeads().get(0).getPassageId());
        assertEquals(3, section.getQuestions().size());
        assertEquals(QuestionEvidence.LABEL_DIRECT, section.getQuestions().get(0).getStanding());
        assertEquals(QuestionEvidence.LABEL_NONE, section.getQuestions().get(2).getStanding());
        assertEquals(third, section.getQuestions().get(2).getId());
        assertEquals(Arrays.asList("p1", "p3b"), placement(plan, "karte-rad").getPassageIds());
    }

    @Test
    public void openContradictionsGoToTheSectionOfTheirQuestionOrTopic() throws IOException {
        card("karte-rad", "Radverkehr", 0);
        found("t-weg", "Autobahnbau", ResearchTopic.Fence.AUSSEN);
        evidence("karte-rad", 4, 2);
        String questionId = memory.questions().get(0).getId();
        List<MissionCheck.ContradictionRecord> records = Arrays.asList(
                new MissionCheck.ContradictionRecord("w1", "FRAGE", questionId, "a.example", "b.example",
                        "WIDERSPRUCH", "OFFEN"),
                new MissionCheck.ContradictionRecord("w2", "THEMA", "karte-rad", "c.example", "d.example",
                        "VEREINBAR", "OFFEN"),
                new MissionCheck.ContradictionRecord("w3", "THEMA", "t-weg", "e.example", "f.example", "UNKLAR",
                        "OFFEN"),
                new MissionCheck.ContradictionRecord("w4", "THEMA", "karte-rad", "g.example", "h.example",
                        "WIDERSPRUCH", "IM_TEXT_DARGESTELLT"));

        SectionPlan plan = planner(500).contradictions(records).plan(TIME);

        List<SectionPlan.Contradiction> open = plan.getSections().get(0).getContradictions();
        assertEquals(1, open.size());
        assertEquals("w1", open.get(0).getId());
        assertEquals("a.example", open.get(0).getSourceA());
        assertEquals("WIDERSPRUCH", open.get(0).getVerdict());
        assertTrue(plan.getNotes().toString(), plan.getNotes().contains("Der offene Widerspruch w3 gehört zu keinem "
                + "Abschnitt der Gliederung."));
    }

    @Test
    public void withoutALengthEveryTopicWithEvidenceEarnsASectionsWorth() throws IOException {
        city();

        SectionPlan plan = planner(0).parents(SectionPlannerTest::parent).plan(TIME);

        assertFalse(plan.isTargetGiven());
        assertEquals(12 * SectionPlanner.SECTION_WORDS, plan.getTargetWords());
        assertEquals(12 * SectionPlanner.SECTION_WORDS, plan.getPlannedWords());
        assertTrue(plan.getNotes().toString(), plan.getNotes().contains("Kein Umfang vorgegeben: angenommen werden "
                + "450 Wörter je Thema mit direkten Belegen (12 Themen, 5400 Wörter)."));
    }

    @Test
    public void anEmptyMemoryGivesAnEmptyPlanThatSaysSo() {
        SectionPlan plan = planner(7500).plan(TIME);

        assertTrue(plan.getChapters().isEmpty());
        assertEquals(Collections.singletonList("Kein Thema hat direkte Belege; die Gliederung ist leer."),
                plan.getNotes());
    }

    @Test
    public void thePlanIsTheSameEveryTimeAndSurvivesItsJson() throws IOException {
        city();
        List<MissionCheck.ContradictionRecord> records = Collections.singletonList(
                new MissionCheck.ContradictionRecord("w1", "THEMA", "t-tempo30", "a.example", "b.example", "UNKLAR",
                        "OFFEN"));

        SectionPlan plan = planner(7500).parents(SectionPlannerTest::parent).contradictions(records).plan(TIME);
        SectionPlan again = planner(7500).parents(SectionPlannerTest::parent).contradictions(records).plan(TIME);

        assertEquals(plan.toJson(), again.toJson());
        SectionPlan read = SectionPlan.fromJson(plan.toJson());
        assertEquals(plan.toJson(), read.toJson());
        assertEquals(plan.getPlacements().size(), read.getPlacements().size());
        assertEquals(Placement.Fate.AUFGEGANGEN_IN, placement(read, "t-carsharing").getFate());
        assertEquals("w1", read.section("abschnitt-t-tempo30").getContradictions().get(0).getId());
    }
}
