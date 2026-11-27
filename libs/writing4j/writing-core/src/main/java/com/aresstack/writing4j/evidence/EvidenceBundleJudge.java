package com.aresstack.writing4j.evidence;

import com.aresstack.knowledge4j.run.Pieces;
import com.aresstack.knowledge4j.model.ThinkingMode;
import com.aresstack.knowledge4j.scope.Fence;
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
import java.util.function.Consumer;

/**
 * The evidence check per question: ONE call judges a question against a bundle of candidate passages, not one call per
 * pair. Each passage comes with its heading path and the profile of its source, and the prompt holds the book's
 * Weidezaun, so a mixed source (one document on several subjects) is told apart passage by passage in the same call.
 *
 * <p>Per passage the model gives a verbatim quote, one sentence on what the passage says to the question, whether what
 * it says applies within the Weidezaun ({@code anwendbar}: JA, NEIN, UNKLAR) and the verdict BELEGT, HINWEIS or KEIN.
 * The program then checks the quote as for a single pair ({@link EvidenceVerdict#judged}): a BELEGT whose quote does
 * not stand in the passage or whose sentence names a number the quote lacks holds as HINWEIS. A passage that does not
 * apply is KEIN; a BELEGT whose applicability is unclear holds as HINWEIS, so the question stays open until it is
 * settled. Nothing is deleted and no question is dropped.</p>
 *
 * <p>The size of a bundle follows the model's context, not a fixed number: passages are added while the prompt and the
 * answer for them fit into {@link Options#getContextTokens()} (estimated at {@link Options#getCharsPerToken()}
 * characters per token); a passage longer than {@link Options#getPassageChars()} is cut. A question with more
 * candidates than one call holds gets several calls. A verdict on the same question and the same text is reused, a
 * page leftover ({@link #useNoise}) is not asked. A failed call or an answer that misses passages gets one repair for
 * the missing ones; a passage still without a verdict is FEHLER, never KEIN, and is offered again later.</p>
 */
public final class EvidenceBundleJudge {

    /** The stage name the questions run under in {@link Pieces}. */
    public static final String STAGE = "belegpruefung";

    /** One passage with what the check reads beside its text; immutable. */
    public static final class Candidate {
        private final EvidenceJudge.Passage passage;
        private final String headingPath;
        private final String profile;

        /**
         * @param headingPath the headings above the passage, empty when unknown
         * @param profile     the source profile in a line (publisher, kind, jurisdiction, date, subject, relation to
         *                    the fence), empty when unknown
         */
        public Candidate(EvidenceJudge.Passage passage, String headingPath, String profile) {
            if (passage == null) {
                throw new IllegalArgumentException("a candidate needs a passage");
            }
            this.passage = passage;
            this.headingPath = headingPath == null ? "" : headingPath.replaceAll("\\s+", " ").trim();
            this.profile = profile == null ? "" : profile.replaceAll("\\s+", " ").trim();
        }

        public EvidenceJudge.Passage getPassage() {
            return passage;
        }

        public String getHeadingPath() {
            return headingPath;
        }

        public String getProfile() {
            return profile;
        }
    }

    /** How the check works; immutable. Every value is a setting, none is a limit of the method. */
    public static final class Options {
        public static final int DEFAULT_PARALLEL = 1;
        public static final int DEFAULT_CONTEXT_TOKENS = 8192;
        public static final double DEFAULT_CHARS_PER_TOKEN = 3.0;
        public static final int DEFAULT_ANSWER_TOKENS_PER_PASSAGE = 160;

        private final int parallel;
        private final int contextTokens;
        private final double charsPerToken;
        private final int passageChars;
        private final int answerTokensPerPassage;

