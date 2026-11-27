package com.aresstack.writing4j.planwriter;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.writing4j.memory.QuestionEvidence;
import com.aresstack.writing4j.missionreport.BookSection;
import com.aresstack.writing4j.missionreport.SentenceLookup;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.writing4j.engine.EvidencePassage;
import com.aresstack.writing4j.engine.SectionWriterPrompt;
import com.aresstack.writing4j.sectionplan.SectionPlan;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Verdict;
import com.aresstack.writing4j.sentencecheck.SentenceVerdicts;
import com.aresstack.knowledge4j.file.CorruptFileException;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 10: the section plan gives the writer its key points (one per question, direct evidence first, leads
 * marked, no evidence a gap) and notes per passage; every cited sentence goes to the sentence check, a failing one goes
 * back to the writer once, what still fails is marked, never deleted. Texts are self-written (road traffic).
 */
public class PlanSectionWriterTest {

    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(90_000L), ZoneOffset.UTC);

    static final String P1 = "Tempo 30 senkt den Lärm in Wohnstraßen deutlich. Messungen ergaben drei Dezibel weniger.";
    static final String P2 = "In Tempo-30-Zonen gibt es weniger schwere Unfälle. Die Zahl der Verletzten sank um ein "
            + "Viertel.";
    static final String P3 = "Unsere Blitzer erkennen jedes Fahrzeug zuverlässig, auch bei Nacht und auf mehrspurigen "
            + "Straßen.";
    static final String P4 = "In Wien gilt in Wohngebieten flächendeckend Tempo 30. Die Stadt hat das im Gemeinderat "
            + "beschlossen.";
    static final String P5 = "Der Verband fordert mehr Tempo-30-Zonen vor Schulen und Kindergärten in allen Städten.";

    static final String Q1 = "Wie wirkt Tempo 30 auf den Lärm?";
    static final String Q2 = "Wie wirkt Tempo 30 auf Unfälle?";
    static final String Q3 = "Was fordern Verbände?";
    static final String Q4 = "Was kostet die Umstellung?";
    static final String Q5 = "Wie zuverlässig ist die Überwachung?";
    static final String Q6 = "Wer kontrolliert die Zonen?";

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    /** A transport that answers by a function and records every request; safe for parallel callers. */
    static final class Scripted implements MainModelChat {
        final Function<List<ChatMessage>, MainModelChatResult> answers;
        final AtomicInteger calls = new AtomicInteger();
        final List<List<ChatMessage>> requests = Collections.synchronizedList(new ArrayList<List<ChatMessage>>());
        final List<String> schemas = Collections.synchronizedList(new ArrayList<String>());

        Scripted(Function<List<ChatMessage>, MainModelChatResult> answers) {
            this.answers = answers;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            requests.add(new ArrayList<ChatMessage>(messages));
            schemas.add(schemaJson);
            return answers.apply(messages);
        }

        @Override
        public String modelName() {
            return "test-model";
        }

        List<List<ChatMessage>> rewrites() {
            List<List<ChatMessage>> out = new ArrayList<List<ChatMessage>>();
            synchronized (requests) {
                for (List<ChatMessage> request : requests) {
                    if (PlanSectionWriter.REWRITE_SYSTEM.equals(request.get(0).getContent())) {
                        out.add(request);
                    }
                }
            }
            return out;
        }
    }

    static PassageLookup lookup() {
        return PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("p1", "stadt-a", "https://stadt-a.example/laerm", P1, "BEHOERDE", "DE"),
                new PassageLookup.Passage("p2", "stadt-b", "", P2, "", "DE"),
                new PassageLookup.Passage("p3", "blitzer", "", P3, "hersteller_handel", "DE"),
                new PassageLookup.Passage("p4", "wien", "", P4, "BEHOERDE", "at"),
                new PassageLookup.Passage("p5", "verband", "", P5, "VERBAND", "DE"),
                new PassageLookup.Passage("leer", "stadt-c", "   ")));
    }

    static SectionPlan.Evidence ev(String passageId, String sourceId, String... questions) {
        return new SectionPlan.Evidence(passageId, sourceId, "", "key-" + passageId, Arrays.asList(questions),
                Collections.singletonList("t1"));
    }

    static SectionPlan.Question q(String id, String text, String standing) {
        return new SectionPlan.Question(id, text, "t1", standing);
    }

    static SectionPlan.Section section(String id, String title, int words, List<SectionPlan.Evidence> evidence,
                                       List<SectionPlan.Evidence> leads, List<SectionPlan.Question> questions,
                                       List<SectionPlan.Contradiction> contradictions) {
        return new SectionPlan.Section(id, title, "t1", Collections.singletonList("t1"), 10, words, evidence, leads,
                questions, contradictions);
    }

    static SectionPlan plan(SectionPlan.Section... sections) {
        SectionPlan.Chapter chapter = new SectionPlan.Chapter("kapitel-1", "Verkehr in der Stadt",
                SectionPlan.ChapterKind.GEFUNDEN, "karte-1", Double.NaN, 1000, Arrays.asList(sections));
        return new SectionPlan(1, 1L, 1000, true, Collections.singletonList(chapter),
                Collections.<SectionPlan.TopicLine>emptyList(), Collections.<String>emptyList(), "");
    }

    /** Every case of the plan: direct evidence, a rule of Austria, a maker, a lead, no evidence, no text. */
    static SectionPlan.Section full() {
        return section("abschnitt-t1", "Tempo 30", 400,
                Arrays.asList(ev("p1", "stadt-a", "q1"), ev("p2", "stadt-b", "q2"), ev("p4", "wien", "q2"),
                        ev("p3", "blitzer", "q5"), ev("p6", "fehlt", "q5", "q6")),
                Collections.singletonList(ev("p5", "verband", "q3")),
                Arrays.asList(q("q1", Q1, QuestionEvidence.LABEL_DIRECT), q("q2", Q2, QuestionEvidence.LABEL_DIRECT),
                        q("q3", Q3, QuestionEvidence.LABEL_LEAD), q("q4", Q4, QuestionEvidence.LABEL_NONE),
                        q("q5", Q5, QuestionEvidence.LABEL_DIRECT), q("q6", Q6, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
    }

    static PlanSectionWriter.Options options() {
        return PlanSectionWriter.Options.defaults().withTopUp(false).withParallel(1).withCheckParallel(1);
    }

    // -------------------------------------------------------------------------------------------- the plan's order

    @Test
    public void thePlanGivesTheWriterItsQuestionsAsKeyPointsWithTheirPassagesAndNotes() {
        PlanSectionWriter.Prepared prepared = PlanSectionWriter.prepare(full(), "Verkehr in der Stadt", lookup(),
                options());

        assertEquals(Arrays.asList(Q1, Q2, Q3 + PlanSectionWriter.LEAD_POINT, Q5),
                prepared.assignment.getKeyPoints());
        assertEquals("direct evidence first, the lead last, each bound to its question", Arrays.asList(
                Collections.singletonList(1), Arrays.asList(2, 3), Collections.singletonList(5),
                Collections.singletonList(4)), prepared.assignment.getKeyPointPassages());
        List<EvidencePassage> pack = prepared.assignment.getPassages();
        assertEquals(Arrays.asList("p1", "p2", "p4", "p3", "p5"), passageIds(pack));
        assertEquals("the stock has no text for it", Collections.singletonList("p6"), prepared.withoutText);
        assertEquals(PlanSectionWriter.NOTE_DIRECT, pack.get(0).getNote());
        assertEquals("direct evidence; Austrian law: say where a rule holds, e.g. „in Österreich“",
                pack.get(2).getNote());
        assertEquals("direct evidence; a maker or seller speaks: name it as the speaker in every sentence that takes "
                + "a claim from here, e.g. „nach Angaben des Herstellers“", pack.get(3).getNote());
        assertEquals(PlanSectionWriter.NOTE_LEAD + "; an association speaks: name it as the speaker in every sentence "
                + "that takes a claim from here, e.g. „nach Angaben des Verbands“", pack.get(4).getNote());
        assertEquals(P3, pack.get(3).getText());

        List<WrittenSection.Gap> gaps = prepared.gaps;
        assertEquals(3, gaps.size());
        assertEquals(WrittenSection.Gap.Kind.NUR_HINWEISE, gaps.get(0).getKind());
        assertEquals("q3", gaps.get(0).getQuestionId());
        assertEquals(Q3, gaps.get(0).getText());
        assertEquals("no evidence, no statement: a gap", WrittenSection.Gap.Kind.FRAGE_OHNE_BELEG,
                gaps.get(1).getKind());
        assertEquals(Q4, gaps.get(1).getText());
        assertEquals(WrittenSection.Gap.Kind.FRAGE_OHNE_BELEG, gaps.get(2).getKind());
        assertEquals(Q6 + " (Belege ohne Text im Bestand)", gaps.get(2).getText());

        assertEquals("Part of the chapter \"Verkehr in der Stadt\". The key points are questions of the research: "
                + "write about each from its passages.", prepared.assignment.getBrief());
        assertEquals("German", prepared.assignment.getBookLanguage());
        assertEquals(400, prepared.assignment.getSectionWords());
        assertEquals(450, prepared.assignment.getWordsPerCall());
        assertEquals(10, prepared.assignment.getPassagesPerCall());
        String prompt = SectionWriterPrompt.first(prepared.assignment).get(1).getContent();
        assertTrue(prompt, prompt.contains("- " + Q3 + " (only leads: say what they show; no source answers it "
                + "directly)\n"));
        assertTrue("a source with a site is called by its name", prompt.contains("[1] \"\"\"" + P1 + "\"\"\" "
                + "(source Stadt-A; direct evidence)\n"));
        assertTrue("one without keeps a letter", prompt.contains("[3] \"\"\"" + P4 + "\"\"\" (source B; direct "
                + "evidence; Austrian law: say where a rule holds, e.g. „in Österreich“)\n"));
        assertEquals(Collections.singletonMap("stadt-a", "Stadt-A"), prepared.assignment.getSourceNames());
        assertFalse("no key point for a question without evidence", prompt.contains(Q4));

        PlanSectionWriter.Prepared english = PlanSectionWriter.prepare(full(), "", lookup(),
                options().withLanguage("en"));
        assertEquals("English", english.assignment.getBookLanguage());
        assertEquals("direct evidence; Austrian law: say where a rule holds, e.g. \"in Austria\"",
                english.assignment.getPassages().get(2).getNote());
        assertEquals(Q6 + " (evidence without a text in the stock)", english.gaps.get(2).getText());
        assertEquals("The key points are questions of the research: write about each from its passages.",
                english.assignment.getBrief());
        PlanSectionWriter.Prepared austrian = PlanSectionWriter.prepare(full(), "", lookup(),
                options().withLegalSpace("AT"));
        assertEquals("a book for Austria marks no Austrian rule", PlanSectionWriter.NOTE_DIRECT,
                austrian.assignment.getPassages().get(2).getNote());
        assertEquals("but a German one", "direct evidence; German law: say where a rule holds, e.g. „in "
                + "Deutschland“", austrian.assignment.getPassages().get(0).getNote());
    }

    @Test
    public void bothSidesOfAnOpenContradictionAreNotedAndComeFirst() {
        SectionPlan.Section section = section("abschnitt-t1", "Tempo 30", 400,
                Arrays.asList(ev("p1", "stadt-a", "q2"), ev("p2", "stadt-b", "q2"), ev("p4", "wien", "q2")),
                Collections.<SectionPlan.Evidence>emptyList(),
                Collections.singletonList(q("q2", Q2, QuestionEvidence.LABEL_DIRECT)),
                Arrays.asList(new SectionPlan.Contradiction("W1", "FRAGE", "q2", "stadt-b", "wien", "WIDERSPRUCH"),
                        new SectionPlan.Contradiction("W2", "THEMA", "t1", "stadt-a", "nirgends", "UNKLAR"),
                        new SectionPlan.Contradiction("W3", "FRAGE", "q-alt", "stadt-b", "wien", "WIDERSPRUCH")));

        PlanSectionWriter.Prepared prepared = PlanSectionWriter.prepare(section, "", lookup(), options());

        assertEquals("the contradiction's passages first", Collections.singletonList(Arrays.asList(2, 3, 1)),
                prepared.assignment.getKeyPointPassages());
        assertEquals(PlanSectionWriter.NOTE_DIRECT, prepared.assignment.getPassages().get(0).getNote());
        assertEquals(PlanSectionWriter.NOTE_DIRECT + "; " + PlanSectionWriter.NOTE_CONTRADICTION,
                prepared.assignment.getPassages().get(1).getNote());
        assertTrue(prepared.assignment.getPassages().get(2).getNote().endsWith(PlanSectionWriter.NOTE_CONTRADICTION));
        assertEquals("an old question id still finds both sources", 2, prepared.contradictions);
        assertEquals("a side without a passage", 1, prepared.contradictionsWithoutPassage);
        assertTrue(prepared.assignment.getBrief(), prepared.assignment.getBrief().endsWith("Some passages contradict "
                + "each other: present both views, each with its source."));
    }

    @Test
    public void aLongSectionWithFewKeyPointsIsSplitByItsPassages() {
        List<PassageLookup.Passage> passages = new ArrayList<PassageLookup.Passage>();
        List<SectionPlan.Evidence> evidence = new ArrayList<SectionPlan.Evidence>();
        for (int i = 1; i <= 12; i++) {
            passages.add(new PassageLookup.Passage("x" + i, "quelle-" + i, "Absatz " + i + ": Tempo 30 wirkt."));
            evidence.add(ev("x" + i, "quelle-" + i, "q1"));
        }
        SectionPlan.Section eight = section("s8", "Lärm", 1350, evidence.subList(0, 8),
                Collections.<SectionPlan.Evidence>emptyList(),
                Collections.singletonList(q("q1", Q1, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        PlanSectionWriter.Prepared split = PlanSectionWriter.prepare(eight, "", PassageLookup.of(passages),
                options());
        assertEquals("three calls wanted, two halves of four possible", Arrays.asList(Q1,
                Q1 + PlanSectionWriter.MORE_POINT), split.assignment.getKeyPoints());
        assertEquals(Arrays.asList(Arrays.asList(1, 3, 5, 7), Arrays.asList(2, 4, 6, 8)),
                split.assignment.getKeyPointPassages());

        SectionPlan.Section twelve = section("s12", "Lärm", 1350, evidence,
                Collections.<SectionPlan.Evidence>emptyList(),
                Collections.singletonList(q("q1", Q1, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        PlanSectionWriter.Prepared three = PlanSectionWriter.prepare(twelve, "", PassageLookup.of(passages),
                options());
        assertEquals(3, three.assignment.getKeyPoints().size());
        assertEquals(Arrays.asList(1, 5, 9), three.assignment.getKeyPointPassages().get(0));
        PlanSectionWriter.Prepared short_ = PlanSectionWriter.prepare(section("s", "Lärm", 400, evidence,
                Collections.<SectionPlan.Evidence>emptyList(),
                Collections.singletonList(q("q1", Q1, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList()), "", PassageLookup.of(passages), options());
        assertEquals("one call is enough", 1, short_.assignment.getKeyPoints().size());
    }

    // --------------------------------------------------------------------------------------------- writing

    /** The writer: the draft by section title, a repair gets the same text, a rewrite by its old sentence. */
    static Scripted writer(Map<String, String> drafts, Map<String, String> rewrites) {
        return new Scripted(messages -> {
            String system = messages.get(0).getContent();
            String last = messages.get(messages.size() - 1).getContent();
            if (PlanSectionWriter.REWRITE_SYSTEM.equals(system)) {
                String old = between(last, "SENTENCE: \"\"\"", "\"\"\"");
                for (Map.Entry<String, String> rewrite : rewrites.entrySet()) {
                    if (old.startsWith(rewrite.getKey())) {
                        return rewrite.getValue() == null ? MainModelChatResult.failure(
                                MainModelChatResult.Status.TIMEOUT, "timeout") : MainModelChatResult.ok(
                                "{\"sentence\":\"" + rewrite.getValue() + "\"}");
                    }
                }
                return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE, "no rewrite");
            }
            String title = between(messages.get(1).getContent(), "SECTION: ", "\n");
            String draft = drafts.get(title);
            return draft == null ? MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    "no draft") : MainModelChatResult.ok(draft);
        });
    }

    /**
     * The checker: a verdict by the start of the sentence, or of the sentence and the passage ("Die Blitzer|Unsere");
     * asked fields get the given or a passing answer.
     */
    static Scripted checker(Map<String, String[]> verdicts) {
        return new Scripted(messages -> {
            String prompt = messages.get(1).getContent();
            String sentence = between(prompt, "Satz aus dem Buch:\n„", "“\n\nZitierte Passage:");
            String passage = between(prompt, "Zitierte Passage:\n„", "“\n\nPrüfe");
            String[] said = {"NICHT_GEDECKT", "", "Die Passage sagt dazu nichts.", null, null};
            for (Map.Entry<String, String[]> verdict : verdicts.entrySet()) {
                String[] key = verdict.getKey().split("\\|", 2);
                if (sentence.startsWith(key[0]) && (key.length == 1 || passage.startsWith(key[1]))) {
                    said = verdict.getValue();
                }
            }
            JsonObject json = new JsonObject();
            json.addProperty("belegart", "TEXT");
            json.addProperty("zitat", said[1]);
            json.addProperty("grund", said[2]);
            json.addProperty("urteil", said[0]);
            if (prompt.contains("- zuschreibung:")) {
                json.addProperty("zuschreibung", said.length > 3 && said[3] != null ? said[3] : "ZUGESCHRIEBEN");
            }
            if (prompt.contains("- rechtsraum:")) {
                json.addProperty("rechtsraum", said.length > 4 && said[4] != null ? said[4] : "GEKENNZEICHNET");
            }
            return MainModelChatResult.ok(json.toString());
        });
    }

    static String[] says(String verdict, String quote, String reason) {
        return new String[]{verdict, quote, reason, null, null};
    }

    static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) {
            return "";
        }
        from += start.length();
        int to = text.indexOf(end, from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    static SectionPlan.Section watch() {
        return section("abschnitt-w", "Überwachung", 60,
                Arrays.asList(ev("p1", "stadt-a", "q1"), ev("p3", "blitzer", "q5")),
                Collections.<SectionPlan.Evidence>emptyList(),
                Arrays.asList(q("q1", Q1, QuestionEvidence.LABEL_DIRECT), q("q5", Q5, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
    }

    @Test
    public void everySectionPromptShowsMissionAndWeidezaunWithWhatIsExcluded() throws Exception {
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1].");
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 senkt", says("GEDECKT", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich",
                "Die Passage sagt das."));
        Fence fence = new Fence("Verkehrsruhe in Wohnstraßen", "", Arrays.asList("Lärm"),
                Arrays.asList("Autobahnen"), "", "");

        Scripted fenced = writer(drafts, new LinkedHashMap<String, String>());
        new PlanSectionWriter(fenced, checker(said), lookup(), new SentenceVerdicts(), null, options(), CLOCK, null,
                fence).write(plan(watch()), watch());
        String prompt = fenced.requests.get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("BOOK MISSION AND WEIDEZAUN"));
        assertTrue(prompt, prompt.contains("Verkehrsruhe in Wohnstraßen"));
        assertTrue(prompt, prompt.contains("Ausgeschlossen:\n- Autobahnen"));

        Scripted open = writer(drafts, new LinkedHashMap<String, String>());
        new PlanSectionWriter(open, checker(said), lookup(), new SentenceVerdicts(), null, options(), CLOCK, null)
                .write(plan(watch()), watch());
        String without = open.requests.get(0).get(1).getContent();
        assertFalse(without, without.contains("WEIDEZAUN"));
    }

    @Test
    public void aMakersClaimStatedAsAFactIsWrittenAgainOnceAndKeptWhenItPasses() throws Exception {
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1]. Die Blitzer erkennen jedes "
                + "Fahrzeug zuverlässig [2].");
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Die Blitzer", "Nach Angaben des Herstellers erkennen seine Blitzer jedes Fahrzeug zuverlässig "
                + "[2].");
        Scripted writer = writer(drafts, rewrites);
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 senkt", says("GEDECKT", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich",
                "Die Passage sagt das."));
        said.put("Die Blitzer", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        said.put("Nach Angaben", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ZUGESCHRIEBEN", null});
        Scripted checker = checker(said);
        SentenceVerdicts verdicts = new SentenceVerdicts();
        WrittenSections store = new WrittenSections();
        List<String> log = Collections.synchronizedList(new ArrayList<String>());

        WrittenSection written = new PlanSectionWriter(writer, checker, lookup(), verdicts, store, options(), CLOCK,
                log::add).write(plan(watch()), watch());

        assertEquals(written.getFailure(), WrittenSection.Status.OK, written.getStatus());
        assertEquals("Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1]. Nach Angaben des Herstellers erkennen "
                + "seine Blitzer jedes Fahrzeug zuverlässig [2].", written.getText());
        assertTrue(written.getMarks().toString(), written.getMarks().isEmpty());
        assertEquals(1, written.count("zurueck"));
        assertEquals(1, written.count("ueberarbeitet"));
        assertEquals(1, written.count("neuAufrufe"));
        assertEquals(2, written.count("gedeckt"));
        assertEquals(0, written.count("alsTatsache"));
        assertEquals(2, written.count("mitBeleg"));
        assertEquals(3, written.count("pruefPaare"));
        assertEquals(1, written.count("schreibAufrufe"));
        assertEquals(2, written.count("zitiertePassagen"));
        assertEquals(18, written.getWords());
        assertEquals(WrittenSection.Gap.Kind.UNTER_ZIEL, written.getGaps().get(0).getKind());
        assertEquals("Der Abschnitt hat 18 von 60 geplanten Wörtern.", written.getGaps().get(0).getText());
        assertEquals("p3", written.getCitations().get(2).getPassageId());
        assertEquals(P3, written.getCitations().get(2).getPassageText());
        assertEquals("Verkehr in der Stadt", written.getChapterTitle());
        assertEquals(PlanSectionWriter.VERSION, written.getVersion());
        assertEquals(written.toJson(), store.latest("abschnitt-w").toJson());
        assertEquals("three pairs judged", 3, verdicts.size());

        List<List<ChatMessage>> asked = writer.rewrites();
        assertEquals(1, asked.size());
        String prompt = asked.get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("SENTENCE: \"\"\"Die Blitzer erkennen jedes Fahrzeug zuverlässig "
                + "[2].\"\"\""));
        assertTrue(prompt, prompt.contains("- [2] its source is a maker or seller, and the sentence states its claim "
                + "as a fact: name the maker or seller as the speaker in this sentence, e.g. „nach Angaben des "
                + "Herstellers“ or „wie ein Anbieter angibt“\n"));
        assertTrue("the sentence around it", prompt.contains("THE SENTENCE BEFORE IT: \"\"\"Tempo 30 senkt den Lärm in "
                + "Wohnstraßen deutlich.\"\"\"\n"));
        assertFalse("the last sentence has none after it", prompt.contains("THE SENTENCE AFTER IT"));
        assertTrue(prompt, prompt.contains("[2] \"\"\"" + P3 + "\"\"\" (direct evidence; a maker or seller speaks"));
        assertFalse("only the passages the sentence cites", prompt.contains(P1));
        assertTrue(writer.schemas.contains(PlanSectionWriter.REWRITE_SCHEMA));
        assertTrue(log.toString(), log.toString().contains("1 back to the writer, 1 rewritten, 0 marked"));
        assertFalse("no source text in the log", log.toString().contains("Wohnstraßen"));
    }

    @Test
    public void whatStillFailsAfterOneRewriteIsMarkedNeverDeleted() throws Exception {
        String s1 = "Tempo 30 halbiert alle Unfälle in der Stadt [2].";
        String s2 = "Die Blitzer erkennen jedes Fahrzeug zuverlässig [3].";
        String s3 = "In Wohngebieten gilt flächendeckend Tempo 30 [4].";
        String s4 = "Tempo 30 senkt den Lärm um zehn Dezibel [1].";
        String s4new = "Tempo 30 senkt den Lärm in allen Straßen um zehn Dezibel [1].";
        String s5 = "Das zeigt viel.";
        String s6 = "In Tempo-30-Zonen gibt es weniger schwere Unfälle [2].";
        String s7 = "Die Zahl der Verletzten sank um die Hälfte [2].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Tempo 30", String.join(" ", s1, s2, s3, s4, s5, s6, s7));
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Tempo 30 halbiert", "Tempo 30 senkt Unfälle [2]. Es wirkt [2].");
        rewrites.put("Die Blitzer", "Nach Angaben des Herstellers erkennen die Blitzer jedes Auto bei Regen [3].");
        rewrites.put("In Wohngebieten", null);
        rewrites.put("Tempo 30 senkt den Lärm", s4new);
        rewrites.put("Die Zahl der Verletzten", "Die Zahl der Verletzten sank um ein Viertel [1].");
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 halbiert", says("NICHT_GEDECKT", "", "Die Passage nennt ein Viertel weniger Verletzte."));
        said.put("Die Blitzer", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        said.put("Nach Angaben", says("NICHT_GEDECKT", "", "Die Passage nennt keinen Regen."));
        said.put("In Wohngebieten", new String[]{"GEDECKT", "in Wohngebieten flächendeckend Tempo 30",
                "Die Passage sagt das.", null, "NICHT_GEKENNZEICHNET"});
        said.put("Tempo 30 senkt den Lärm", says("NICHT_GEDECKT", "", "Die Passage nennt drei Dezibel."));
        said.put("In Tempo-30-Zonen", says("GEDECKT", "In Tempo-30-Zonen gibt es weniger schwere Unfälle",
                "Die Passage sagt das."));
        said.put("Die Zahl der Verletzten", says("NICHT_GEDECKT", "", "Die Passage nennt ein Viertel."));
        SectionPlan.Section section = section("abschnitt-t1", "Tempo 30", 400,
                Arrays.asList(ev("p1", "stadt-a", "q1"), ev("p2", "stadt-b", "q2"), ev("p3", "blitzer", "q5"),
                        ev("p4", "wien", "q2")),
                Collections.<SectionPlan.Evidence>emptyList(),
                Arrays.asList(q("q1", Q1, QuestionEvidence.LABEL_DIRECT), q("q2", Q2, QuestionEvidence.LABEL_DIRECT),
                        q("q5", Q5, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        Scripted writer = writer(drafts, rewrites);

        WrittenSection written = new PlanSectionWriter(writer, checker(said), lookup(), new SentenceVerdicts(), null,
                options().withAttributionTail(false), CLOCK, null).write(plan(section), section);

        assertEquals(written.getFailure(), WrittenSection.Status.OK, written.getStatus());
        assertEquals("every sentence stays, one rewritten", String.join(" ", s1, s2, s3, s4new, s5, s6, s7),
                written.getText());
        List<WrittenSection.Mark> marks = written.getMarks();
        assertEquals(marks.toString(), 6, marks.size());
        assertMark(marks.get(0), WrittenSection.Mark.Kind.NICHT_GEDECKT, s1, "NICHT_GEDECKT", "[2] Die Passage nennt "
                + "ein Viertel weniger Verletzte. · Neufassung ungültig: 2 Sätze statt einem", false);
        assertMark(marks.get(1), WrittenSection.Mark.Kind.ALS_TATSACHE, s2, "GEDECKT", "[3] Aussage einer Quelle mit "
                + "Eigeninteresse als Tatsache · Neufassung verworfen (nicht gedeckt)", false);
        assertMark(marks.get(2), WrittenSection.Mark.Kind.RECHTSRAUM_OFFEN, s3, "GEDECKT", "[4] Regel eines anderen "
                + "Rechtsraums ohne Angabe, wo sie gilt · Neufassung fehlgeschlagen", false);
        assertMark(marks.get(3), WrittenSection.Mark.Kind.NICHT_GEDECKT, s4new, "NICHT_GEDECKT", "[1] Die Passage "
                + "nennt drei Dezibel. · Neufassung behalten, besteht weiterhin nicht", true);
        assertMark(marks.get(4), WrittenSection.Mark.Kind.OHNE_BELEG, s5, "", "zitiert keine Passage", false);
        assertMark(marks.get(5), WrittenSection.Mark.Kind.NICHT_GEDECKT, s7, "NICHT_GEDECKT", "[2] Die Passage nennt "
                + "ein Viertel. · Neufassung ungültig: zitiert eine Passage, die der alte Satz nicht zitiert", false);
        assertEquals(7, written.count("saetze"));
        assertEquals(6, written.count("mitBeleg"));
        assertEquals(5, written.count("zurueck"));
        assertEquals(1, written.count("ueberarbeitet"));
        assertEquals(5, written.count("neuAufrufe"));
        assertEquals(6, written.count("markiert"));
        assertEquals(1, written.count("ohneBeleg"));
        assertEquals(3, written.count("nichtGedeckt"));
        assertEquals(3, written.count("gedeckt"));
        assertEquals(1, written.count("alsTatsache"));
        assertEquals(1, written.count("rechtsraumOffen"));
        assertEquals("six pairs, then the two rewrites the check judged", 8, written.count("pruefPaare"));
        assertEquals(5, writer.rewrites().size());
    }

    @Test
    public void aClaimWhoseSentenceNamesItsSpeakerIsNoFactAndDoesNotGoBack() throws Exception {
        String named = "Nach Angaben des Herstellers erkennen die Blitzer jedes Fahrzeug zuverlässig [2].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1]. " + named);
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 senkt", says("GEDECKT", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich",
                "Die Passage sagt das."));
        said.put("Nach Angaben", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        Scripted writer = writer(drafts, new LinkedHashMap<String, String>());
        SentenceVerdicts verdicts = new SentenceVerdicts();
        PlanSectionWriter planWriter = new PlanSectionWriter(writer, checker(said), lookup(), verdicts, null,
                options(), CLOCK, null);

        WrittenSection written = planWriter.write(plan(watch()), watch());

        assertEquals(written.getFailure(), WrittenSection.Status.OK, written.getStatus());
        assertTrue(written.getMarks().toString(), written.getMarks().isEmpty());
        assertEquals("the check said fact, the sentence names the maker", 1, written.count("zuschreibungImSatz"));
        assertEquals(0, written.count("alsTatsache"));
        assertEquals(0, written.count("zurueck"));
        assertEquals(0, written.count("sprecherEingefuegt"));
        assertTrue(writer.rewrites().isEmpty());
        List<BookSection.Citation> cited = Collections.singletonList(
                new BookSection.Citation(2, "p3", "blitzer", P3));
        assertTrue("the stored verdict stays as the model gave it", SentenceLookup.of(verdicts).of(named, cited)
                .isStatedAsFact());
        assertFalse("the mission report reads it through the writer", planWriter.sentenceLookup().of(named, cited)
                .isStatedAsFact());
        assertEquals(Verdict.GEDECKT, planWriter.sentenceLookup().of(named, cited).getVerdict());
    }

    @Test
    public void aClaimThatStillStandsAsAFactGetsItsSpeakerPutInAndIsCheckedAgain() throws Exception {
        String fact = "Die Blitzer erkennen jedes Fahrzeug zuverlässig [2].";
        String first = "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", first + " " + fact);
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Die Blitzer", null);
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 senkt", says("GEDECKT", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich",
                "Die Passage sagt das."));
        said.put("Die Blitzer", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});

        WrittenSection written = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(watch()), watch());

        assertEquals(first + " Die Blitzer erkennen jedes Fahrzeug zuverlässig, wie ein Anbieter angibt [2].",
                written.getText());
        assertTrue(written.getMarks().toString(), written.getMarks().isEmpty());
        assertEquals(1, written.count("sprecherEingefuegt"));
        assertEquals(0, written.count("alsTatsache"));
        assertEquals("the check still calls it a fact, the sentence names the seller", 1,
                written.count("zuschreibungImSatz"));
        assertEquals(1, written.count("zurueck"));
        assertEquals(0, written.count("ueberarbeitet"));
        assertEquals("two pairs, then the sentence with its speaker", 3, written.count("pruefPaare"));
        assertEquals(2, written.count("gedeckt"));

        said.put("Die Blitzer erkennen jedes Fahrzeug zuverlässig, wie", says("NICHT_GEDECKT", "",
                "Die Passage nennt keinen Anbieter."));
        WrittenSection refused = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(watch()), watch());
        assertEquals("not worse: the old sentence stays", first + " " + fact, refused.getText());
        assertEquals(refused.getMarks().toString(), 1, refused.getMarks().size());
        assertMark(refused.getMarks().get(0), WrittenSection.Mark.Kind.ALS_TATSACHE, fact, "GEDECKT", "[2] Aussage "
                + "einer Quelle mit Eigeninteresse als Tatsache · Neufassung fehlgeschlagen · Sprecher eingefügt, "
                + "verworfen (nicht gedeckt)", false);
        assertEquals(0, refused.count("sprecherEingefuegt"));
        assertEquals(1, refused.count("alsTatsache"));

        WrittenSection off = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options().withAttributionTail(false), CLOCK, null).write(plan(watch()),
                watch());
        assertEquals(first + " " + fact, off.getText());
        assertEquals("two pairs only", 2, off.count("pruefPaare"));
        assertEquals(1, off.count("alsTatsache"));
    }

    @Test
    public void aSourceNamedByALetterGoesBackAndIsMarkedWhenItStays() throws Exception {
        String lettered = "Laut Quelle B senkt Tempo 30 den Lärm in Wohnstraßen deutlich [1].";
        String fact = "Die Blitzer erkennen jedes Fahrzeug zuverlässig [2].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", lettered + " " + fact);
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Laut Quelle B", "Laut der Stadt senkt Tempo 30 den Lärm in Wohnstraßen deutlich [1].");
        rewrites.put("Die Blitzer", "Laut Quelle A erkennen die Blitzer jedes Fahrzeug zuverlässig [2].");
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Laut", says("GEDECKT", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich", "Die Passage sagt das."));
        said.put("Die Blitzer", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        Scripted writer = writer(drafts, rewrites);

        WrittenSection written = new PlanSectionWriter(writer, checker(said), lookup(), new SentenceVerdicts(), null,
                options().withAttributionTail(false), CLOCK, null).write(plan(watch()), watch());

        assertEquals("Laut der Stadt senkt Tempo 30 den Lärm in Wohnstraßen deutlich [1]. " + fact,
                written.getText());
        assertEquals(written.getMarks().toString(), 1, written.getMarks().size());
        assertMark(written.getMarks().get(0), WrittenSection.Mark.Kind.ALS_TATSACHE, fact, "GEDECKT", "[2] Aussage "
                + "einer Quelle mit Eigeninteresse als Tatsache · Neufassung ungültig: nennt eine Quelle mit einem "
                + "Buchstaben", false);
        assertEquals(2, written.count("zurueck"));
        assertEquals(1, written.count("ueberarbeitet"));
        assertEquals(0, written.count("quelleBuchstabe"));
        String prompt = writer.rewrites().get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("WHAT THE CHECK FOUND:\n- " + PlanSectionWriter.LETTER_FINDING + "\n"));

        rewrites.put("Laut Quelle B", null);
        WrittenSection stays = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options().withAttributionTail(false), CLOCK, null).write(plan(watch()),
                watch());
        assertEquals(stays.getMarks().toString(), 2, stays.getMarks().size());
        assertMark(stays.getMarks().get(0), WrittenSection.Mark.Kind.QUELLE_BUCHSTABE, lettered, "GEDECKT", "nennt "
                + "eine Quelle mit einem Buchstaben, den kein Leser kennt · Neufassung fehlgeschlagen", false);
        assertEquals(1, stays.count("quelleBuchstabe"));
        assertEquals(WrittenSection.Mark.Kind.QUELLE_BUCHSTABE, WrittenSection.fromJson(stays.toJson()).getMarks()
                .get(0).getKind());
    }

    /** The review of 22.11.2026: a letter alone sends back only a sentence the check judged. */
    @Test
    public void aLetterSendsBackNoSentenceTheCheckDidNotJudge() throws Exception {
        String lettered = "Laut Quelle B senkt Tempo 30 den Lärm in Wohnstraßen deutlich [1].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", lettered);
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Laut Quelle B", "Laut der Stadt sinkt der Lärm in allen Straßen um die Hälfte [1].");

        Scripted writer = writer(drafts, rewrites);
        WrittenSection unchecked = new PlanSectionWriter(writer, null, lookup(), new SentenceVerdicts(), null,
                options(), CLOCK, null).write(plan(watch()), watch());
        assertEquals("no check: nothing to measure a rewrite against", lettered, unchecked.getText());
        assertEquals(0, unchecked.count("zurueck"));
        assertTrue(writer.rewrites().isEmpty());

        Scripted failing = new Scripted(messages -> MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT,
                "timeout"));
        WrittenSection failed = new PlanSectionWriter(writer(drafts, rewrites), failing, lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(watch()), watch());
        assertEquals("a failed check: the sentence stays", lettered, failed.getText());
        assertEquals(0, failed.count("zurueck"));
    }

    /** The review of c10b: "Der Hersteller conrad.at"; the writer and the rewrite get the site's own name. */
    @Test
    public void theWriterAndTheRewriteNameASourceByItsSitesOwnName() throws Exception {
        PassageLookup sites = PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("p1", "stadt-a", "https://stadt-a.example/laerm", P1, "BEHOERDE", "DE"),
                new PassageLookup.Passage("p3", "blitzer", "https://www.blitzer.example/kamera", P3,
                        "HERSTELLER_HANDEL", "DE")));
        String first = "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1].";
        String fact = "Die Blitzer erkennen jedes Fahrzeug zuverlässig [2].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", first + " " + fact);
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Die Blitzer", null);
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 senkt", says("GEDECKT", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich",
                "Die Passage sagt das."));
        said.put("Die Blitzer", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        Scripted writer = writer(drafts, rewrites);

        new PlanSectionWriter(writer, checker(said), sites, new SentenceVerdicts(), null, options(), CLOCK, null)
                .write(plan(watch()), watch());

        String draft = writer.requests.get(0).get(1).getContent();
        assertTrue(draft, draft.contains("[2] \"\"\"" + P3 + "\"\"\" (source Blitzer; direct evidence; a maker or "
                + "seller speaks"));
        assertFalse(draft, draft.contains("blitzer.example"));
        assertEquals("only the maker's sentence goes back", 1, writer.rewrites().size());
        String rewrite = writer.rewrites().get(0).get(1).getContent();
        assertTrue(rewrite, rewrite.contains("- [2] its source is a maker or seller (Blitzer), and the sentence "
                + "states its claim as a fact"));
    }

    /** One speaker phrase for one marker group: an association's claim must not go to "a vendor". */
    @Test
    public void aSpeakerIsPutInOnlyWhereOnePhraseFitsEveryClaimOfTheGroup() throws Exception {
        SectionPlan.Section control = section("abschnitt-k", "Kontrolle", 60,
                Arrays.asList(ev("p3", "blitzer", "q5"), ev("p5", "verband", "q3")),
                Collections.<SectionPlan.Evidence>emptyList(),
                Arrays.asList(q("q5", Q5, QuestionEvidence.LABEL_DIRECT), q("q3", Q3, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        String shared = "Mehr Kontrollen machen Tempo-30-Zonen sicherer [1, 2].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Kontrolle", shared);
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Mehr Kontrollen", null);
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Mehr Kontrollen", new String[]{"GEDECKT", "", "Die Passage sagt das.", "ALS_TATSACHE", null});

        WrittenSection one = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(control), control);
        assertEquals(shared, one.getText());
        assertEquals(0, one.count("sprecherEingefuegt"));

        String both = "Mehr Kontrollen machen Tempo-30-Zonen sicherer [1] und senken die Unfallzahlen [2].";
        drafts.put("Kontrolle", both);
        WrittenSection two = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(control), control);
        assertEquals("the review of c10b: one clause, two sources, no speaker that reads as both", both,
                two.getText());
        assertEquals(0, two.count("sprecherEingefuegt"));
        assertEquals(1, two.count("alsTatsache"));
    }

    /** The review of c10b: "[15] …, wie ein Anbieter angibt [12]" read as the speaker of both sources. */
    @Test
    public void aSpeakerGoesOnlyIntoAClauseThatCitesNoOtherSource() throws Exception {
        String clauses = "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1]; die Blitzer erkennen jedes Fahrzeug "
                + "zuverlässig [2].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", clauses);
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Tempo 30", null);
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30", new String[]{"GEDECKT", "", "Die Passage sagt das.", "ALS_TATSACHE", null});

        WrittenSection own = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(watch()), watch());
        assertEquals("Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1]; die Blitzer erkennen jedes Fahrzeug "
                + "zuverlässig, wie ein Anbieter angibt [2].", own.getText());
        assertEquals(1, own.count("sprecherEingefuegt"));
        assertTrue(own.getMarks().toString(), own.getMarks().isEmpty());

        String comma = "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1], und die Blitzer erkennen jedes Fahrzeug "
                + "zuverlässig [2].";
        drafts.put("Überwachung", comma);
        WrittenSection shared = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(watch()), watch());
        assertEquals("the city's claim shares the clause", comma, shared.getText());
        assertEquals(0, shared.count("sprecherEingefuegt"));
        assertEquals(shared.getMarks().toString(), 1, shared.getMarks().size());
        assertEquals(WrittenSection.Mark.Kind.ALS_TATSACHE, shared.getMarks().get(0).getKind());

        PassageLookup sites = PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("p1", "stadt-a", "https://stadt-a.example/laerm", P1, "BEHOERDE", "DE"),
                new PassageLookup.Passage("p7", "stadt-a-2", "https://www.stadt-a.example/tempo", P2, "BEHOERDE", "DE"),
                new PassageLookup.Passage("p3", "blitzer", "", P3, "HERSTELLER_HANDEL", "DE"),
                new PassageLookup.Passage("p8", "blitzer", "", P5, "HERSTELLER_HANDEL", "DE")));
        List<BookSection.Citation> four = Arrays.asList(new BookSection.Citation(1, "p1", "stadt-a", P1),
                new BookSection.Citation(2, "p3", "blitzer", P3), new BookSection.Citation(3, "p7", "stadt-a-2", P2),
                new BookSection.Citation(4, "p8", "blitzer", P5));
        assertEquals("one site, two ids", new HashSet<Integer>(Arrays.asList(1, 3)),
                PlanSectionWriter.sameSource(sites, four.get(0), four));
        assertEquals("one id, no site", new HashSet<Integer>(Arrays.asList(2, 4)),
                PlanSectionWriter.sameSource(sites, four.get(1), four));

        SectionPlan.Section blitzer = section("abschnitt-b", "Blitzer", 60, Arrays.asList(ev("p3", "blitzer", "q5"),
                ev("p8", "blitzer", "q5")), Collections.<SectionPlan.Evidence>emptyList(),
                Collections.singletonList(q("q5", Q5, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        String same = "Die Blitzer erkennen jedes Fahrzeug [1] und warnen den Fahrer [2].";
        drafts.put("Blitzer", same);
        rewrites.put("Die Blitzer", null);
        said.put("Die Blitzer|" + P3.substring(0, 12), new String[]{"GEDECKT", "", "Die Passage sagt das.",
                "ALS_TATSACHE", null});
        said.put("Die Blitzer|" + P5.substring(0, 12), new String[]{"GEDECKT", "", "Die Passage sagt das.",
                "ZUGESCHRIEBEN", null});
        WrittenSection one = new PlanSectionWriter(writer(drafts, rewrites), checker(said), sites,
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(blitzer), blitzer);
        assertEquals("another passage of the same source shares the clause", "Die Blitzer erkennen jedes Fahrzeug, "
                + "wie ein Anbieter angibt [1], und warnen den Fahrer [2].", one.getText());
        assertEquals(1, one.count("sprecherEingefuegt"));

        String reported = "Laut einer Quelle erkennen die Blitzer jedes Fahrzeug zuverlässig [2].";
        drafts.put("Überwachung", reported);
        said.put("Laut", new String[]{"GEDECKT", "", "Die Passage sagt das.", "ALS_TATSACHE", null});
        rewrites.put("Laut", null);
        WrittenSection beside = new PlanSectionWriter(writer(drafts, rewrites), checker(said), lookup(),
                new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(watch()), watch());
        assertEquals("no speaker beside \"laut einer Quelle\"", reported, beside.getText());
        assertEquals(0, beside.count("sprecherEingefuegt"));
    }

    /** The review of 22.11.2026: "… zuverlässig., wie ein Anbieter angibt [2] Tempo 30 …" fused two sentences. */
    @Test
    public void anInsertedSpeakerMayNotFuseTwoSentences() {
        String text = "Die Blitzer erkennen jedes Fahrzeug zuverlässig. [2] Tempo 30 senkt den Lärm [1].";
        String old = "Die Blitzer erkennen jedes Fahrzeug zuverlässig. [2]";
        assertTrue(PlanSectionWriter.keepsItsSentences(text, 0, old, "Die Blitzer erkennen jedes Fahrzeug "
                + "zuverlässig, wie ein Anbieter angibt. [2]", 0));
        assertFalse(PlanSectionWriter.keepsItsSentences(text, 0, old, "Die Blitzer erkennen jedes Fahrzeug "
                + "zuverlässig., wie ein Anbieter angibt [2]", 0));
        String second = "Tempo 30 senkt den Lärm [1].";
        assertTrue(PlanSectionWriter.keepsItsSentences(text, text.indexOf(second), second, "Tempo 30 senkt den Lärm, "
                + "wie ein Anbieter angibt [1].", 1));
        assertFalse("another place in the section", PlanSectionWriter.keepsItsSentences(text, text.indexOf(second),
                second, "Tempo 30 senkt den Lärm, wie ein Anbieter angibt [1].", 0));
    }

    @Test
    public void aRewriteSeesTheSentencesAroundItAndMayNotRepeatThem() throws Exception {
        String first = "Tempo 30 senkt den Lärm in Wohnstraßen um drei Dezibel [1].";
        String fact = "Die Blitzer erkennen jedes Fahrzeug zuverlässig [2].";
        String third = "Messungen der Stadt ergaben drei Dezibel weniger [1].";
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", String.join(" ", first, fact, third));
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Die Blitzer", "Nach Angaben des Herstellers gilt: Tempo 30 senkt den Lärm in Wohnstraßen um "
                + "drei Dezibel [2].");
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 senkt", says("GEDECKT", "Tempo 30 senkt den Lärm", "Die Passage sagt das."));
        said.put("Messungen", says("GEDECKT", "drei Dezibel weniger", "Die Passage sagt das."));
        said.put("Die Blitzer", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        Scripted writer = writer(drafts, rewrites);

        WrittenSection written = new PlanSectionWriter(writer, checker(said), lookup(), new SentenceVerdicts(), null,
                options().withAttributionTail(false), CLOCK, null).write(plan(watch()), watch());

        assertEquals(String.join(" ", first, fact, third), written.getText());
        assertMark(written.getMarks().get(0), WrittenSection.Mark.Kind.ALS_TATSACHE, fact, "GEDECKT", "[2] Aussage "
                + "einer Quelle mit Eigeninteresse als Tatsache · Neufassung ungültig: wiederholt einen anderen Satz "
                + "des Abschnitts", false);
        String prompt = writer.rewrites().get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("THE SENTENCE BEFORE IT: \"\"\"Tempo 30 senkt den Lärm in Wohnstraßen um "
                + "drei Dezibel.\"\"\"\nTHE SENTENCE AFTER IT: \"\"\"Messungen der Stadt ergaben drei Dezibel "
                + "weniger.\"\"\"\nWHAT THE CHECK FOUND:"));
        assertTrue(prompt, prompt.contains(" Do not repeat what the sentences before and after it say. Answer as "
                + "JSON"));
        assertTrue(prompt, prompt.contains("Never name a source by a letter such as „Quelle A“"));
    }

    @Test
    public void aLetterForASourceAndARepeatedRunOfWordsAreFound() {
        assertTrue(PlanSectionWriter.letterLabel("Laut Quelle C sinken die Kosten [1]."));
        assertTrue(PlanSectionWriter.letterLabel("Die Quellen A und B widersprechen sich [1, 2]."));
        assertTrue(PlanSectionWriter.letterLabel("As source B shows, costs fall [1]."));
        assertTrue(PlanSectionWriter.letterLabel("So steht es in Quelle: D [1]."));
        assertFalse(PlanSectionWriter.letterLabel("Laut Quelle ADAC sinken die Kosten [1]."));
        assertFalse(PlanSectionWriter.letterLabel("Eine Quelle aus Wien nennt das [1]."));
        assertFalse(PlanSectionWriter.letterLabel("Die Quelle Z-Wert fehlt [1]."));
        assertFalse(PlanSectionWriter.letterLabel(null));

        String fresh = "Kurz: Tempo 30 senkt den Lärm, in Wohnstraßen um drei Dezibel [2].";
        List<String> sentences = Arrays.asList("Tempo 30 senkt den Lärm in Wohnstraßen um drei Dezibel [1].",
                "Alt [2].");
        assertTrue("case, punctuation and markers aside", PlanSectionWriter.sharesRun(fresh, sentences.get(0)));
        assertFalse("seven words are no run", PlanSectionWriter.sharesRun("Tempo 30 senkt den Lärm in Wohnstraßen "
                + "[1].", "Tempo 30 senkt den Lärm in Wohnstraßen [2]."));
        assertEquals("wiederholt einen anderen Satz des Abschnitts", PlanSectionWriter.repeated(fresh, "Alt [2].",
                sentences, 1));
        assertNull("the old sentence shared it already", PlanSectionWriter.repeated(fresh, "Tempo 30 senkt den Lärm "
                + "in Wohnstraßen um drei Dezibel, sagt die Stadt [2].", sentences, 1));
        assertNull("its own place does not count", PlanSectionWriter.repeated(fresh, "Alt [1].", sentences, 0));
        assertEquals("Tempo 30 senkt den Lärm um drei Dezibel.", PlanSectionWriter.neighbour(Collections
                .singletonList("Tempo 30 senkt den Lärm [1, 2] um drei Dezibel [3]."), 0));
        assertEquals("", PlanSectionWriter.neighbour(sentences, -1));
        assertEquals("", PlanSectionWriter.neighbour(sentences, 2));
    }

    private static void assertMark(WrittenSection.Mark mark, WrittenSection.Mark.Kind kind, String sentence,
                                   String verdict, String reason, boolean rewritten) {
        assertEquals(mark.toJson().toString(), kind, mark.getKind());
        assertEquals(sentence, mark.getSentence());
        assertEquals(verdict, mark.getVerdict());
        assertEquals(reason, mark.getReason());
        assertEquals(rewritten, mark.isRewritten());
    }

    @Test
    public void aSectionWithoutTextIsNotWrittenAndAFailedWriterIsAnError() throws Exception {
        SectionPlan.Section empty = section("abschnitt-leer", "Kontrolle", 300,
                Arrays.asList(ev("p6", "fehlt", "q6"), ev("leer", "stadt-c", "q6")),
                Collections.<SectionPlan.Evidence>emptyList(),
                Arrays.asList(q("q6", Q6, QuestionEvidence.LABEL_DIRECT), q("q4", Q4, QuestionEvidence.LABEL_NONE)),
                Collections.<SectionPlan.Contradiction>emptyList());
        Scripted writer = writer(new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>());
        WrittenSections store = new WrittenSections();
        PlanSectionWriter planWriter = new PlanSectionWriter(writer, null, lookup(), new SentenceVerdicts(), store,
                options(), CLOCK, null);

        WrittenSection nothing = planWriter.write(plan(empty), empty);
        assertEquals(WrittenSection.Status.OHNE_STOFF, nothing.getStatus());
        assertEquals("no call without material", 0, writer.calls.get());
        assertEquals(Arrays.asList("p6", "leer"), nothing.getWithoutText());
        assertEquals(2, nothing.getGaps().size());
        assertEquals("", nothing.getText());

        WrittenSection failed = planWriter.write(plan(watch()), watch());
        assertEquals(WrittenSection.Status.FEHLER, failed.getStatus());
        assertEquals("Schreiber: no draft", failed.getFailure());
        assertEquals("", failed.getText());
        assertEquals(1, failed.count("schreibAufrufe"));
        assertEquals("both are stored", 2, store.size());
        assertEquals(failed.toJson(), store.latest("abschnitt-w").toJson());
    }

    @Test
    public void withoutACheckerNoSentenceGoesBackAndNoneIsMarkedForItsCoverage() throws Exception {
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1].");
        WrittenSection written = new PlanSectionWriter(writer(drafts, new LinkedHashMap<String, String>()), null,
                lookup(), new SentenceVerdicts(), null, options(), CLOCK, null).write(plan(watch()), watch());
        assertEquals(WrittenSection.Status.OK, written.getStatus());
        assertEquals(1, written.count("ungeprueft"));
        assertEquals(0, written.count("zurueck"));
        assertTrue(written.getMarks().isEmpty());
    }

    @Test
    public void allSectionsAreWrittenInParallelAndComeBackInThePlansOrder() throws Exception {
        List<SectionPlan.Section> sections = new ArrayList<SectionPlan.Section>();
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        String[] texts = {P1, P2, P4};
        String[] sentences = {"Tempo 30 senkt den Lärm in Wohnstraßen deutlich",
                "In Tempo-30-Zonen gibt es weniger schwere Unfälle", "In Wien gilt in Wohngebieten flächendeckend "
                + "Tempo 30"};
        String[] ids = {"p1", "p2", "p4"};
        String[] sources = {"stadt-a", "stadt-b", "wien"};
        for (int i = 0; i < 3; i++) {
            sections.add(section("abschnitt-" + i, "Teil " + i, 0, Collections.singletonList(ev(ids[i], sources[i],
                    "q" + i)), Collections.<SectionPlan.Evidence>emptyList(), Collections.singletonList(q("q" + i,
                    "Frage " + i + "?", QuestionEvidence.LABEL_DIRECT)),
                    Collections.<SectionPlan.Contradiction>emptyList()));
            drafts.put("Teil " + i, sentences[i] + " [1].");
            said.put(sentences[i], says("GEDECKT", sentences[i], "Die Passage sagt das."));
        }
        assertTrue(texts[2].contains(sentences[2]));
        WrittenSections store = new WrittenSections();
        PlanSectionWriter planWriter = new PlanSectionWriter(writer(drafts, new LinkedHashMap<String, String>()),
                checker(said), lookup(), new SentenceVerdicts(), store, options().withParallel(2), CLOCK, null);
        SectionPlan plan = plan(sections.toArray(new SectionPlan.Section[0]));

        List<WrittenSection> all = planWriter.writeAll(plan);

        assertEquals(3, all.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("abschnitt-" + i, all.get(i).getSectionId());
            assertEquals(all.get(i).getFailure(), WrittenSection.Status.OK, all.get(i).getStatus());
            assertTrue("a section without planned words has no length gap", all.get(i).getGaps().isEmpty());
        }
        assertEquals(1, all.get(2).count("rechtsraumOffen") + all.get(2).count("gedeckt"));
        assertEquals(3, store.all().size());
        List<WrittenSection> one = planWriter.writeAll(plan, Collections.singletonList("abschnitt-1"));
        assertEquals(1, one.size());
        assertEquals("abschnitt-1", one.get(0).getSectionId());
        assertTrue(planWriter.writeAll(plan, Collections.singletonList("fehlt")).isEmpty());
    }

    @Test
    public void oneAtATimeTheSectionsAreWrittenInOrderWithTheWritingMemory() throws Exception {
        List<SectionPlan.Section> sections = new ArrayList<SectionPlan.Section>();
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        String[] sentences = {"Tempo 30 senkt den Lärm in Wohnstraßen deutlich",
                "In Tempo-30-Zonen gibt es weniger schwere Unfälle"};
        String[] ids = {"p1", "p2"};
        String[] sources = {"stadt-a", "stadt-b"};
        for (int i = 0; i < 2; i++) {
            sections.add(section("abschnitt-" + i, "Teil " + i, 0, Collections.singletonList(ev(ids[i], sources[i],
                    "q" + i)), Collections.<SectionPlan.Evidence>emptyList(), Collections.singletonList(q("q" + i,
                    "Frage " + i + "?", QuestionEvidence.LABEL_DIRECT)),
                    Collections.<SectionPlan.Contradiction>emptyList()));
            drafts.put("Teil " + i, sentences[i] + " [1].");
            said.put(sentences[i], says("GEDECKT", sentences[i], "Die Passage sagt das."));
        }
        Scripted writer = writer(drafts, new LinkedHashMap<String, String>());
        PlanSectionWriter planWriter = new PlanSectionWriter(writer, checker(said), lookup(), new SentenceVerdicts(),
                null, options().withParallel(1), CLOCK, null);

        List<WrittenSection> all = planWriter.writeAll(plan(sections.toArray(new SectionPlan.Section[0])));

        assertEquals(2, all.size());
        List<String> prompts = new ArrayList<String>();
        for (List<ChatMessage> request : writer.requests) {
            if (!PlanSectionWriter.REWRITE_SYSTEM.equals(request.get(0).getContent())) {
                prompts.add(request.get(request.size() - 1).getContent());
            }
        }
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0), prompts.get(0).contains("Folgt später, hier nicht vorwegnehmen: „Teil 1“ "
                + "(Frage 1?)"));
        assertFalse(prompts.get(0).contains("PREVIOUS SECTION"));
        assertTrue(prompts.get(1), prompts.get(1).contains("PREVIOUS SECTION (digest, do not repeat it):\n"
                + sentences[0] + "."));
        assertFalse(prompts.get(1).contains("Folgt später"));
        assertEquals(1, all.get(0).count("gedaechtnisZeilen"));
        assertEquals(0, all.get(1).count("gedaechtnisZeilen"));
    }

    // --------------------------------------------------------------------------------------------- the rewrite

    @Test
    public void theRewriteTakesOneSentenceThatCitesOnlyItsPassagesAndSaysSomethingElse() {
        assertEquals("Ein Satz [2].", PlanSectionWriter.parseSentence("```json\n{\"sentence\": \" Ein  Satz [2]. "
                + "\"}\n```"));
        assertNull(PlanSectionWriter.parseSentence("kein JSON"));
        assertNull(PlanSectionWriter.parseSentence("{\"satz\":\"x\"}"));
        assertNull(PlanSectionWriter.parseSentence("{\"sentence\":[1]}"));
        java.util.Set<Integer> allowed = new java.util.TreeSet<Integer>(Arrays.asList(1, 2));
        assertNull(PlanSectionWriter.invalid("Tempo 30 wirkt [1, 2].", "Tempo 30 hilft [1].", allowed));
        assertEquals("keine lesbare Antwort", PlanSectionWriter.invalid(null, "x [1].", allowed));
        assertEquals("leerer Satz", PlanSectionWriter.invalid("", "x [1].", allowed));
        assertEquals("2 Sätze statt einem", PlanSectionWriter.invalid("Eins [1]. Zwei [2].", "x [1].", allowed));
        assertEquals("zitiert keine Passage", PlanSectionWriter.invalid("Ohne Beleg.", "x [1].", allowed));
        assertEquals("zitiert eine Passage, die der alte Satz nicht zitiert",
                PlanSectionWriter.invalid("Mit drei [3].", "x [1].", allowed));
        assertEquals("unverändert", PlanSectionWriter.invalid("Tempo 30 hilft [2].", "Tempo 30 hilft [1].",
                allowed));

        List<ChatMessage> prompt = PlanSectionWriter.rewritePrompt("German", "Tempo 30", "Der Satz [2].",
                Collections.singletonList("[2] it does not carry the sentence: x"), Collections.singletonList(
                        new EvidencePassage(2, "s", "", "p2", "Text \"\"\"zwei\"\"\".", "direct evidence")));
        assertEquals(PlanSectionWriter.REWRITE_SYSTEM, prompt.get(0).getContent());
        String user = prompt.get(1).getContent();
        assertTrue(user, user.startsWith("Book language: German.\n\nSECTION: Tempo 30\nSENTENCE: \"\"\"Der Satz "
                + "[2].\"\"\"\nWHAT THE CHECK FOUND:\n- [2] it does not carry the sentence: x\nPASSAGES THE "
                + "SENTENCE CITES:\n[2] \"\"\"Text \"zwei\".\"\"\" (direct evidence)\n\nWrite this one sentence "
                + "again, in German."));
        assertTrue(user, user.endsWith("Answer as JSON: {\"sentence\": \"...\"}"));
        assertEquals("sentence-rewrite", PlanSectionWriter.REWRITE_ROLE.getId());
        assertEquals("phase.draft.writer", PlanSectionWriter.REWRITE_ROLE.getSlot().getName());
    }

    @Test
    public void aRewriteIsRankedByWhatTheCheckFound() {
        SentenceLookup.State covered = new SentenceLookup.State(Verdict.GEDECKT, false, false);
        SentenceLookup.State partly = new SentenceLookup.State(Verdict.TEILWEISE, false, false);
        SentenceLookup.State fact = new SentenceLookup.State(Verdict.GEDECKT, true, false);
        SentenceLookup.State foreign = new SentenceLookup.State(Verdict.TEILWEISE, false, true);
        SentenceLookup.State not = new SentenceLookup.State(Verdict.NICHT_GEDECKT, false, false);
        assertEquals(3, PlanSectionWriter.rank(covered));
        assertEquals(2, PlanSectionWriter.rank(partly));
        assertEquals(1, PlanSectionWriter.rank(fact));
        assertEquals(1, PlanSectionWriter.rank(foreign));
        assertEquals(0, PlanSectionWriter.rank(not));
        assertEquals("a failed check is never kept", -1, PlanSectionWriter.rank(SentenceLookup.State.UNCHECKED));
        assertFalse(PlanSectionWriter.needsRewrite(covered));
        assertFalse("partly covered stays", PlanSectionWriter.needsRewrite(partly));
        assertTrue(PlanSectionWriter.needsRewrite(fact));
        assertTrue(PlanSectionWriter.needsRewrite(foreign));
        assertTrue(PlanSectionWriter.needsRewrite(not));
        assertFalse(PlanSectionWriter.needsRewrite(SentenceLookup.State.UNCHECKED));

        SentenceVerdict partial = SentenceVerdict.checked("s#1", "Satz [1].", "p1", "q", P1, Verdict.TEILWEISE,
                SentenceVerdict.Kind.TEXT, "Tempo 30 senkt den Lärm", "Nur zum Teil.",
                SentenceVerdict.Attribution.NICHT_GEFRAGT, SentenceVerdict.Jurisdiction.NICHT_GEKENNZEICHNET, "m", 2,
                1L, 1L);
        assertEquals("[1] it carries only part of the sentence: Nur zum Teil.; it names a rule of another legal "
                + "space, and the sentence does not say where the rule holds", PlanSectionWriter.finding(1, partial, "",
                "", false, true));
        assertEquals("", PlanSectionWriter.finding(1, null, "", "", false, true));
        SentenceVerdict asFact = SentenceVerdict.checked("s#1", "Satz [3].", "p3", "q", P3, Verdict.GEDECKT,
                SentenceVerdict.Kind.TEXT, "Blitzer erkennen jedes Fahrzeug", "Die Passage sagt das.",
                SentenceVerdict.Attribution.ALS_TATSACHE, SentenceVerdict.Jurisdiction.NICHT_GEFRAGT, "m", 2, 1L, 1L);
        assertEquals("[3] its source is an association (verband.example), and the sentence states its claim as a "
                + "fact: name the association as the speaker in this sentence, e.g. „nach Angaben des Verbands“",
                PlanSectionWriter.finding(3, asFact, "VERBAND", "verband.example", false, true));
        assertEquals("[3] its source is a maker or seller, and the sentence states its claim as a fact: name the "
                + "maker or seller as the speaker in this sentence, e.g. \"according to the manufacturer\"",
                PlanSectionWriter.finding(3, asFact, "HERSTELLER_HANDEL", "", false, false));
        assertEquals("its part names the speaker already", "", PlanSectionWriter.finding(3, asFact,
                "HERSTELLER_HANDEL", "", true, true));
    }

    // --------------------------------------------------------------------------------------------- the pieces

    @Test
    public void aPassageIsCutAfterASentenceAndNotesFollowTheSourceRating() {
        assertEquals("Kurz.", PlanSectionWriter.cut("  Kurz. ", 300));
        String text = "Erster Satz hier. Zweiter Satz folgt dort. Dritter Satz ist zu lang für den Rest.";
        assertEquals("Erster Satz hier. Zweiter Satz folgt dort.", PlanSectionWriter.cut(text, 50));
        assertEquals("no sentence end in the second half: at a blank", "Ein langes Wort ohne …",
                PlanSectionWriter.cut("Ein langes Wort ohne Punkt und Komma bis zum Schluss", 22));
        assertEquals("", PlanSectionWriter.legalNote("EU", "DE", true));
        assertEquals("", PlanSectionWriter.legalNote("OHNE", "DE", true));
        assertEquals("", PlanSectionWriter.legalNote("", "DE", true));
        assertEquals("", PlanSectionWriter.legalNote("DE", "DE", true));
        assertEquals("Swiss law: say where a rule holds, e.g. „in der Schweiz“",
                PlanSectionWriter.legalNote("CH", "DE", true));
        assertEquals("the law of another country: say in which country a rule holds",
                PlanSectionWriter.legalNote("ANDERE", "DE", true));
        assertEquals("", PlanSectionWriter.interestNote("BEHOERDE", true));
        assertEquals("an association speaks: name it as the speaker in every sentence that takes a claim from here, "
                + "e.g. \"according to the association\"", PlanSectionWriter.interestNote("VERBAND", false));
        assertEquals(", wie ein Anbieter angibt", PlanSectionWriter.speakerPhrase("HERSTELLER_HANDEL", "de"));
        assertEquals(", as an association states", PlanSectionWriter.speakerPhrase("VERBAND", "en"));
        assertEquals("", PlanSectionWriter.speakerPhrase("BEHOERDE", "de"));
        assertEquals("no phrase in another language than German or English", "",
                PlanSectionWriter.speakerPhrase("HERSTELLER_HANDEL", "fr"));
        assertEquals(Arrays.asList("stadt-a.example", "stadt-a"), PlanSectionWriter.sourceNames(lookup().find("p1")));
        assertEquals("the prompt's name: without the ending", "Stadt-A",
                PlanSectionWriter.sourceName(lookup().find("p1")));
        assertEquals("", PlanSectionWriter.sourceName(lookup().find("p3")));
        assertEquals("Blitzer", PlanSectionWriter.sourceName(new PassageLookup.Passage("x", "blitzer.example", "",
                "Text.", "", "")));
        assertEquals("", PlanSectionWriter.sourceName(null));
        assertEquals("an id without a dot is no site", Collections.<String>emptyList(),
                PlanSectionWriter.sourceNames(lookup().find("p3")));
        assertEquals("no address: the id's site", Arrays.asList("blitzer.example", "blitzer"),
                PlanSectionWriter.sourceNames(new PassageLookup.Passage("x", "blitzer.example", "", "Text.", "", "")));
        try {
            PlanSectionWriter.Options.defaults().withLegalSpace("EU");
            fail("a book is written for DE, AT or CH");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid options"));
        }
        assertEquals(10, PlanSectionWriter.Options.defaults().getPassagesPerCall());
        assertEquals(0.6, PlanSectionWriter.Options.defaults().getUnderTarget(), 0.0);
    }

    @Test
    public void aWrittenSectionSurvivesItsJsonAndGivesTheMissionReportItsView() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("saetze", 2);
        WrittenSection section = new WrittenSection("abschnitt-w", "Überwachung", "Verkehr in der Stadt", 60,
                WrittenSection.Status.OK, "", "Satz eins [1]. Satz zwei [2].", 4, Arrays.asList(
                new WrittenSection.Citation(1, "p1", "stadt-a", P1), new WrittenSection.Citation(2, "p3", "blitzer",
                        P3), new WrittenSection.Citation(1, "doppelt", "x", "y")),
                Collections.singletonList(new WrittenSection.Gap(WrittenSection.Gap.Kind.NUR_HINWEISE, "q3", Q3)),
                Collections.singletonList(new WrittenSection.Mark(WrittenSection.Mark.Kind.ALS_TATSACHE,
                        "Satz zwei [2].", "GEDECKT", "[2] Aussage", true)), Collections.singletonList("p6"), counts,
                "gemma4:12b", "abc", 1, 5L, 7L);

        WrittenSection back = WrittenSection.fromJson(section.toJson());

        assertEquals(section.toJson(), back.toJson());
        assertEquals("a marker once", 2, back.getCitations().size());
        assertEquals("p1", back.getCitations().get(1).getPassageId());
        assertTrue(back.getMarks().get(0).isRewritten());
        assertEquals(2, back.count("saetze"));
        assertEquals(0, back.count("fehlt"));
        BookSection book = back.toBookSection();
        assertEquals("abschnitt-w", book.getSectionId());
        assertEquals(60, book.getTargetWords());
        assertEquals("Satz eins [1]. Satz zwei [2].", book.getText());
        assertEquals("p3", book.citation(2).getPassageId());
        assertEquals(P3, book.citation(2).getPassageText());
        assertTrue(section.toString(), section.toString().startsWith("abschnitt-w OK, 4 of 60 words, 1 marked"));
    }

    @Test
    public void theWrittenSectionsAreAppendedReadBackAndFailClosed() throws Exception {
        File project = scratch.newFolder("projekt");
        WrittenSections store = new WrittenSections(project);
        try {
            store.record(sample("a", 1));
            fail("open first");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("open"));
        }
        assertEquals(0, store.open());
        store.record(sample("a", 1));
        store.record(sample("b", 1));
        store.record(sample("a", 2));
        File log = new File(project, "section-writer/sections.jsonl");
        assertEquals(log, store.logFile());

        WrittenSections again = new WrittenSections(project);
        assertEquals(3, again.open());
        assertEquals(3, again.size());
        assertEquals("the latest version holds", 2, again.latest("a").getWords());
        assertEquals(Arrays.asList("b", "a"), Arrays.asList(again.all().get(0).getSectionId(),
                again.all().get(1).getSectionId()));
        assertNull(again.latest("c"));

        Files.write(log.toPath(), "{\"v\":1,\"art\":\"abschnitt\"".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        WrittenSections cut = new WrittenSections(project);
        assertEquals("an unfinished last line is cut off", 3, cut.open());
        assertTrue(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).endsWith("}\n"));

        Files.write(log.toPath(), "kaputt\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        try {
            new WrittenSections(project).open();
            fail("a line that cannot be read");
        } catch (CorruptFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 4"));
        }

        File newer = scratch.newFolder("neuer");
        File newerLog = new File(newer, "section-writer/sections.jsonl");
        newerLog.getParentFile().mkdirs();
        Files.write(newerLog.toPath(), "{\"v\":2,\"art\":\"abschnitt\"}\n".getBytes(StandardCharsets.UTF_8));
        try {
            new WrittenSections(newer).open();
            fail("a newer version");
        } catch (WrittenSections.NewerVersionException expected) {
            assertTrue(expected.getMessage().contains("newer Writing4j"));
        }
        File pointed = scratch.newFolder("zeiger");
        File pointer = new File(pointed, "section-writer/current");
        pointer.getParentFile().mkdirs();
        Files.write(pointer.toPath(), "sections-2.jsonl\n".getBytes(StandardCharsets.UTF_8));
        try {
            new WrittenSections(pointed).open();
            fail("another generation");
        } catch (WrittenSections.NewerVersionException expected) {
            assertTrue(expected.getMessage().contains("sections-2.jsonl"));
        }
        WrittenSections ram = new WrittenSections();
        assertNull(ram.logFile());
        ram.record(sample("x", 3));
        assertEquals(1, ram.size());
    }

    // ------------------------------------------------------------------------------------- naming the sources

    /** The review of 22.11.2026: two sites of one name are two speakers and the prompt tells them apart. */
    @Test
    public void twoSitesOfOneNameKeepTheirHostsAndSpeakApart() {
        PassageLookup sites = PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("a1", "polizei", "https://polizei.freistaat.example/lage", P1, "BEHOERDE",
                        "DE"),
                new PassageLookup.Passage("a2", "umwelt", "https://umwelt.freistaat.example/laerm", P2, "BEHOERDE",
                        "DE"),
                new PassageLookup.Passage("a3", "post-1", "https://www.linkedin.com/posts/eins", P3, "", "DE"),
                new PassageLookup.Passage("a4", "post-2", "https://linkedin.com/posts/zwei", P5, "", "DE"),
                new PassageLookup.Passage("a5", "polizei-2", "https://www.polizei.freistaat.example/tempo", P4,
                        "BEHOERDE", "DE")));
        List<BookSection.Citation> cited = Arrays.asList(new BookSection.Citation(1, "a1", "polizei", P1),
                new BookSection.Citation(2, "a2", "umwelt", P2), new BookSection.Citation(3, "a3", "post-1", P3),
                new BookSection.Citation(4, "a4", "post-2", P5), new BookSection.Citation(5, "a5", "polizei-2", P4));
        assertEquals("one name, two hosts: two speakers; one host: one", new HashSet<Integer>(Arrays.asList(1, 5)),
                PlanSectionWriter.sameSource(sites, cited.get(0), cited));
        assertEquals("two posts on a site of many authors", Collections.singleton(3),
                PlanSectionWriter.sameSource(sites, cited.get(2), cited));
        assertEquals("", PlanSectionWriter.sharedHost(sites.find("a3")));
        assertEquals("polizei.freistaat.example", PlanSectionWriter.sharedHost(sites.find("a5")));
        assertEquals("", PlanSectionWriter.sharedHost(null));

        SectionPlan.Section both = section("abschnitt-f", "Freistaat", 200, Arrays.asList(ev("a1", "polizei", "q1"),
                ev("a2", "umwelt", "q2")), Collections.<SectionPlan.Evidence>emptyList(),
                Arrays.asList(q("q1", Q1, QuestionEvidence.LABEL_DIRECT), q("q2", Q2, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        PlanSectionWriter.Prepared prepared = PlanSectionWriter.prepare(both, "", sites, options());
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("polizei", "polizei.freistaat.example");
        names.put("umwelt", "umwelt.freistaat.example");
        assertEquals("one name of two sites: their hosts", names, prepared.assignment.getSourceNames());
        PlanSectionWriter.Entry police = prepared.entryOf("a1");
        assertEquals("Freistaat", police.name);
        assertEquals("polizei.freistaat.example", police.shown);
        assertEquals("the check knows every name the book may give it", Arrays.asList("polizei.freistaat.example",
                "freistaat", "Freistaat"), police.spoken());
        PlanSectionWriter.Prepared alone = PlanSectionWriter.prepare(section("abschnitt-g", "Polizei", 200,
                Arrays.asList(ev("a1", "polizei", "q1"), ev("a5", "polizei-2", "q2")),
                Collections.<SectionPlan.Evidence>emptyList(), Arrays.asList(q("q1", Q1,
                        QuestionEvidence.LABEL_DIRECT), q("q2", Q2, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList()),
                "", sites, options());
        names.clear();
        names.put("polizei", "Freistaat");
        names.put("polizei-2", "Freistaat");
        assertEquals("two pages of one site keep its name", names, alone.assignment.getSourceNames());
    }

    /**
     * The reviews of 22.11.2026: one organisation on its main site and a subdomain, or under two endings, keeps one
     * name and speaks as one; two subdomains of one domain speak apart.
     */
    @Test
    public void subdomainsOfOneOrganisationKeepItsName() {
        assertTrue(PlanSectionWriter.distinctSites("polizei.freistaat.example", "umwelt.freistaat.example"));
        assertFalse(PlanSectionWriter.distinctSites("verkehrsclub.example", "presse.verkehrsclub.example"));
        assertFalse(PlanSectionWriter.distinctSites("radhandel.example", "radhandel.eu.com"));
        assertFalse(PlanSectionWriter.distinctSites("rad.example", "rad.example"));
        assertTrue(PlanSectionWriter.sameSite("verkehrsclub.example", "presse.verkehrsclub.example"));
        assertTrue(PlanSectionWriter.sameSite("radhandel.example", "radhandel.eu.com"));
        assertFalse(PlanSectionWriter.sameSite("polizei.freistaat.example", "umwelt.freistaat.example"));
        assertFalse(PlanSectionWriter.sameSite("rad.example", "fuss.example"));
        assertFalse(PlanSectionWriter.sameSite("", "rad.example"));
        PassageLookup sites = PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("c1", "club", "https://www.verkehrsclub.example/tempo", P1, "VERBAND", "DE"),
                new PassageLookup.Passage("c2", "presse", "https://presse.verkehrsclub.example/laerm", P2, "VERBAND",
                        "DE"),
                new PassageLookup.Passage("c3", "handel-at", "https://radhandel.eu.com/akku", P3, "HERSTELLER_HANDEL",
                        "AT"),
                new PassageLookup.Passage("c4", "handel", "https://radhandel.example/akku", P4, "HERSTELLER_HANDEL",
                        "DE"),
                new PassageLookup.Passage("c5", "post", "https://de.linkedin.com/pulse/eins", P5, "", "DE")));
        List<BookSection.Citation> cited = Arrays.asList(new BookSection.Citation(1, "c1", "club", P1),
                new BookSection.Citation(2, "c2", "presse", P2), new BookSection.Citation(3, "c3", "handel-at", P3),
                new BookSection.Citation(4, "c4", "handel", P4), new BookSection.Citation(5, "c5", "post", P5));
        assertEquals(new HashSet<Integer>(Arrays.asList(1, 2)), PlanSectionWriter.sameSource(sites, cited.get(0),
                cited));
        assertEquals(new HashSet<Integer>(Arrays.asList(3, 4)), PlanSectionWriter.sameSource(sites, cited.get(2),
                cited));
        assertEquals("a subdomain of a site of many authors", "", PlanSectionWriter.sharedHost(sites.find("c5")));
        PlanSectionWriter.Prepared prepared = PlanSectionWriter.prepare(section("abschnitt-v", "Verkehrsclub", 200,
                Arrays.asList(ev("c1", "club", "q1"), ev("c2", "presse", "q2")),
                Collections.<SectionPlan.Evidence>emptyList(), Arrays.asList(q("q1", Q1,
                        QuestionEvidence.LABEL_DIRECT), q("q2", Q2, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList()), "", sites, options());
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("club", "Verkehrsclub");
        names.put("presse", "Verkehrsclub");
        assertEquals("no host as the speaker", names, prepared.assignment.getSourceNames());
    }

    /** A spelling counts only with a capital and as a whole word, not as part of an address. */
    @Test
    public void onlyACapitalisedWholeWordGivesTheSpelling() {
        assertEquals("Bahn", PlanSectionWriter.spelled("bahn", "bahn und bahn und bahn, sagt die Bahn"));
        assertEquals("Radwerk", PlanSectionWriter.spelled("radwerk",
                "RadWerk.example und RadWerk.example nennen es. Das sagt Radwerk."));
        assertEquals("never all in capitals", "Shimano", PlanSectionWriter.spelled("Shimano",
                "SHIMANO STEPS E8000. Shimano bietet den Antrieb an. SHIMANO"));
        assertEquals("BASt", PlanSectionWriter.spelled("BAST", "Die BASt zählt. Laut BASt sinkt die Zahl."));
        assertEquals("a short name with two capitals", "GdP", PlanSectionWriter.spelled("GDP",
                "Die GdP fordert mehr Kontrollen. Die GdP warnt."));
        assertEquals("a short name with one capital stays", "VDB", PlanSectionWriter.spelled("VDB",
                "Der Vdb meldet. Vdb und Vdb."));
        assertEquals("a name without a capital spelling stays", "radwerk", PlanSectionWriter.spelled("radwerk",
                "radwerk.example/kontakt"));
    }

    /** The review of 22.11.2026: the prompt writes a name as the source does ("Bahn"), the same for all its pages. */
    @Test
    public void theSourcesNameIsSpelledAsItsOwnTextWritesIt() {
        assertEquals("Bahn", PlanSectionWriter.spelled("BAHN", "Die Bahn baut neue Strecken. Bahnhöfe werden größer, "
                + "die Bahn wird pünktlicher, meldet BAHN aktuell auf bahn.example."));
        assertEquals("no such spelling in the text", "BAHN", PlanSectionWriter.spelled("BAHN",
                "Die Züge fahren pünktlich, die s-bahn auch."));
        assertEquals("a name of three letters only in capitals", "VDB", PlanSectionWriter.spelled("VDB",
                "Der Vdb meldet weniger Unfälle."));
        assertEquals("a host stays", "bahn.example", PlanSectionWriter.spelled("bahn.example", "Die Bahn fährt."));
        assertEquals("a spelling all in capitals is a logo's (the review of 22.11.2026)", "Rad24",
                PlanSectionWriter.spelled("Rad24", "Bei RAD24 gibt es Akkus; rad24 liefert schnell."));
        assertEquals("", PlanSectionWriter.spelled("", "Die Bahn fährt."));
        assertEquals("", PlanSectionWriter.spelled(null, "Die Bahn fährt."));
        assertEquals("Sturzwacht", PlanSectionWriter.spelled("Sturzwacht", null));

        PassageLookup rail = PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("b1", "bahn-1", "https://bahn.example/laerm", "Die Bahn senkt den Lärm.",
                        "BEHOERDE", "DE"),
                new PassageLookup.Passage("b2", "bahn-2", "https://www.bahn.example/tempo", "Auch die BAHN fährt in "
                        + "Wohngebieten langsamer, sagt die Bahn.", "BEHOERDE", "DE")));
        PlanSectionWriter.Prepared prepared = PlanSectionWriter.prepare(section("abschnitt-b", "Bahn", 200,
                Arrays.asList(ev("b1", "bahn-1", "q1"), ev("b2", "bahn-2", "q2")),
                Collections.<SectionPlan.Evidence>emptyList(), Arrays.asList(q("q1", Q1,
                        QuestionEvidence.LABEL_DIRECT), q("q2", Q2, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList()),
                "", rail, options());
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("bahn-1", "Bahn");
        names.put("bahn-2", "Bahn");
        assertEquals("one spelling over all passages of the name", names, prepared.assignment.getSourceNames());
        assertEquals("BAHN", prepared.entryOf("b1").name);
    }

    /**
     * The review of 22.11.2026: a sentence that names the source as its own text writes it ("GdP"), or as the prompt
     * gave it from another passage of that source, names its speaker and does not go back.
     */
    @Test
    public void aSentenceNamingTheSourceAsItsTextOrThePromptWritesItIsNoFact() throws Exception {
        PassageLookup union = PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("g1", "gdp-1", "https://www.gdp.example/presse", "Die GdP fordert mehr "
                        + "Blitzer an Schulen.", "VERBAND", "DE"),
                new PassageLookup.Passage("g2", "gdp-2", "https://www.gdp.example/kontrollen", "Mehr Kontrollen "
                        + "auf Autobahnen seien nötig.", "VERBAND", "DE")));
        SectionPlan.Section kontrolle = section("abschnitt-g", "Kontrolle", 60, Arrays.asList(ev("g1", "gdp-1",
                "q1"), ev("g2", "gdp-2", "q2")), Collections.<SectionPlan.Evidence>emptyList(), Arrays.asList(
                q("q1", Q1, QuestionEvidence.LABEL_DIRECT), q("q2", Q2, QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Kontrolle", "Die GdP fordert mehr Blitzer an Schulen [1]. Laut GdP sind mehr Kontrollen auf "
                + "Autobahnen nötig [2].");
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Die GdP fordert", new String[]{"GEDECKT", "Die GdP fordert mehr Blitzer an Schulen",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        said.put("Laut GdP", new String[]{"GEDECKT", "Mehr Kontrollen auf Autobahnen seien nötig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        Scripted writer = writer(drafts, new LinkedHashMap<String, String>());

        WrittenSection written = new PlanSectionWriter(writer, checker(said), union, new SentenceVerdicts(), null,
                options(), CLOCK, null).write(plan(kontrolle), kontrolle);

        assertEquals(written.getFailure(), WrittenSection.Status.OK, written.getStatus());
        assertTrue(written.getMarks().isEmpty());
        assertEquals("both sentences name the union as written", 2, written.count("zuschreibungImSatz"));
        assertEquals(0, written.count("zurueck"));
        assertTrue(writer.rewrites().isEmpty());
    }

    /** The review of 22.11.2026: the sentence check gets the source's names, so "Rad24" holds no number. */
    @Test
    public void theSentenceCheckGetsTheSourcesNames() throws Exception {
        String text = "Der Akku des Rads hält acht Stunden und lädt in drei Stunden.";
        SectionPlan.Section akku = section("abschnitt-r", "Akku", 60, Collections.singletonList(ev("r1", "rad", "q1")),
                Collections.<SectionPlan.Evidence>emptyList(), Collections.singletonList(q("q1", "Wie lange hält der "
                        + "Akku?", QuestionEvidence.LABEL_DIRECT)), Collections.<SectionPlan.Contradiction>emptyList());
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Akku", "Laut Rad24 hält der Akku acht Stunden [1].");
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Laut Rad24", says("GEDECKT", "Der Akku des Rads hält acht Stunden", "Die Passage sagt das."));

        SentenceVerdicts named = new SentenceVerdicts();
        new PlanSectionWriter(writer(drafts, new LinkedHashMap<String, String>()), checker(said),
                PassageLookup.of(Collections.singletonList(new PassageLookup.Passage("r1", "rad",
                        "https://www.rad24.example/akku", text, "HERSTELLER_HANDEL", "DE"))), named, null, options(),
                CLOCK, null).write(plan(akku), akku);
        assertEquals(1, named.size());
        assertEquals("the number is part of the source's name", Verdict.GEDECKT, named.all().get(0).getVerdict());

        SentenceVerdicts unnamed = new SentenceVerdicts();
        new PlanSectionWriter(writer(drafts, new LinkedHashMap<String, String>()), checker(said),
                PassageLookup.of(Collections.singletonList(new PassageLookup.Passage("r1", "rad", "", text,
                        "HERSTELLER_HANDEL", "DE"))), unnamed, null, options(), CLOCK, null).write(plan(akku), akku);
        assertEquals("a source without a site: 24 is a number of the sentence", Verdict.TEILWEISE,
                unnamed.all().get(0).getVerdict());
    }

    /** The review of 22.11.2026: a rewrite names the source as the prompt did, by its host where two share a name. */
    @Test
    public void aRewriteNamesTheSourceAsThePromptDid() throws Exception {
        PassageLookup shops = PassageLookup.of(Arrays.asList(
                new PassageLookup.Passage("n1", "nord", "https://nord.radhandel.example/rad", "Das Rad wiegt zwölf "
                        + "Kilo.", "HERSTELLER_HANDEL", "DE"),
                new PassageLookup.Passage("s1", "sued", "https://sued.radhandel.example/rad", "Der Akku hält acht "
                        + "Stunden.", "HERSTELLER_HANDEL", "DE")));
        SectionPlan.Section rad = section("abschnitt-r", "Rad", 60, Arrays.asList(ev("n1", "nord", "q1"),
                ev("s1", "sued", "q2")), Collections.<SectionPlan.Evidence>emptyList(), Arrays.asList(q("q1",
                "Wie schwer ist das Rad?", QuestionEvidence.LABEL_DIRECT), q("q2", "Wie lange hält der Akku?",
                QuestionEvidence.LABEL_DIRECT)), Collections.<SectionPlan.Contradiction>emptyList());
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Rad", "Das Rad wiegt zwölf Kilo [1].");
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Das Rad", null);
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Das Rad", new String[]{"GEDECKT", "Das Rad wiegt zwölf Kilo", "Die Passage sagt das.",
                "ALS_TATSACHE", null});
        Scripted writer = writer(drafts, rewrites);
        new PlanSectionWriter(writer, checker(said), shops, new SentenceVerdicts(), null, options(), CLOCK, null)
                .write(plan(rad), rad);
        List<ChatMessage> asked = writer.rewrites().get(0);
        String finding = asked.get(asked.size() - 1).getContent();
        assertTrue(finding, finding.contains("(nord.radhandel.example)"));
    }

    private static WrittenSection sample(String id, int words) {
        return new WrittenSection(id, "Titel " + id, "Kapitel", 100, WrittenSection.Status.OK, "", "Ein Satz [1].",
                words, Collections.singletonList(new WrittenSection.Citation(1, "p1", "stadt-a", P1)), null, null,
                null, null, "m", "h", 1, 1L, 1L);
    }

    private static List<String> passageIds(List<EvidencePassage> passages) {
        List<String> out = new ArrayList<String>();
        for (EvidencePassage passage : passages) {
            out.add(passage.getPassageId());
        }
        return out;
    }
}
