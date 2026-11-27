package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;
import com.aresstack.writing4j.cornerstone.TermCandidates;
import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.planwriter.PlanSectionWriter;
import com.aresstack.writing4j.planwriter.WrittenSection;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.Reader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 4 as a whole on the invented controlled basket with a scripted model and word vectors: passages outside the
 * fence by their source's profile are set aside before the cornerstones are found, questions come from the mission and
 * from the cornerstones, near duplicates are one question, and all are checked against mission and fence, questions outside are kept with their reason and not pursued, a
 * demanded question the basket does not answer is a gap for a further search, passages outside the fence are neither
 * evidence nor cited. No concept goes in.
 */
public class BookRunTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
    private static final List<String> OUTSIDE_PASSAGES = Arrays.asList("p03", "p08", "p10", "p13", "p14");

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    @Test
    public void fromTheBasketToTheBook() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        Script script = new Script();
        BookRun.Settings settings = settings(stock, 1);

        BookRun.Result result = new BookRun(script, script, script, new WordVectors(), settings, CLOCK, null)
                .run(stock.basket, stock);

        BookRun.Opening opening = result.getOpening();
        assertEquals(7, opening.getProfiles().size());
        assertEquals("the pool part of the mixed source and the two sources outside are set aside",
                Arrays.asList("p08", "p10", "p13"), new ArrayList<String>(opening.getSetAside().keySet()));
        assertEquals(11, opening.getPassages().size());
        assertEquals(Arrays.asList("quellensteckbriefe", "sieb", "fragen", "zusammenlegen", "fragenpruefung",
                "belegpruefung", "abschnittsplan", "schreiben"), names(result.getSteps()));
        assertEquals(7, result.getSteps().get(0).getCalls());
        assertTrue("the outline has chapters", opening.getGroups().size() > 0);
        assertEquals("one call for the mission, one for the outline of all cornerstones", 2,
                result.getSteps().get(2).getCalls());
        assertEquals("no model call for merging", 0, result.getSteps().get(3).getCalls());
        assertEquals("all questions in one bundle", 1, result.getSteps().get(4).getCalls());
        assertEquals(0, result.getModelSwitches());
        for (BookRun.Group group : opening.getGroups()) {
            assertFalse(group.getTitle(), Script.EVERYDAY.contains(group.getTitle()));
            assertFalse(group.getTitle(), group.getPassageIds().isEmpty());
            for (String id : group.getPassageIds()) {
                assertFalse("a passage set aside is no place of a cornerstone",
                        opening.getSetAside().containsKey(id));
            }
        }

        Set<String> found = new LinkedHashSet<String>();
        Set<String> demanded = new LinkedHashSet<String>();
        for (BookRun.Question question : opening.getQuestions()) {
            (question.getOrigin() == BookRun.Question.Origin.PFLICHT ? demanded : found).add(question.getText());
            assertEquals(question.getText(), question.getText().equals(Script.DRINKING), !question.isPursued());
        }
        assertTrue(demanded.toString(), demanded.containsAll(Script.ANSWERS.keySet()));
        assertTrue("a question both sides ask is one question", java.util.Collections.disjoint(found, demanded));
        assertTrue("the question outside is kept: " + found, found.contains(Script.DRINKING));
        assertFalse("the pool part set aside asks nothing", found.contains(Script.POOL));
        assertTrue(demanded.toString(), demanded.contains(Script.FUNDING));

        for (EvidenceVerdict verdict : result.getVerdicts()) {
            if (OUTSIDE_PASSAGES.contains(verdict.getPassageId())) {
                assertFalse(verdict.getPassageId(), verdict.getKind() == EvidenceVerdict.Kind.BELEGT);
            }
        }
        assertFalse(result.getSections().isEmpty());
        for (WrittenSection section : result.getSections()) {
            assertEquals(section.getFailure(), WrittenSection.Status.OK, section.getStatus());
            for (WrittenSection.Citation citation : section.getCitations().values()) {
                assertFalse(citation.getPassageId(), OUTSIDE_PASSAGES.contains(citation.getPassageId()));
            }
        }
        for (BookRun.QuestionLine line : result.getQuestionLines()) {
            if (!line.getQuestion().isPursued()) {
                assertEquals(BookRun.QuestionLine.State.AUSSERHALB, line.getState());
                assertTrue(line.getReason(), line.getReason().startsWith("außerhalb des Weidezauns: "));
            } else if (line.getQuestion().getText().equals(Script.FUNDING)) {
                assertEquals(BookRun.QuestionLine.State.NACHRECHERCHE, line.getState());
                assertTrue(line.getReason(), line.getReason().contains("Nachrecherche"));
            } else {
                assertEquals(line.getQuestion().getText() + ": " + line.getReason(),
                        BookRun.QuestionLine.State.BEANTWORTET, line.getState());
            }
        }
        assertEquals(0, result.getCitedOutsideFence());
        assertTrue(result.book(), result.book().startsWith("# "));

        File out = scratch.newFolder("out");
        JsonObject json = stock.write(result, out, new JsonObject());
        for (String name : new String[]{"fragen.json", "fragen.md", "ergebnis.json", "buch.md", "bericht.txt"}) {
            assertTrue(name, new File(out, name).isFile());
        }
        assertEquals(0, json.getAsJsonObject("klassen").getAsJsonObject("irrelevant").get("belegt").getAsInt());
        assertEquals(0, json.getAsJsonObject("klassen").getAsJsonObject("gemischt-aussen").get("zitiert").getAsInt());
        assertEquals(Script.ANSWERS.size(),
                json.getAsJsonObject("fragenAbdeckung").get("BEANTWORTET").getAsInt());
        assertEquals(1, json.getAsJsonObject("fragenAbdeckung").get("NACHRECHERCHE").getAsInt());
    }

    @Test
    public void theReadersQuestionsArePursuedAndAGapWithoutEvidenceAndEveryStepReportsEachPiece() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        Script script = new Script();
        String unanswered = "Welche Farbe hat der Gartenzaun des Nachbarn?";
        final List<String> reports = java.util.Collections.synchronizedList(new ArrayList<String>());
        BookRun run = new BookRun(script, script, script, new WordVectors(), settings(stock, 1), CLOCK, null);
        BookRun.Progress progress = (step, done, total) -> reports.add(step + " " + done + "/" + total);
        BookRun.Result result = run.write(run.open(run.prepare(stock.basket.withQuestions(
                Arrays.asList(Script.DRINKING, unanswered)), stock, progress), (java.util.Map<String, String>) null,
                progress), progress);

        BookRun.QuestionLine drinking = null;
        BookRun.QuestionLine gap = null;
        for (BookRun.QuestionLine line : result.getQuestionLines()) {
            if (line.getQuestion().getText().equals(Script.DRINKING)) {
                drinking = line;
            } else if (line.getQuestion().getText().equals(unanswered)) {
                gap = line;
            }
        }
        assertTrue("the reader's question outside the fence is still pursued", drinking.getQuestion().isPursued());
        assertEquals(BookRun.Question.Origin.LESER, drinking.getQuestion().getOrigin());
        assertEquals(BookRun.QuestionLine.State.NACHRECHERCHE, gap.getState());
        assertTrue(gap.getReason(), gap.getReason().startsWith("vom Leser gefragt"));
        assertTrue(result.report(ZoneOffset.UTC), result.report(ZoneOffset.UTC).contains(unanswered));
        for (int done = 1; done <= 7; done++) {
            assertTrue("each source as soon as it is described: " + reports,
                    reports.contains("quellensteckbriefe " + done + "/7"));
        }
        assertTrue(reports.toString(), reports.contains("belegpruefung 1/" + count(reports, "belegpruefung 0/")));
        assertTrue(reports.toString(), reports.contains("schreiben 1/" + count(reports, "schreiben 0/")));
    }

    @Test
    public void theHostRunsEachSourceProfileAndEachEvidenceCheckAsAPieceOfItsOwn() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        Script script = new Script();
        final List<String> stages = java.util.Collections.synchronizedList(new ArrayList<String>());
        final List<String> titles = java.util.Collections.synchronizedList(new ArrayList<String>());
        com.aresstack.knowledge4j.run.Pieces host = new com.aresstack.knowledge4j.run.Pieces() {
            @Override
            public <T, R> List<R> run(String stage, List<T> items, java.util.function.Function<T, String> title,
                                      java.util.function.Function<T, R> work) {
                stages.add(stage);
                List<R> out = new ArrayList<R>();
                for (T item : items) {
                    titles.add(title.apply(item));
                    out.add(work.apply(item));
                }
                return out;
            }
        };
        BookRun run = new BookRun(script, script, script, new WordVectors(), settings(stock, 2).withPieces(host),
                CLOCK, null);
        BookRun.Preparation prepared = run.prepare(stock.basket, stock, null);

        assertEquals(Arrays.asList("quellensteckbriefe"), stages);
        assertEquals("one piece per source, named for the job list", 7, titles.size());
        assertEquals(7, prepared.getProfiles().size());

        run.write(run.open(prepared, (java.util.Map<String, String>) null, null), null);
        assertEquals("the evidence check runs its questions as pieces too",
                Arrays.asList("quellensteckbriefe", "belegpruefung"), stages);
    }

    @Test
    public void anInterruptedRunTakesTheProfilesItAlreadyMadeAndAsksOnlyForTheRest() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        final java.util.Map<String, String> kept = new java.util.concurrent.ConcurrentHashMap<String, String>();
        com.aresstack.writing4j.sourcerating.ProfileMemory memory =
                new com.aresstack.writing4j.sourcerating.ProfileMemory() {
                    @Override
                    public String recall(String key) {
                        return kept.get(key);
                    }

                    @Override
                    public void remember(String key, String profile) {
                        kept.put(key, profile);
                    }
                };
        Script script = new Script();
        BookRun.Preparation first = new BookRun(script, script, script, new WordVectors(),
                settings(stock, 2).withProfileMemory(memory), CLOCK, null).prepare(stock.basket, stock, null);
        assertEquals("every profile is kept as soon as it is made", 7, kept.size());

        String forgotten = kept.keySet().iterator().next();
        kept.remove(forgotten);
        final List<Integer> asked = java.util.Collections.synchronizedList(new ArrayList<Integer>());
        com.aresstack.knowledge4j.run.Pieces counting = new com.aresstack.knowledge4j.run.Pieces() {
            @Override
            public <T, R> List<R> run(String stage, List<T> items, java.util.function.Function<T, String> title,
                                      java.util.function.Function<T, R> work) {
                asked.add(items.size());
                return com.aresstack.knowledge4j.run.Pieces.parallel(1).run(stage, items, title, work);
            }
        };
        BookRun.Preparation again = new BookRun(script, script, script, new WordVectors(),
                settings(stock, 2).withProfileMemory(memory).withPieces(counting), CLOCK, null)
                .prepare(stock.basket, stock, null);
        assertEquals("only the forgotten source is asked again", Arrays.asList(1), asked);
        assertEquals(first.getProfiles().keySet(), again.getProfiles().keySet());
        for (String id : first.getProfiles().keySet()) {
            assertEquals(first.getProfiles().get(id).getKind(), again.getProfiles().get(id).getKind());
            assertEquals(first.getProfiles().get(id).getFit().getRelation(),
                    again.getProfiles().get(id).getFit().getRelation());
        }
        assertEquals(7, kept.size());
    }

    private static String count(List<String> reports, String start) {
        for (String report : reports) {
            if (report.startsWith(start)) {
                return report.substring(start.length());
            }
        }
        return "?";
    }

    @Test
    public void theCapacityOfTheModelChangesHowManyCallsRunAtOnceNotTheBook() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        String[] books = new String[2];
        int[] capacities = {1, 6};
        for (int i = 0; i < 2; i++) {
            Script script = new Script();
            BookRun.Settings settings = settings(stock, capacities[i]);
            books[i] = new BookRun(script, script, script, new WordVectors(), settings, CLOCK, null)
                    .run(stock.basket, stock).book();
        }
        assertEquals("one GPU call after call or a cloud service in parallel: the same book", books[0], books[1]);
    }

    @Test
    public void theOpeningAloneNeedsNoWriter() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        Script script = new Script();
        BookRun.Opening opening = new BookRun(script, script, null, new WordVectors(),
                BookRun.Settings.of("de", 0).withCandidates(SMALL_BASKET), CLOCK, null).open(stock.basket, stock);

        assertEquals("profiles, sieve, questions, merging, question check", 5, opening.getSteps().size());
        assertFalse(opening.getQuestions().isEmpty());
        JsonObject json = stock.writeOpening(opening, scratch.newFolder("fragen"), new JsonObject());
        assertTrue(json.getAsJsonObject("fragenNachUrteil").get("AUSSEN").getAsInt() > 0);
    }

    @Test
    public void withoutAnEmbeddingModelSharedWordsChooseThePassages() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        Script script = new Script();
        BookRun.Result result = new BookRun(script, script, script, null, settings(stock, 1), CLOCK, null)
                .run(stock.basket, stock);
        int answered = 0;
        for (BookRun.QuestionLine line : result.getQuestionLines()) {
            answered += line.getState() == BookRun.QuestionLine.State.BEANTWORTET ? 1 : 0;
        }
        assertTrue("questions are answered without vectors: " + answered, answered >= Script.ANSWERS.size() - 1);
        assertFalse(result.getSections().isEmpty());
    }

    /** The invented basket is small: a term needs two passages from two sources. */
    static final TermCandidates.Options SMALL_BASKET = new TermCandidates.Options(2, 2,
            TermCandidates.Options.DEFAULT_DEPTH, TermCandidates.Options.DEFAULT_UNKNOWN_FLOOR);

    static BookRun.Settings settings(BasketStock stock, int capacity) {
        return new BookRun.Settings("de", stock.targetWords, capacity, null, null, null,
                PlanSectionWriter.Options.defaults().withTopUp(false)).withCandidates(SMALL_BASKET);
    }

    private static List<String> names(List<BookRun.Step> steps) {
        List<String> out = new ArrayList<String>();
        for (BookRun.Step step : steps) {
            out.add(step.getName());
        }
        return out;
    }

    /**
     * Word vectors: one dimension per word of six letters or more (hashed), so passages with the same words lie close.
     * Deterministic and enough to group the invented basket.
     */
    static final class WordVectors implements EmbeddingPort {
        private static final int DIMENSIONS = 256;

        @Override
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (String text : texts) {
                float[] values = new float[DIMENSIONS];
                values[0] = 0.01f;
                for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
                    if (word.length() >= 6) {
                        values[Math.floorMod(word.hashCode(), DIMENSIONS - 1) + 1] += 1f;
                    }
                }
                out.add(new EmbeddingVector("words", "words-" + role, values));
            }
            return out;
        }
    }

    /** The scripted model: profiles, questions, question check, evidence, writer and sentence check by role. */
    static final class Script implements MainModelChat {
        private static final Pattern BLOCK = Pattern.compile("\\[(P\\d+)][^\\n]*\\n(?:[^\\n]*\\n)*?\"\"\"\\n(.*?)( …)?"
                + "\\n\"\"\"", Pattern.DOTALL);
        private static final Pattern PLACE = Pattern.compile("(?m)^(\\d+)\\. [^:\\n]*: (.*)$");
        private static final Pattern QUESTION = Pattern.compile("\\[(F\\d+)] ([^\\n]*)");
        private static final Pattern WRITER_PASSAGE = Pattern.compile("\\[(\\d+)] \"\"\"(.*?)\"\"\"", Pattern.DOTALL);
        private static final Pattern SENTENCE_LABEL = Pattern.compile("\\[(S\\d+)] „");
        static final String POOL = "Wie reinigt man einen Pool vor der Saison?";
        static final String DRINKING = "Wie bereitet man Regenwasser zu Trinkwasser auf?";
        /** Demanded by the mission, answered by no passage of the basket. */
        static final String FUNDING = "Welche Förderprogramme gibt es für Regenwasseranlagen?";
        /** The questions the invented passages answer, each with words of the passages that answer it. */
        static final Map<String, List<String>> ANSWERS = new LinkedHashMap<String, List<String>>();
        /** Questions outside the fence a careless draft would ask, with the words that tempt it. */
        static final Map<String, List<String>> TEMPTING = new LinkedHashMap<String, List<String>>();

        static {
            ANSWERS.put("Aus welchen Bauteilen besteht eine Regenwassernutzungsanlage?",
                    Arrays.asList("besteht aus einem Filter"));
            ANSWERS.put("Wofür eignet sich Regenwasser im Haushalt?",
                    Arrays.asList("eignet sich für die Toilettenspülung", "Regentonne"));
            ANSWERS.put("Wem muss man die Anlage anzeigen, bevor sie in Betrieb geht?",
                    Arrays.asList("Gesundheitsamt"));
            ANSWERS.put("Wie werden Trinkwasser- und Regenwasserleitungen getrennt?",
                    Arrays.asList("nie mit dem Trinkwassernetz"));
            ANSWERS.put("Was kostet eine Anlage und wann rechnet sie sich?",
                    Arrays.asList("3.000 und 6.000", "15 bis 20 Jahren", "fünf Jahren"));
            ANSWERS.put("Wie viel Trinkwasser lässt sich einsparen?", Arrays.asList("40 Prozent", "Hälfte"));
            TEMPTING.put(POOL, Arrays.asList("Pool"));
            TEMPTING.put(DRINKING, Arrays.asList("Trinkwasser aufbereiten"));
        }

        final AtomicInteger calls = new AtomicInteger();

        /** Terms the scripted sieve calls everyday words; every other term is a cornerstone. */
        static final Set<String> EVERYDAY = new LinkedHashSet<String>(Arrays.asList("Anlage", "Haus", "Wasser"));
        private static final Pattern TERM = Pattern.compile("\nBegriff: ([^\n]*)\n");
        private static final Pattern NUMBERED = Pattern.compile("(?m)^(\\d+)\\. ");

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            calls.incrementAndGet();
            if (messages.get(0).getContent().contains("Aufgabe: Bilde daraus die Gliederung des Buches.")) {
                return MainModelChatResult.ok(outline(messages.get(0).getContent()));
            }
            if (messages.get(0).getContent().startsWith("Du hilfst bei der Vorbereitung eines Sachbuchs")) {
                Matcher term = TERM.matcher(messages.get(0).getContent());
                return MainModelChatResult.ok(term.find() && EVERYDAY.contains(term.group(1).trim())
                        ? "allgemeinwort" : "Rolle: eckpfeiler");
            }
            if (messages.get(0).getContent().startsWith("You write one sentence of a non-fiction book again")) {
                return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE, "no rewrite");
            }
            Matcher passage = WRITER_PASSAGE.matcher(messages.get(messages.size() - 1).getContent());
            StringBuilder text = new StringBuilder();
            while (passage.find()) {
                text.append(firstSentence(passage.group(2))).append(" [").append(passage.group(1)).append("]. ");
            }
            return text.length() == 0 ? MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "no passage") : MainModelChatResult.ok(text.toString().trim());
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            String system = messages.get(0).getContent();
            String prompt = messages.get(messages.size() - 1).getContent();
            if (system.startsWith("Du beschreibst Quellen")) {
                return MainModelChatResult.ok(profile(prompt));
            }
            if (system.startsWith("Du planst ein Sachbuch")) {
                JsonArray questions = new JsonArray();
                for (String question : ANSWERS.keySet()) {
                    questions.add(question);
                }
                questions.add(FUNDING);
                JsonObject o = new JsonObject();
                o.addProperty("titel", "Regenwasser im Haus");
                o.add("fragen", questions);
                return MainModelChatResult.ok(o.toString());
            }
            if (system.startsWith("Du ordnest die Begriffe")) {
                JsonArray numbers = new JsonArray();
                Matcher numbered = NUMBERED.matcher(prompt);
                while (numbered.find()) {
                    numbers.add(Integer.parseInt(numbered.group(1)));
                }
                JsonObject field = new JsonObject();
                field.addProperty("name", "Regenwasser im Haus");
                field.add("begriffe", numbers);
                JsonArray fields = new JsonArray();
                fields.add(field);
                JsonObject o = new JsonObject();
                o.add("felder", fields);
                return MainModelChatResult.ok(o.toString());
            }
            if (system.startsWith("Du prüfst Fragen eines Sachbuchs")) {
                return MainModelChatResult.ok(check(prompt));
            }
            if (system.startsWith("Du prüfst für ein Sachbuch, welche Passagen")) {
                return MainModelChatResult.ok(evidence(prompt));
            }
            if (system.startsWith("Du prüfst Sätze")) {
                return MainModelChatResult.ok(sentences(prompt, schemaJson != null && schemaJson.contains("urteile")));
            }
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE, "unknown role");
        }

        @Override
        public String modelName() {
            return "script";
        }

        private static String profile(String prompt) {
            JsonObject o = new JsonObject();
            o.addProperty("anbieter", "Herausgeber");
            o.addProperty("grund", "Erfundene Quelle des Tests.");
            o.addProperty("quellenart", "SONSTIGE");
            o.addProperty("rechtsraum", prompt.contains("alpenland") ? "AT" : "DE");
            o.addProperty("eigeninteresse", "NEIN");
            o.addProperty("datum", "");
            o.addProperty("gegenstand", "Wasser");
            JsonArray outside = new JsonArray();
            if (prompt.contains("haus-und-garten")) {
                o.addProperty("zaun", "GEMISCHT");
                outside.add("Wasser rund ums Haus > Den Pool richtig reinigen");
            } else {
                o.addProperty("zaun", prompt.contains("industrie-wasser.example") || prompt.contains("alpenland")
                        ? "AUSSEN" : "INNEN");
            }
            o.add("aussenTeile", outside);
            return o.toString();
        }

        /**
         * One chapter: every question whose words stand at a cornerstone's place, with its numbers; a careless outline
         * also asks the drinking water question; the pool question only if a place shows the pool.
         */
        private static String outline(String prompt) {
            StringBuilder out = new StringBuilder("Kapitel 1: Regenwasser im Haus\n");
            Map<String, List<String>> asked = new LinkedHashMap<String, List<String>>(ANSWERS);
            asked.put(POOL, TEMPTING.get(POOL));
            for (Map.Entry<String, List<String>> entry : asked.entrySet()) {
                List<String> numbers = new ArrayList<String>();
                Matcher place = PLACE.matcher(prompt);
                while (place.find()) {
                    for (String mark : entry.getValue()) {
                        if (place.group(2).contains(mark) && !numbers.contains(place.group(1))) {
                            numbers.add(place.group(1));
                        }
                    }
                }
                if (entry.getKey().equals(POOL) && numbers.isEmpty()) {
                    continue;
                }
                out.append("- ").append(entry.getKey()).append(" ").append(numbers).append("\n");
            }
            out.append("- ").append(DRINKING).append(" [1]\n");
            return out.toString();
        }

        private static String check(String prompt) {
            JsonArray list = new JsonArray();
            Matcher question = QUESTION.matcher(prompt);
            while (question.find()) {
                boolean outside = TEMPTING.containsKey(question.group(2).trim());
                JsonObject o = new JsonObject();
                o.addProperty("nr", question.group(1));
                o.addProperty("grund", outside ? "Im Weidezaun ausgeschlossen." : "Gehört zum Auftrag.");
                o.addProperty("urteil", outside ? "AUSSEN" : "INNEN");
                list.add(o);
            }
            JsonObject top = new JsonObject();
            top.add("urteile", list);
            return top.toString();
        }

        private static String evidence(String prompt) {
            String question = between(prompt, "Frage, die das Buch beantworten muss:\n", "\n");
            List<String> answersTo = ANSWERS.containsKey(question) ? ANSWERS.get(question)
                    : new ArrayList<String>();
            JsonArray list = new JsonArray();
            Matcher block = BLOCK.matcher(prompt);
            while (block.find()) {
                String text = block.group(2);
                boolean outside = text.contains("Trinkwasser aufbereiten");
                for (String word : Arrays.asList("Pool", "Brauereien", "Österreich", "Rasenmäher")) {
                    outside |= text.contains(word);
                }
                boolean answers = false;
                for (String mark : answersTo) {
                    answers |= text.contains(mark);
                }
                JsonObject o = new JsonObject();
                o.addProperty("nr", block.group(1));
                o.addProperty("zitat", answers && !outside ? firstSentence(text) : "");
                o.addProperty("aussage", answers && !outside ? firstSentence(text) + "." : "nichts");
                o.addProperty("anwendbar", outside ? "NEIN" : "JA");
                o.addProperty("urteil", answers && !outside ? "BELEGT" : "KEIN");
                list.add(o);
            }
            JsonObject top = new JsonObject();
            top.add("urteile", list);
            return top.toString();
        }

        private static String sentences(String prompt, boolean bundled) {
            String passage = between(prompt, "Zitierte Passage:\n„", "“\n");
            if (!bundled) {
                return entry(passage, null).toString();
            }
            JsonArray list = new JsonArray();
            Matcher label = SENTENCE_LABEL.matcher(prompt);
            while (label.find()) {
                list.add(entry(passage, label.group(1)));
            }
            JsonObject top = new JsonObject();
            top.add("urteile", list);
            return top.toString();
        }

        private static JsonObject entry(String passage, String label) {
            JsonObject o = new JsonObject();
            if (label != null) {
                o.addProperty("nr", label);
            }
            o.addProperty("belegart", "TEXT");
            o.addProperty("zitat", passage);
            o.addProperty("grund", "Die Passage sagt das.");
            o.addProperty("urteil", "GEDECKT");
            return o;
        }

        private static String firstSentence(String text) {
            String clean = text.replaceAll("\\s+", " ").trim();
            int end = clean.indexOf(". ");
            String first = end < 0 ? clean : clean.substring(0, end);
            return first.endsWith(".") ? first.substring(0, first.length() - 1) : first;
        }

        private static String between(String text, String start, String end) {
            int from = text.indexOf(start);
            if (from < 0) {
                return "";
            }
            from += start.length();
            int to = text.indexOf(end, from);
            return to < 0 ? text.substring(from) : text.substring(from, to);
        }
    }
}