        public Options(int parallel, int contextTokens, double charsPerToken, int passageChars,
                       int answerTokensPerPassage) {
            if (parallel < 1 || contextTokens < 1024 || charsPerToken <= 0
                    || passageChars < EvidenceJudge.Options.MIN_PASSAGE_CHARS || answerTokensPerPassage < 50) {
                throw new IllegalArgumentException("parallel >= 1, contextTokens >= 1024, charsPerToken > 0, "
                        + "passageChars >= " + EvidenceJudge.Options.MIN_PASSAGE_CHARS
                        + ", answerTokensPerPassage >= 50 expected");
            }
            this.parallel = parallel;
            this.contextTokens = contextTokens;
            this.charsPerToken = charsPerToken;
            this.passageChars = passageChars;
            this.answerTokensPerPassage = answerTokensPerPassage;
        }

        public static Options defaults() {
            return new Options(DEFAULT_PARALLEL, DEFAULT_CONTEXT_TOKENS, DEFAULT_CHARS_PER_TOKEN,
                    EvidenceJudge.Options.DEFAULT_PASSAGE_CHARS, DEFAULT_ANSWER_TOKENS_PER_PASSAGE);
        }

        public Options withParallel(int value) {
            return new Options(value, contextTokens, charsPerToken, passageChars, answerTokensPerPassage);
        }

        public Options withContextTokens(int value) {
            return new Options(parallel, value, charsPerToken, passageChars, answerTokensPerPassage);
        }

        public Options withPassageChars(int value) {
            return new Options(parallel, contextTokens, charsPerToken, value, answerTokensPerPassage);
        }

        public int getParallel() {
            return parallel;
        }

        public int getContextTokens() {
            return contextTokens;
        }

        public double getCharsPerToken() {
            return charsPerToken;
        }

        public int getPassageChars() {
            return passageChars;
        }

        public int getAnswerTokensPerPassage() {
            return answerTokensPerPassage;
        }
    }

    /** What a call did: the passages it asked, the answers and the time. */
    public static final class Call {
        private final String questionId;
        private final int passages;
        private final int attempts;
        private final long millis;

        Call(String questionId, int passages, int attempts, long millis) {
            this.questionId = questionId;
            this.passages = passages;
            this.attempts = attempts;
            this.millis = millis;
        }

        public String getQuestionId() {
            return questionId;
        }

        public int getPassages() {
            return passages;
        }

        public int getAttempts() {
            return attempts;
        }

        public long getMillis() {
            return millis;
        }
    }

    /** The outcome of one question: a verdict per candidate that is no page leftover, and the calls made. */
    public static final class Result {
        private final List<EvidenceVerdict> verdicts;
        private final List<Call> calls;
        private final int reused;

        Result(List<EvidenceVerdict> verdicts, List<Call> calls, int reused) {
            this.verdicts = Collections.unmodifiableList(new ArrayList<EvidenceVerdict>(verdicts));
            this.calls = Collections.unmodifiableList(new ArrayList<Call>(calls));
            this.reused = reused;
        }

        public List<EvidenceVerdict> getVerdicts() {
            return verdicts;
        }

        public List<Call> getCalls() {
            return calls;
        }

        /** Verdicts taken over from the same question on the same text, without a call. */
        public int getReused() {
            return reused;
        }
    }

