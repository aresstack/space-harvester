package com.aresstack.writing4j.evidence;

import com.aresstack.knowledge4j.model.ThinkingMode;
import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.memory.PassageNoise;
import com.aresstack.writing4j.memory.PassageText;
import com.aresstack.writing4j.engine.WritingSlots;
import com.aresstack.knowledge4j.model.role.ModelRole;
import com.aresstack.knowledge4j.model.role.RoleOutput;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Component 6 of the build plan (rev 13, 21.11.2026): the evidence judge. One call judges one question against one
 * passage: a verbatim quote, one sentence on what the passage says to the question, and the verdict BELEGT (a concrete
 * statement that answers part of the question), HINWEIS (touches the question but stays general or on a side aspect)
 * or KEIN. The program then checks the answer ({@link EvidenceVerdict#judged}): a BELEGT whose quote does not stand
 * verbatim in the passage, or whose sentence names a number the quote does not hold, holds as HINWEIS; both verdicts
 * are kept. The model judges, the check only lowers.
 *
 * <p>The prompt is the per-passage step of the planning comparison of 21.11. (arm g12-z: each passage judged alone,
 * the form whose "ja" carried in 35 of 41 cases), with the classes named as in the data model and the number rule of
 * the second review added. Rules of the build plan: a slot of its own
 * ({@link WritingSlots#PHASE_RESEARCH_EVIDENCE}; gemma4:12b is the reference, gemma4:e2b only when it does no
 * worse in the stock test), a fixed answer schema, thinking off, temperature 0. A failed call or an unreadable
 * answer gets one repair attempt; then the pair is FEHLER with its reason, never KEIN, and is offered again later.
 * After {@link Options#STOP_AFTER_FAILURES} failed pairs in a row the rest of a batch is not asked (FEHLER too, with
 * the last cause, so that a model that cannot be loaded or reached shows as such). A
 * verdict for the same question and the same passage text (the memory's text key) is reused, not asked again. A
 * passage the page-noise check marked as a page leftover ({@link #useNoise}) is not asked in a batch. Apart from
 * those marks the judge keeps no state between calls; it is as thread-safe as the injected chat.</p>
 */
public final class EvidenceJudge {

    /** One question of the research memory; immutable. */
    public static final class Question {
        private final String id;
        private final String text;

        public Question(String id, String text) {
            this.id = id == null ? "" : id.trim();
            this.text = text == null ? "" : text.trim();
            if (this.id.isEmpty()) {
                throw new IllegalArgumentException("a question needs an id");
            }
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }
    }

    /** One passage as the judge reads it; immutable. */
    public static final class Passage {
        private final String passageId;
        private final String sourceId;
        private final String sourceUrl;
        private final String domain;
        private final String text;

        /**
         * @param domain the source's host as shown to the model; empty to take it from the address
         */
        public Passage(String passageId, String sourceId, String sourceUrl, String domain, String text) {
            this.passageId = passageId == null ? "" : passageId.trim();
            this.sourceId = sourceId == null ? "" : sourceId.trim();
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
            String given = domain == null ? "" : domain.trim();
            this.domain = given.isEmpty() ? hostOf(this.sourceUrl) : given;
            this.text = text == null ? "" : text;
            if (this.passageId.isEmpty()) {
                throw new IllegalArgumentException("a passage needs an id");
            }
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public String getDomain() {
            return domain;
        }

        public String getText() {
            return text;
        }
    }

    /** A question and a passage to judge against it; immutable. */
    public static final class Pair {
        private final Question question;
        private final Passage passage;

        public Pair(Question question, Passage passage) {
            if (question == null || passage == null) {
                throw new IllegalArgumentException("a pair needs a question and a passage");
            }
            this.question = question;
            this.passage = passage;
        }

        public Question getQuestion() {
            return question;
        }

        public Passage getPassage() {
            return passage;
        }
    }

    /** What a pair got: a verdict of its own or a reused one, the raw answers and the time of its calls. */
    public static final class Outcome {
        private final Pair pair;
        private final EvidenceVerdict verdict;
        private final boolean reused;
        private final List<String> answers;
        private final long millis;

        Outcome(Pair pair, EvidenceVerdict verdict, boolean reused, List<String> answers, long millis) {
            this.pair = pair;
            this.verdict = verdict;
            this.reused = reused;
            this.answers = Collections.unmodifiableList(new ArrayList<String>(answers));
            this.millis = millis;
        }

        public Pair getPair() {
            return pair;
        }

        /** The verdict; for a reused one, the verdict of the passage with the same text (its passage id differs). */
        public EvidenceVerdict getVerdict() {
            return verdict;
        }

        /** Whether no call was made because the same question had a verdict on the same text. */
        public boolean isReused() {
            return reused;
        }

        /** The raw answer of every call for this pair, in order (a failed call adds ""); empty when reused. */
        public List<String> getAnswers() {
            return answers;
        }

        /** The time of this pair's calls; 0 when reused. */
        public long getMillis() {
            return millis;
        }
    }

    /** How the judge works; immutable. */
    public static final class Options {
        /** Default calls at the same time (to be measured per role; build plan, models and graphics card). */
        public static final int DEFAULT_PARALLEL = 4;
        /** Default characters of a passage in the prompt; longer passages are cut. */
        public static final int DEFAULT_PASSAGE_CHARS = 4000;
        /** Fewer characters would change the text key ({@link PassageText#KEY_CHARS}) of a cut passage. */
        public static final int MIN_PASSAGE_CHARS = 1000;
        /** After this many failed pairs in a row the rest of a batch is not asked. */
        public static final int STOP_AFTER_FAILURES = 5;

        private final int parallel;
        private final int passageChars;

        public Options(int parallel, int passageChars) {
            if (parallel < 1 || passageChars < MIN_PASSAGE_CHARS) {
                throw new IllegalArgumentException("at least one call at a time and " + MIN_PASSAGE_CHARS
                        + " characters of passage");
            }
            this.parallel = parallel;
            this.passageChars = passageChars;
        }

        public static Options defaults() {
            return new Options(DEFAULT_PARALLEL, DEFAULT_PASSAGE_CHARS);
        }

        public int getParallel() {
            return parallel;
        }

        public int getPassageChars() {
            return passageChars;
        }
    }

    /** The role: its own slot under Sources, a fixed schema, thinking off. */
    public static final ModelRole ROLE = ModelRole.builder("evidence-judge",
                    WritingSlots.PHASE_RESEARCH_EVIDENCE)
            .purpose("component 6: judges one question against one passage (BELEGT, HINWEIS, KEIN) with a verbatim "
                    + "quote; the program checks quote and numbers and only lowers")
            .promptFromCaller()
            .output(RoleOutput.schema(schema(), "zitat, aussage, urteil"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final double TEMPERATURE = 0.0;
    /** Answer budget: a quote of at most 25 words, one sentence, one word. */
    static final int ANSWER_TOKENS = 400;
    /** Calls per pair: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    private static final int MISSION_CHARS = 2000;
    private static final int QUOTE_CHARS = 600;
    private static final int STATEMENT_CHARS = 400;

    private final MainModelChat chat;
    private final String mission;
    private final boolean german;
    private final Options options;
    private final Clock clock;
    private final Consumer<String> log;
    private PassageNoise noise = PassageNoise.NONE;

    /**
     * @param chat     the transport of the slot ({@link #ROLE})
     * @param mission  the book's mission as the user wrote it
     * @param language the working language ({@code "en"} for English prompts; anything else reads German)
     * @param options  the defaults when {@code null}
     * @param log      progress and errors; may be {@code null}
     */
    public EvidenceJudge(MainModelChat chat, String mission, String language, Options options,
                         Consumer<String> log) {
        this(chat, mission, language, options, log, Clock.systemUTC());
    }

    EvidenceJudge(MainModelChat chat, String mission, String language, Options options, Consumer<String> log,
                  Clock clock) {
        if (chat == null) {
            throw new IllegalArgumentException("the evidence judge needs a chat");
        }
        this.chat = chat;
        this.mission = mission == null ? "" : mission.trim();
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.log = log;
        this.clock = clock;
    }

    /** One pair: at most {@link #ATTEMPTS} calls. */
    public Outcome judge(Pair pair, int round) {
        Passage passage = pair.getPassage();
        String read = read(passage.getText(), options.getPassageChars());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(mission, pair.getQuestion().getText(), passage.getDomain(), read,
                read.length() < passage.getText().length(), german)));
        List<String> answers = new ArrayList<String>();
        String problem = "";
        long start = clock.millis();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, ANSWER_TOKENS, schema());
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (result == null || !result.isOk()) {
                answers.add("");
                problem = result == null ? "model call returned nothing"
                        : "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue; // the same request once more
            }
            String text = result.getText() == null ? "" : result.getText();
            answers.add(text);
            Parsed parsed = parse(text);
            if (parsed.problem.isEmpty()) {
                EvidenceVerdict verdict = EvidenceVerdict.judged(pair.getQuestion().getId(), passage.getPassageId(),
                        passage.getSourceId(), passage.getSourceUrl(), read, parsed.kind, parsed.quote,
                        parsed.statement, chat.modelName(), round, clock.millis());
                return new Outcome(pair, verdict, false, answers, clock.millis() - start);
            }
            problem = "invalid answer: " + parsed.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + parsed.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("evidence judge failed for " + pair.getQuestion().getId() + " / " + passage.getPassageId() + ": "
                + problem);
        return new Outcome(pair, failed(pair, read, problem, round), false, answers, clock.millis() - start);
    }

    /**
     * Every pair, {@link Options#getParallel()} calls at a time. A pair whose question already has a verdict other
     * than FEHLER on the same passage text, in {@code known} or earlier in this batch, gets that verdict without a
     * call. After {@link Options#STOP_AFTER_FAILURES} failed pairs in a row the remaining pairs are not asked. A pair
     * whose passage is a page leftover ({@link #useNoise}) is left out: it is not asked and gets no outcome.
     *
     * @param known earlier verdicts (FEHLER ones are asked again); may be empty
     * @return one outcome per pair that is not a page leftover, in the given order
     */
    public List<Outcome> judgeAll(List<Pair> all, Collection<EvidenceVerdict> known, final int round) {
        PassageNoise leftover = currentNoise();
        List<Pair> pairs = new ArrayList<Pair>(all.size());
        for (Pair pair : all) {
            if (!leftover.isNoise(PassageText.key(pair.getPassage().getText()))) {
                pairs.add(pair);
            }
        }
        Map<String, EvidenceVerdict> earlier = new HashMap<String, EvidenceVerdict>();
        if (known != null) {
            for (EvidenceVerdict verdict : known) {
                if (verdict.getKind() == EvidenceVerdict.Kind.FEHLER) {
                    continue;
                }
                String key = verdict.getQuestionId() + "\n" + verdict.getTextKey();
                EvidenceVerdict before = earlier.get(key);
                if (before == null || before.getTimeMillis() <= verdict.getTimeMillis()) {
                    earlier.put(key, verdict);
                }
            }
        }
        final Map<String, Integer> firstOfKey = new LinkedHashMap<String, Integer>();
        List<Integer> ask = new ArrayList<Integer>();
        String[] keys = new String[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            keys[i] = keyOf(pairs.get(i), options.getPassageChars());
            if (!earlier.containsKey(keys[i]) && !firstOfKey.containsKey(keys[i])) {
                firstOfKey.put(keys[i], i);
                ask.add(i);
            }
        }
        Outcome[] judged = new Outcome[pairs.size()];
        if (!ask.isEmpty()) {
            final AtomicInteger failuresInRow = new AtomicInteger();
            final AtomicReference<String> lastFailure = new AtomicReference<String>("");
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.getParallel(), ask.size()));
            Map<Integer, Future<Outcome>> running = new LinkedHashMap<Integer, Future<Outcome>>();
            try {
                for (final int index : ask) {
                    final Pair pair = pairs.get(index);
                    running.put(index, pool.submit(() -> {
                        if (failuresInRow.get() >= Options.STOP_AFTER_FAILURES) {
                            String read = read(pair.getPassage().getText(), options.getPassageChars());
                            return new Outcome(pair, failed(pair, read, "not asked: the model failed "
                                    + Options.STOP_AFTER_FAILURES + " pairs in a row; last: " + lastFailure.get(),
                                    round), false, Collections.<String>emptyList(), 0L);
                        }
                        Outcome outcome = judge(pair, round);
                        if (outcome.getVerdict().getKind() == EvidenceVerdict.Kind.FEHLER) {
                            lastFailure.set(outcome.getVerdict().getReason());
                            failuresInRow.incrementAndGet();
                        } else {
                            failuresInRow.set(0);
                        }
                        return outcome;
                    }));
                }
                for (Map.Entry<Integer, Future<Outcome>> entry : running.entrySet()) {
                    judged[entry.getKey()] = entry.getValue().get();
                }
            } catch (ExecutionException crashed) {
                throw new IllegalStateException("evidence judge crashed", crashed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("evidence judge interrupted", interrupted);
            } finally {
                pool.shutdownNow();
            }
        }
        List<Outcome> out = new ArrayList<Outcome>(pairs.size());
        int calls = 0;
        int failed = 0;
        for (int i = 0; i < pairs.size(); i++) {
            Outcome outcome = judged[i];
            if (outcome == null) {
                EvidenceVerdict earlierVerdict = earlier.get(keys[i]);
                Outcome first = earlierVerdict == null ? judged[firstOfKey.get(keys[i])] : null;
                EvidenceVerdict shared = earlierVerdict != null ? earlierVerdict : first.getVerdict();
                if (shared.getKind() == EvidenceVerdict.Kind.FEHLER) {
                    String read = read(pairs.get(i).getPassage().getText(), options.getPassageChars());
                    outcome = new Outcome(pairs.get(i), failed(pairs.get(i), read, "same text as "
                            + shared.getPassageId() + ", which failed: " + shared.getReason(), round), false,
                            Collections.<String>emptyList(), 0L);
                } else {
                    outcome = new Outcome(pairs.get(i), shared, true, Collections.<String>emptyList(), 0L);
                }
            } else {
                calls += outcome.getAnswers().size();
            }
            failed += outcome.getVerdict().getKind() == EvidenceVerdict.Kind.FEHLER ? 1 : 0;
            out.add(outcome);
        }
        log("evidence judge: " + pairs.size() + " pairs, " + ask.size() + " asked, " + calls + " calls, " + failed
                + " without a verdict" + (all.size() > pairs.size() ? ", " + (all.size() - pairs.size())
                + " left out as page leftovers" : ""));
        return out;
    }

    /**
     * Which passages are page leftovers (the marks of the page-noise check); {@code null} is {@link PassageNoise#NONE}.
     * A pair whose passage is one is left out of {@link #judgeAll} from now on.
     */
    public synchronized EvidenceJudge useNoise(PassageNoise noise) {
        this.noise = noise == null ? PassageNoise.NONE : noise;
        return this;
    }

    private synchronized PassageNoise currentNoise() {
        return noise;
    }

    private EvidenceVerdict failed(Pair pair, String read, String problem, int round) {
        Passage passage = pair.getPassage();
        return EvidenceVerdict.failed(pair.getQuestion().getId(), passage.getPassageId(), passage.getSourceId(),
                passage.getSourceUrl(), read, problem, chat.modelName(), round, clock.millis());
    }

    /**
     * The question and the text the judge reads count (the key the verdict stores); an empty text never shares, each
     * such passage stands alone.
     */
    static String keyOf(Pair pair, int passageChars) {
        String text = PassageText.key(read(pair.getPassage().getText(), passageChars));
        return pair.getQuestion().getId() + "\n" + (text.isEmpty() ? "passage:" + pair.getPassage().getPassageId()
                : text);
    }

    private void log(String line) {
        if (log != null) {
            log.accept(line);
        }
    }

    static final String SYSTEM_DE = "Du prüfst für ein Sachbuch, ob eine Passage aus dem Recherchebestand eine Frage "
            + "des Buchauftrags belegt. Du urteilst nur nach der vorgelegten Passage und erfindest nichts. Antworte "
            + "nur mit JSON.";

    static final String SYSTEM_EN = "You check for a non-fiction book whether a passage of the research stock "
            + "supports a question of the book's mission. You judge only by the passage given and invent nothing. "
            + "Answer only with JSON.";

    static String prompt(String mission, String question, String domain, String passage, boolean cut,
                         boolean german) {
        String task = cutText(mission.replaceAll("\\s+", " ").trim(), MISSION_CHARS);
        String text = passage.trim() + (cut ? " …" : "");
        if (german) {
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + "Frage, die das Buch beantworten muss:\n" + question + "\n\n"
                    + "Passage aus dem Recherchebestand (Quelle: " + (domain.isEmpty() ? "unbekannt" : domain)
                    + "):\n\"\"\"\n" + text + "\n\"\"\"\n\n"
                    + "Aufgabe: Trägt diese Passage etwas zur Antwort auf genau diese Frage bei? Gehe in dieser "
                    + "Reihenfolge vor.\n\n"
                    + "1. Zitat: Gib das wörtliche Zitat aus der Passage an (höchstens 25 Wörter), das am ehesten "
                    + "zur Antwort beiträgt. Übernimm die Wörter genau so, wie sie in der Passage stehen. "
                    + "Wenn nichts beiträgt, bleibt das Zitat leer.\n"
                    + "2. Aussage: Schreibe in einem Satz, was die Passage zur Frage aussagt. Nennt die Aussage eine "
                    + "Zahl, muss diese Zahl im Zitat stehen. Wenn die Passage nichts dazu aussagt, schreibe "
                    + "\"nichts\".\n"
                    + "3. Urteil:\n"
                    + "   - \"BELEGT\": Sie enthält eine konkrete Aussage, die einen Teil der Frage beantwortet.\n"
                    + "   - \"HINWEIS\": Sie berührt die Frage, bleibt aber allgemein oder betrifft nur einen "
                    + "Randaspekt.\n"
                    + "   - \"KEIN\": Sie beantwortet die Frage nicht, zum Beispiel anderes Thema, andere Branche ohne "
                    + "Bezug, nur Überschrift, Inhaltsverzeichnis, Literaturliste oder Werbung.\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"zitat\", \"aussage\" und \"urteil\".";
        }
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + "Question the book must answer:\n" + question + "\n\n"
                + "Passage of the research stock (source: " + (domain.isEmpty() ? "unknown" : domain)
                + "):\n\"\"\"\n" + text + "\n\"\"\"\n\n"
                + "Task: Does this passage contribute to the answer to exactly this question? Go in this order.\n\n"
                + "1. Quote (zitat): give the verbatim quote from the passage (at most 25 words) that contributes "
                + "most to the answer. Copy the words exactly as they stand in the passage. If nothing contributes, "
                + "leave the quote empty.\n"
                + "2. Statement (aussage): write in one sentence what the passage says on the question. If the "
                + "statement names a number, that number must stand in the quote. If the passage says nothing on it, "
                + "write \"nothing\".\n"
                + "3. Verdict (urteil):\n"
                + "   - \"BELEGT\": it holds a concrete statement that answers part of the question.\n"
                + "   - \"HINWEIS\": it touches the question but stays general or concerns only a side aspect.\n"
                + "   - \"KEIN\": it does not answer the question, for example another subject, another trade "
                + "without relation, only a heading, a table of contents, a reference list or advertising.\n\n"
                + "Answer only with JSON with the fields \"zitat\", \"aussage\" and \"urteil\".";
    }

    /** The quote first, then the sentence, then the verdict; all required, nothing else allowed. */
    static String schema() {
        JsonObject properties = new JsonObject();
        JsonObject string = new JsonObject();
        string.addProperty("type", "string");
        properties.add("zitat", string);
        properties.add("aussage", string.deepCopy());
        JsonObject verdict = new JsonObject();
        verdict.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("BELEGT");
        values.add("HINWEIS");
        values.add("KEIN");
        verdict.add("enum", values);
        properties.add("urteil", verdict);
        JsonArray required = new JsonArray();
        required.add("zitat");
        required.add("aussage");
        required.add("urteil");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /** A verdict read from an answer, or why the answer holds none. */
    static final class Parsed {
        EvidenceVerdict.Kind kind;
        String quote = "";
        String statement = "";
        String problem = "";
    }

    static Parsed parse(String answer) {
        Parsed parsed = new Parsed();
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!element.isJsonObject()) {
                parsed.problem = "the answer is no JSON object";
                return parsed;
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            parsed.problem = "the answer is no JSON";
            return parsed;
        }
        parsed.kind = kindOf(text(json, "urteil"));
        if (parsed.kind == null) {
            parsed.problem = "\"urteil\" missing or not one of BELEGT, HINWEIS, KEIN";
            return parsed;
        }
        parsed.quote = cutText(text(json, "zitat"), QUOTE_CHARS);
        parsed.statement = cutText(text(json, "aussage"), STATEMENT_CHARS);
        return parsed;
    }

    /** The verdict word, read leniently (the words of the planning comparison are understood too). */
    static EvidenceVerdict.Kind kindOf(String word) {
        String w = word.toUpperCase(Locale.ROOT).replaceAll("[\\s\"'.]+", "");
        switch (w) {
            case "BELEGT":
            case "JA":
                return EvidenceVerdict.Kind.BELEGT;
            case "HINWEIS":
            case "TEILWEISE":
                return EvidenceVerdict.Kind.HINWEIS;
            case "KEIN":
            case "NEIN":
                return EvidenceVerdict.Kind.KEIN;
            default:
                return null;
        }
    }

    /** What the judge reads of a passage: at most {@code max} characters. */
    static String read(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The host of an address, lower case, without {@code www.}. */
    static String hostOf(String address) {
        String rest = address == null ? "" : address.trim().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int cut = rest.length();
        for (char stop : new char[]{'/', '?', '#', ' '}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        String host = rest.substring(0, cut);
        host = host.substring(host.lastIndexOf('@') + 1);
        int port = host.indexOf(':');
        if (port >= 0) {
            host = host.substring(0, port);
        }
        return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\d*\\.", "");
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().replaceAll("\\s+", " ").trim() : "";
    }

    private static String cutText(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim() + " …";
    }
}