    /** The role: the slot of the evidence judge, a fixed schema, thinking off. */
    public static final ModelRole ROLE = ModelRole.builder("evidence-bundle-judge",
                    WritingSlots.PHASE_RESEARCH_EVIDENCE)
            .purpose("judges one question against a bundle of candidate passages with heading path and source "
                    + "profile, inside the Weidezaun (BELEGT, HINWEIS, KEIN; anwendbar JA, NEIN, UNKLAR); the program "
                    + "checks quote and numbers and only lowers")
            .promptFromCaller()
            .output(RoleOutput.schema(schema(), "urteile: nr, zitat, aussage, anwendbar, urteil"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final double TEMPERATURE = 0.0;
    static final int ATTEMPTS = 2;
    private static final int MISSION_CHARS = 2000;
    private static final int QUOTE_CHARS = 600;
    private static final int STATEMENT_CHARS = 400;
    /** Tokens of the fixed parts of the prompt besides the fence and the passages (instructions, question). */
    private static final int FIXED_PROMPT_TOKENS = 700;

    static final String SYSTEM_DE = "Du prüfst für ein Sachbuch, welche Passagen aus dem Recherchebestand eine Frage "
            + "des Buchauftrags belegen. Du urteilst über jede Passage nur nach ihr selbst und erfindest nichts. "
            + "Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You check for a non-fiction book which passages of the research stock support a "
            + "question of the book's mission. You judge each passage only by itself and invent nothing. Answer only "
            + "with JSON.";

    private final MainModelChat chat;
    private final Fence fence;
    private final boolean german;
    private final Options options;
    private final Clock clock;
    private final Consumer<String> log;
    private PassageNoise noise = PassageNoise.NONE;

    public EvidenceBundleJudge(MainModelChat chat, Fence fence, String language, Options options,
                               Consumer<String> log) {
        this(chat, fence, language, options, log, Clock.systemUTC());
    }

    EvidenceBundleJudge(MainModelChat chat, Fence fence, String language, Options options,
                        Consumer<String> log, Clock clock) {
        if (chat == null) {
            throw new IllegalArgumentException("the evidence check needs a chat");
        }
        this.chat = chat;
        this.fence = fence == null ? Fence.of("") : fence;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.log = log;
        this.clock = clock;
    }

    /** Page leftovers are not asked; {@code null} is {@link PassageNoise#NONE}. */
    public synchronized EvidenceBundleJudge useNoise(PassageNoise value) {
        this.noise = value == null ? PassageNoise.NONE : value;
        return this;
    }

    private synchronized PassageNoise currentNoise() {
        return noise;
    }

    /**
     * Every question with its candidates, {@link Options#getParallel()} questions at a time.
     *
     * @param known earlier verdicts (FEHLER ones are asked again); may be empty
     * @return one result per question, in the given order
     */
    public List<Result> judgeAll(Map<EvidenceJudge.Question, List<Candidate>> work,
                                 final Collection<EvidenceVerdict> known, final int round) {
        return judgeAll(work, known, round, null);
    }

    /**
     * As {@link #judgeAll(Map, Collection, int)}; {@code onEach} hears of every question as soon as it is judged, from
     * the thread that judged it, so a host can show the progress ({@code null}: nobody).
     */
    public List<Result> judgeAll(Map<EvidenceJudge.Question, List<Candidate>> work,
                                 final Collection<EvidenceVerdict> known, final int round, Runnable onEach) {
        return judgeAll(work, known, round, onEach, Pieces.parallel(options.getParallel()));
    }

    /**
     * As {@link #judgeAll(Map, Collection, int, Runnable)}; {@code pieces} runs the questions, one piece per question
     * named by its text, so a host can run each as a job of its own under the run's job. The stage is {@link #STAGE}.
     */
    public List<Result> judgeAll(Map<EvidenceJudge.Question, List<Candidate>> work,
                                 final Collection<EvidenceVerdict> known, final int round, Runnable onEach,
                                 Pieces pieces) {
        final Runnable each = onEach == null ? new Runnable() {
            @Override
            public void run() {
            }
        } : onEach;
        if (work.isEmpty()) {
            return new ArrayList<Result>();
        }
        final List<Map.Entry<EvidenceJudge.Question, List<Candidate>>> entries =
                new ArrayList<Map.Entry<EvidenceJudge.Question, List<Candidate>>>(work.entrySet());
        Pieces run = pieces == null ? Pieces.parallel(options.getParallel()) : pieces;
        return new ArrayList<Result>(run.run(STAGE, entries, entry -> entry.getKey().getText(), entry -> {
            try {
                return judge(entry.getKey(), entry.getValue(), known, round);
            } finally {
                each.run();
            }
        }));
    }

    /** One question: its candidates in bundles that fit the context, one call per bundle (plus one repair). */
    public Result judge(EvidenceJudge.Question question, List<Candidate> candidates,
                        Collection<EvidenceVerdict> known, int round) {
        PassageNoise leftover = currentNoise();
        Map<String, EvidenceVerdict> earlier = new HashMap<String, EvidenceVerdict>();
        if (known != null) {
            for (EvidenceVerdict verdict : known) {
                if (verdict.getKind() != EvidenceVerdict.Kind.FEHLER
                        && verdict.getQuestionId().equals(question.getId())) {
                    earlier.put(verdict.getTextKey(), verdict);
                }
            }
        }
        List<EvidenceVerdict> verdicts = new ArrayList<EvidenceVerdict>();
        List<Candidate> ask = new ArrayList<Candidate>();
        Map<String, Candidate> firstOfText = new LinkedHashMap<String, Candidate>();
        List<Candidate> sameAsAsked = new ArrayList<Candidate>();
        int reused = 0;
        for (Candidate candidate : candidates) {
            String read = EvidenceJudge.read(candidate.getPassage().getText(), options.getPassageChars());
            String key = PassageText.key(read);
            if (leftover.isNoise(PassageText.key(candidate.getPassage().getText()))) {
                continue;
            }
            EvidenceVerdict before = key.isEmpty() ? null : earlier.get(key);
            if (before != null) {
                verdicts.add(before);
                reused++;
            } else if (!key.isEmpty() && firstOfText.containsKey(key)) {
                sameAsAsked.add(candidate);
            } else {
                if (!key.isEmpty()) {
                    firstOfText.put(key, candidate);
                }
                ask.add(candidate);
            }
        }
        List<Call> calls = new ArrayList<Call>();
        Map<String, EvidenceVerdict> byPassage = new LinkedHashMap<String, EvidenceVerdict>();
        for (List<Candidate> bundle : bundles(question, ask)) {
            long start = clock.millis();
            int[] attempts = new int[1];
            for (EvidenceVerdict verdict : ask(question, bundle, round, attempts)) {
                byPassage.put(verdict.getPassageId(), verdict);
            }
            calls.add(new Call(question.getId(), bundle.size(), attempts[0], clock.millis() - start));
        }
        verdicts.addAll(byPassage.values());
        for (Candidate same : sameAsAsked) {
            String key = PassageText.key(EvidenceJudge.read(same.getPassage().getText(), options.getPassageChars()));
            EvidenceVerdict shared = byPassage.get(firstOfText.get(key).getPassage().getPassageId());
            if (shared != null) {
                verdicts.add(shared);
                reused++;
            }
        }
        int failed = 0;
        for (EvidenceVerdict verdict : verdicts) {
            failed += verdict.getKind() == EvidenceVerdict.Kind.FEHLER ? 1 : 0;
        }
        log("evidence check " + question.getId() + ": " + candidates.size() + " candidates, " + ask.size()
                + " asked in " + calls.size() + " calls, " + reused + " reused, " + failed + " without a verdict");
        return new Result(verdicts, calls, reused);
    }

    /**
     * The first of the given candidates, in their order, that fit one bundle of this question: what one call can judge
     * when the candidates are ranked and the context window, not a fixed number, decides how many.
     */
    public List<Candidate> fitting(EvidenceJudge.Question question, List<Candidate> ranked) {
        List<List<Candidate>> bundles = bundles(question, ranked);
        return bundles.isEmpty() ? Collections.<Candidate>emptyList() : bundles.get(0);
    }

    /** The candidates split into bundles whose prompt and answer fit the context; at least one passage each. */
    List<List<Candidate>> bundles(EvidenceJudge.Question question, List<Candidate> ask) {
        int fixedChars = (int) (FIXED_PROMPT_TOKENS * options.getCharsPerToken())
                + fence.describe(german, MISSION_CHARS).length() + question.getText().length();
        int budget = (int) (options.getContextTokens() * options.getCharsPerToken()) - fixedChars;
        int answerChars = (int) (options.getAnswerTokensPerPassage() * options.getCharsPerToken());
        List<List<Candidate>> out = new ArrayList<List<Candidate>>();
        List<Candidate> current = new ArrayList<Candidate>();
        int used = 0;
        for (Candidate candidate : ask) {
            int size = block(candidate, 0).length() + answerChars;
            if (!current.isEmpty() && used + size > budget) {
                out.add(current);
                current = new ArrayList<Candidate>();
                used = 0;
            }
            current.add(candidate);
            used += size;
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    /** One bundle: the call, then one repair for the passages the answer missed or got wrong. */
    private List<EvidenceVerdict> ask(EvidenceJudge.Question question, List<Candidate> bundle, int round,
                                      int[] attempts) {
        Map<String, EvidenceVerdict> done = new LinkedHashMap<String, EvidenceVerdict>();
        List<Candidate> open = new ArrayList<Candidate>(bundle);
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS && !open.isEmpty(); attempt++) {
            attempts[0]++;
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
            messages.add(ChatMessage.user(prompt(question, open)));
            int answerTokens = options.getAnswerTokensPerPassage() * open.size();
            MainModelChatResult result;
            try {
                result = chat.completeJson(messages, TEMPERATURE, answerTokens, schema());
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (result == null || !result.isOk()) {
                problem = result == null ? "model call returned nothing"
                        : "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue;
            }
            Map<String, JsonObject> answers = answers(result.getText());
            List<Candidate> still = new ArrayList<Candidate>();
            for (int i = 0; i < open.size(); i++) {
                Candidate candidate = open.get(i);
                JsonObject answer = answers.get(label(i));
                EvidenceVerdict verdict = answer == null ? null : verdict(question, candidate, answer, round);
                if (verdict == null) {
                    still.add(candidate);
                } else {
                    done.put(candidate.getPassage().getPassageId(), verdict);
                }
            }
            problem = still.isEmpty() ? "" : "the answer missed " + still.size() + " of " + open.size()
                    + " passages or gave no valid verdict for them";
            open = still;
        }
        for (Candidate candidate : open) {
            EvidenceJudge.Passage passage = candidate.getPassage();
            done.put(passage.getPassageId(), EvidenceVerdict.failed(question.getId(), passage.getPassageId(),
                    passage.getSourceId(), passage.getSourceUrl(),
                    EvidenceJudge.read(passage.getText(), options.getPassageChars()), problem, chat.modelName(),
                    round, clock.millis()));
        }
        if (!open.isEmpty()) {
            log("evidence check failed for " + question.getId() + " on " + open.size() + " passages: " + problem);
        }
        List<EvidenceVerdict> out = new ArrayList<EvidenceVerdict>();
        for (Candidate candidate : bundle) {
            out.add(done.get(candidate.getPassage().getPassageId()));
        }
        return out;
    }

    /** The verdict of one answer entry, or {@code null} when the entry holds no valid verdict. */
    private EvidenceVerdict verdict(EvidenceJudge.Question question, Candidate candidate, JsonObject answer,
                                    int round) {
        EvidenceVerdict.Kind kind = EvidenceJudge.kindOf(text(answer, "urteil"));
        String applies = text(answer, "anwendbar").toUpperCase(Locale.ROOT);
        if (kind == null || !(applies.equals("JA") || applies.equals("NEIN") || applies.equals("UNKLAR"))) {
            return null;
        }
        String statement = cut(text(answer, "aussage"), STATEMENT_CHARS);
        if (applies.equals("NEIN") && kind != EvidenceVerdict.Kind.KEIN) {
            kind = EvidenceVerdict.Kind.KEIN;
            statement = (german ? "nicht anwendbar: " : "does not apply: ") + statement;
        } else if (applies.equals("UNKLAR") && kind == EvidenceVerdict.Kind.BELEGT) {
            kind = EvidenceVerdict.Kind.HINWEIS;
            statement = (german ? "Anwendbarkeit unklar: " : "applicability unclear: ") + statement;
        }
        EvidenceJudge.Passage passage = candidate.getPassage();
        String read = EvidenceJudge.read(passage.getText(), options.getPassageChars());
        return EvidenceVerdict.judged(question.getId(), passage.getPassageId(), passage.getSourceId(),
                passage.getSourceUrl(), read, kind, cut(text(answer, "zitat"), QUOTE_CHARS), statement,
                chat.modelName(), round, clock.millis());
    }

    String prompt(EvidenceJudge.Question question, List<Candidate> bundle) {
        StringBuilder text = new StringBuilder();
        text.append(german ? "Weidezaun des Buchs:\n" : "Weidezaun (fence) of the book:\n")
                .append(fence.describe(german, MISSION_CHARS));
        text.append(german ? "Frage, die das Buch beantworten muss:\n" : "Question the book must answer:\n")
                .append(question.getText()).append("\n\n");
        text.append(german ? "Passagen aus dem Recherchebestand:\n\n" : "Passages of the research stock:\n\n");
        for (int i = 0; i < bundle.size(); i++) {
            text.append(block(bundle.get(i), i)).append('\n');
        }
        if (german) {
            text.append("Aufgabe: Prüfe jede Passage für sich, ob sie etwas zur Antwort auf genau diese Frage "
                    + "beiträgt. Überschrift und Steckbrief zeigen, wovon der Teil der Quelle handelt: Eine Quelle "
                    + "kann mehrere Gegenstände behandeln, und nur Teile davon liegen im Weidezaun.\n\n"
                    + "Für jede Passage, in dieser Reihenfolge:\n"
                    + "1. zitat: das wörtliche Zitat aus der Passage (höchstens 25 Wörter), das am ehesten zur "
                    + "Antwort beiträgt, genau so, wie es dort steht; leer, wenn nichts beiträgt.\n"
                    + "2. aussage: in einem Satz, was die Passage zur Frage sagt. Nennt die Aussage eine Zahl, muss "
                    + "sie im Zitat stehen. Sagt die Passage nichts dazu: \"nichts\".\n"
                    + "3. anwendbar: \"JA\", wenn das Gesagte für den Gegenstand des Buchs innerhalb des Weidezauns "
                    + "gilt; \"NEIN\", wenn es einen anderen Gegenstand, einen ausgeschlossenen Bereich oder einen "
                    + "anderen Rahmen betrifft; \"UNKLAR\", wenn sich das nicht entscheiden lässt.\n"
                    + "4. urteil: \"BELEGT\" (eine konkrete Aussage, die einen Teil der Frage beantwortet), "
                    + "\"HINWEIS\" (berührt die Frage, bleibt aber allgemein oder betrifft einen Randaspekt) oder "
                    + "\"KEIN\" (beantwortet die Frage nicht, etwa anderes Thema, nur Überschrift, "
                    + "Inhaltsverzeichnis, Literaturliste oder Werbung).\n\n"
                    + "Antworte nur mit JSON: {\"urteile\": [...]} mit einem Eintrag je Passage, jeweils mit den "
                    + "Feldern \"nr\" (die Kennung der Passage, etwa \"P1\"), \"zitat\", \"aussage\", "
                    + "\"anwendbar\" und \"urteil\".");
        } else {
            text.append("Task: Check each passage on its own whether it contributes to the answer to exactly this "
                    + "question. Heading and profile show what the part of the source is about: a source may treat "
                    + "several subjects, and only parts of it lie inside the fence.\n\n"
                    + "For each passage, in this order:\n"
                    + "1. zitat: the verbatim quote from the passage (at most 25 words) that contributes most, "
                    + "exactly as it stands there; empty if nothing contributes.\n"
                    + "2. aussage: in one sentence, what the passage says on the question. If it names a number, the "
                    + "number must stand in the quote. If the passage says nothing on it: \"nothing\".\n"
                    + "3. anwendbar: \"JA\" when what it says holds for the subject of the book inside the fence; "
                    + "\"NEIN\" when it concerns another subject, an excluded field or another frame; \"UNKLAR\" when "
                    + "this cannot be decided.\n"
                    + "4. urteil: \"BELEGT\" (a concrete statement that answers part of the question), \"HINWEIS\" "
                    + "(touches the question but stays general or concerns a side aspect) or \"KEIN\" (does not "
                    + "answer the question, such as another subject, only a heading, a table of contents, a "
                    + "reference list or advertising).\n\n"
                    + "Answer only with JSON: {\"urteile\": [...]} with one entry per passage, each with the fields "
                    + "\"nr\" (the passage's label, such as \"P1\"), \"zitat\", \"aussage\", \"anwendbar\" and "
                    + "\"urteil\".");
        }
        return text.toString();
    }

    /** One passage as the prompt shows it: label, source, profile, heading, then the text (cut if long). */
    String block(Candidate candidate, int index) {
        EvidenceJudge.Passage passage = candidate.getPassage();
        String read = EvidenceJudge.read(passage.getText(), options.getPassageChars());
        boolean cut = read.length() < passage.getText().length();
        StringBuilder text = new StringBuilder();
        text.append('[').append(label(index)).append("] ").append(german ? "Quelle: " : "Source: ")
                .append(passage.getDomain().isEmpty() ? (german ? "unbekannt" : "unknown") : passage.getDomain())
                .append('\n');
        if (!candidate.getProfile().isEmpty()) {
            text.append(german ? "Steckbrief: " : "Profile: ").append(candidate.getProfile()).append('\n');
        }
        if (!candidate.getHeadingPath().isEmpty()) {
            text.append(german ? "Überschrift: " : "Heading: ").append(candidate.getHeadingPath()).append('\n');
        }
        text.append("\"\"\"\n").append(read.trim()).append(cut ? " …" : "").append("\n\"\"\"\n");
        return text.toString();
    }

    static String label(int index) {
        return "P" + (index + 1);
    }

    /** The entries of an answer by their label; empty when the answer is no valid list. */
    static Map<String, JsonObject> answers(String answer) {
        Map<String, JsonObject> out = new HashMap<String, JsonObject>();
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            JsonElement list = element.isJsonObject() ? element.getAsJsonObject().get("urteile") : element;
            if (list == null || !list.isJsonArray()) {
                return out;
            }
            for (JsonElement entry : list.getAsJsonArray()) {
                if (entry.isJsonObject()) {
                    String label = text(entry.getAsJsonObject(), "nr").toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
                    if (label.matches("\\d+")) {
                        label = "P" + label;
                    }
                    if (!label.isEmpty() && !out.containsKey(label)) {
                        out.put(label, entry.getAsJsonObject());
                    }
                }
            }
        } catch (RuntimeException notJson) {
            return out;
        }
        return out;
    }

    /** {@code urteile}: a list of entries with label, quote, statement, applicability and verdict. */
    static String schema() {
        JsonObject string = new JsonObject();
        string.addProperty("type", "string");
        JsonObject properties = new JsonObject();
        properties.add("nr", string);
        properties.add("zitat", string.deepCopy());
        properties.add("aussage", string.deepCopy());
        properties.add("anwendbar", enumOf("JA", "NEIN", "UNKLAR"));
        properties.add("urteil", enumOf("BELEGT", "HINWEIS", "KEIN"));
        JsonArray required = new JsonArray();
        for (String field : new String[]{"nr", "zitat", "aussage", "anwendbar", "urteil"}) {
            required.add(field);
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "object");
        entry.add("properties", properties);
        entry.add("required", required);
        entry.addProperty("additionalProperties", false);
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", entry);
        JsonObject top = new JsonObject();
        JsonObject topProperties = new JsonObject();
        topProperties.add("urteile", list);
        JsonArray topRequired = new JsonArray();
        topRequired.add("urteile");
        top.addProperty("type", "object");
        top.add("properties", topProperties);
        top.add("required", topRequired);
        top.addProperty("additionalProperties", false);
        return top.toString();
    }

    private static JsonObject enumOf(String... values) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        JsonArray names = new JsonArray();
        for (String value : values) {
            names.add(value);
        }
        o.add("enum", names);
        return o;
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().replaceAll("\\s+", " ").trim() : "";
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim() + " …";
    }

    private void log(String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
