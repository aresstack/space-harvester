package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.run.Pieces;
import com.aresstack.knowledge4j.basket.OrderingHint;
import com.aresstack.writing4j.cornerstone.CornerstoneSieve;
import com.aresstack.writing4j.cornerstone.CornerstoneSurvey;
import com.aresstack.writing4j.cornerstone.FieldCards;
import com.aresstack.writing4j.cornerstone.GeneralLanguage;
import com.aresstack.writing4j.cornerstone.Snippets;
import com.aresstack.writing4j.cornerstone.TermCandidates;
import com.aresstack.knowledge4j.basket.BasketReader;
import com.aresstack.knowledge4j.passage.PassageView;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.knowledge4j.basket.SourceBasket;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.writing4j.evidence.EvidenceBundleJudge;
import com.aresstack.writing4j.evidence.EvidenceJudge;
import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;
import com.aresstack.writing4j.cluster.VectorMath;
import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.memory.QuestionEvidence;
import com.aresstack.writing4j.memory.ResearchMemory;
import com.aresstack.writing4j.memory.ResearchQuestion;
import com.aresstack.writing4j.memory.ResearchTopic;
import com.aresstack.writing4j.missionreport.BookSection;
import com.aresstack.writing4j.missionreport.MissionCheck;
import com.aresstack.writing4j.missionreport.MissionReport;
import com.aresstack.writing4j.planwriter.PassageLookup;
import com.aresstack.writing4j.planwriter.PlanSectionWriter;
import com.aresstack.writing4j.planwriter.WrittenSection;
import com.aresstack.writing4j.planwriter.WrittenSections;
import com.aresstack.writing4j.sourcerating.KnownDomains;
import com.aresstack.writing4j.sourcerating.SourceRater;
import com.aresstack.writing4j.sourcerating.SourceRating;
import com.aresstack.writing4j.sourcerating.SourceSample;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.model.chat.ModelCallMetrics;
import com.aresstack.writing4j.sectionplan.SectionPlan;
import com.aresstack.writing4j.sectionplan.SectionPlanner;
import com.aresstack.writing4j.sentencecheck.SentenceVerdicts;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Phase 4, from the basket to the book. The input is the basket ({@link SourceBasket}, references with a
 * {@link BasketReader}) with the book idea it was collected for (mission and Weidezaun with inclusions and exclusions,
 * {@link SourceBasket#fence()}) and the writing settings; nothing else. The concept of the research is not an input: mission and fence are the only measure.
 * <ol>
 * <li>{@link #prepare}: a profile per source against the fence ({@link SourceRater}); the passages of a source
 * outside the fence and of the parts of a mixed source outside it (by the profile's headings) are set aside with their
 * reason; then the cornerstones ({@link CornerstoneSurvey}): candidates without a model, the sieve with votes (safe
 * in, safe out, uncertain) and the fields as cards.</li>
 * <li>Between, both optional and never locked: {@link #withFields} (the reader drags up the wanted fields, only the
 * terms of the others are judged again with the widened fence) and the reader's sorting of the uncertain middle
 * into the mind map, given to {@link #open}; an empty middle asks nothing.</li>
 * <li>{@link #open}: questions from two sides, those the mission demands top down, the evaluative ones among them
 * (Pflichtfragen, critical ones too), together with the reader's own questions from the basket (always pursued, a gap
 * in the report when the basket does not answer them), and the outline of the chosen cornerstones in one call: chapters in an order a
 * newcomer understands, each with its questions naming the cornerstones they rest on ({@link CornerstoneOutline},
 * M24; the reader's ordering hints go along as a hint); near duplicates merged; every question checked against
 * mission and fence ({@link StockQuestions}). Questions outside the fence stay in the question list with their
 * reason and are not pursued. A demanded question goes to the chapter whose passages lie nearest to it.</li>
 * <li>{@link #write}: per question one evidence check ({@link EvidenceBundleJudge}, applicability against the
 * fence) over the places of its cornerstone (those the drafting named first), for a demanded question over the
 * passages nearest to it, as many as one call
 * of the check holds (the vectors only choose the candidates, the context window how many); the cornerstones as topics, their questions and the verdicts in the research memory;
 * the section plan from questions and evidence ({@link SectionPlanner}); the sections in order with the writing
 * memory and the bundled sentence check ({@link PlanSectionWriter}); the mission report ({@link MissionCheck}) and
 * per question where it is answered or why it stays open; a demanded question without evidence in the basket is a
 * gap for a further search.</li>
 * </ol>
 * Two modes, one code: the host gives the number of concurrent calls its research model takes
 * ({@link Settings} {@code capacity}, from the model port's capacity: 1 for a large model alone on one graphics card,
 * many for a cloud service). With 1 every step runs call after call; with more, profiles, the sieve, the cornerstone
 * questions, the question check and the evidence check run that many calls at once. The sieve votes with every model
 * the host offers. Bundling by the context window holds in both
 * modes. The logical order stays: the sections are written one after another with the writing memory whatever the
 * capacity. Every model call is counted per step: calls, failures, tokens, time and model switches. The steps with many
 * pieces (source profiles, sieve, evidence check, sections) report each piece as soon as it is done.
 */
public final class BookRun {

    /** A passage of the basket with its source. */
    public static final class BasketPassage {
        private final String passageId;
        private final SourceRef source;
        private final String domain;
        private final String heading;
        private final String text;

        public BasketPassage(String passageId, SourceRef source, String domain, String heading,
                             String text) {
            this.passageId = passageId;
            this.source = source;
            this.domain = domain == null || domain.isEmpty() ? SourceSample.hostOf(urlOf(source)) : domain;
            this.heading = heading == null ? "" : heading;
            this.text = text == null ? "" : text;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return source.id();
        }

        public String getHeading() {
            return heading;
        }

        public String getText() {
            return text;
        }
    }

    /** The writing settings; every value is a setting of the component it goes to. */
    public static final class Settings {
        public static final int DEFAULT_PLACES_PER_CORNERSTONE = 5;
        public static final int DEFAULT_PLACE_CHARS = 500;
        public static final double DEFAULT_MERGE_SIMILARITY = 0.90;

        private final String language;
        private final int targetWords;
        private final int capacity;
        private final SourceRater.Options rater;
        private final StockQuestions.Options questions;
        private final EvidenceBundleJudge.Options judge;
        private final PlanSectionWriter.Options writer;
        private TermCandidates.Options candidates = TermCandidates.Options.defaults();
        private CornerstoneSieve.Options sieve = CornerstoneSieve.Options.defaults();
        private FieldCards.Options cards = FieldCards.Options.defaults();
        private Snippets.Options snippets = Snippets.Options.defaults();
        private CornerstoneOutline.Options outline = CornerstoneOutline.Options.defaults();
        private int placesPerCornerstone = DEFAULT_PLACES_PER_CORNERSTONE;
        private int placeChars = DEFAULT_PLACE_CHARS;
        private double mergeSimilarity = DEFAULT_MERGE_SIMILARITY;
        private List<String> subjectWords = Collections.emptyList();
        private Pieces pieces;
        private com.aresstack.writing4j.sourcerating.ProfileMemory profiles;

        /**
         * @param capacity the concurrent calls the research model takes, as the host's model port gives it (at
         *                 least 1); it sets the parallelism of every step but the writing
         */
        public Settings(String language, int targetWords, int capacity, SourceRater.Options rater,
                        StockQuestions.Options questions, EvidenceBundleJudge.Options judge,
                        PlanSectionWriter.Options writer) {
            this.language = language == null || language.trim().isEmpty() ? "de" : language.trim();
            this.targetWords = targetWords;
            this.capacity = Math.max(1, capacity);
            SourceRater.Options r = rater == null ? SourceRater.Options.defaults() : rater;
            this.rater = new SourceRater.Options(this.capacity, r.getPassageChars());
            this.questions = (questions == null ? StockQuestions.Options.defaults() : questions)
                    .withParallel(this.capacity);
            this.judge = (judge == null ? EvidenceBundleJudge.Options.defaults() : judge).withParallel(this.capacity);
            this.writer = (writer == null ? PlanSectionWriter.Options.defaults().withLanguage(this.language) : writer)
                    .withParallel(1).withCheckBundled(true).withAttributionTail(false);
            this.sieve = this.sieve.withParallel(this.capacity);
            this.outline = this.outline.withParallel(this.capacity);
        }

        public static Settings of(String language, int targetWords) {
            return new Settings(language, targetWords, 1, null, null, null, null);
        }

        /** The candidates without a model: minimums, depth and the floor of the general language. */
        public Settings withCandidates(TermCandidates.Options value) {
            candidates = value == null ? TermCandidates.Options.defaults() : value;
            return this;
        }

        /** The sieve: places, rounds and the vote limits of safe in and safe out (the parallelism stays the capacity). */
        public Settings withSieve(CornerstoneSieve.Options value) {
            sieve = (value == null ? CornerstoneSieve.Options.defaults() : value).withParallel(capacity);
            return this;
        }

        /** The examples at a term: how many, sentences around, the shortest standing sentence, back references. */
        public Settings withSnippets(Snippets.Options value) {
            snippets = value == null ? Snippets.Options.defaults() : value;
            return this;
        }

        public Settings withCards(FieldCards.Options value) {
            cards = value == null ? FieldCards.Options.defaults() : value;
            return this;
        }

        /**
         * The evidence of a question from a cornerstone: its {@code places} best places of {@code chars} characters
         * first (anchors first, one per source), then its other passages.
         */
        public Settings withCornerstonePlaces(int places, int chars) {
            if (places < 1 || chars < 100) {
                throw new IllegalArgumentException("places >= 1, chars >= 100 expected");
            }
            placesPerCornerstone = places;
            placeChars = chars;
            return this;
        }

        /** The outline from the chosen cornerstones (chapters with questions in one call, M24). */
        public Settings withOutline(CornerstoneOutline.Options value) {
            outline = (value == null ? CornerstoneOutline.Options.defaults() : value).withParallel(capacity);
            return this;
        }

        /** Two questions at or above this cosine similarity are one question. */
        public Settings withMergeSimilarity(double value) {
            if (value <= 0 || value > 1) {
                throw new IllegalArgumentException("0 < mergeSimilarity <= 1 expected");
            }
            mergeSimilarity = value;
            return this;
        }

        /** The words of the subject the anchors are found with; empty to take them from mission and fence. */
        /**
         * How the pieces of a stage run (one source profile per source): {@code null} or not set, on a pool of
         * {@link #getCapacity()} threads in the caller's job; a host may run each piece as a job of its own under the
         * run's job, so the reader sees which source is being described.
         */
        public Settings withPieces(Pieces value) {
            this.pieces = value;
            return this;
        }

        /**
         * Where source profiles are kept between runs ({@code null}: nowhere): a cancelled or interrupted run that
         * starts again takes every profile whose source and fence did not change, and asks the model only for the rest.
         */
        public Settings withProfileMemory(com.aresstack.writing4j.sourcerating.ProfileMemory value) {
            this.profiles = value;
            return this;
        }

        /** Where source profiles are kept; never {@code null}. */
        public com.aresstack.writing4j.sourcerating.ProfileMemory getProfileMemory() {
            return profiles == null ? com.aresstack.writing4j.sourcerating.ProfileMemory.NONE : profiles;
        }

        /** How the pieces of a stage run; never {@code null}. */
        public Pieces getPieces() {
            return pieces == null ? Pieces.parallel(capacity) : pieces;
        }

        public Settings withSubjectWords(List<String> value) {
            subjectWords = value == null ? Collections.<String>emptyList() : new ArrayList<String>(value);
            return this;
        }

        public String getLanguage() {
            return language;
        }

        public int getCapacity() {
            return capacity;
        }
    }

    /** Called with the step, the units done and the units of the step (calls, or terms). */
    public interface Progress {
        Progress NONE = (step, done, total) -> {
        };

        void step(String step, int done, int total);
    }

    /** What one step's model calls came to. */
    public static final class Step {
        private final String name;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong answerTokens = new AtomicLong();
        final AtomicLong modelMillis = new AtomicLong();
        long millis;

        Step(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int getCalls() {
            return calls.get();
        }

        public int getFailures() {
            return failures.get();
        }

        public long getPromptTokens() {
            return promptTokens.get();
        }

        public long getAnswerTokens() {
            return answerTokens.get();
        }

        public long getMillis() {
            return millis;
        }
    }

    /** A cornerstone as a topic of the book: its term, its passages (anchors first) and the questions drafted from it. */
    public static final class Group {
        private final String id;
        private final String title;
        private final List<String> passageIds;
        private final String problem;

        Group(String id, String title, List<String> passageIds, String problem) {
            this.id = id;
            this.title = title;
            this.passageIds = Collections.unmodifiableList(new ArrayList<String>(passageIds));
            this.problem = problem;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public List<String> getPassageIds() {
            return passageIds;
        }

        /** Why the cornerstone got no questions; {@code ""} when the drafting succeeded. */
        public String getProblem() {
            return problem;
        }
    }

    /** A question of the book, with its side and its verdict against mission and fence. */
    public static final class Question {
        /**
         * Where it comes from: the mission demands it, the places of a cornerstone answer it, or the reader asked it
         * in the basket ({@code SourceBasket#questions}). A reader's question is demanded like one of the mission
         * and always pursued: the check against the fence only notes its verdict, the evidence check decides, and
         * without evidence it is a gap in the report, never dropped.
         */
        public enum Origin {
            PFLICHT, ECKPFEILER, LESER;

            /** Whether the work must answer it or name it as a gap: the mission's and the reader's questions. */
            public boolean isDemanded() {
                return this != ECKPFEILER;
            }
        }

        private final String id;
        private final String text;
        private final Origin origin;
        private final String groupId;
        private final StockQuestions.Verdict verdict;
        /** The vector of a demanded question, to rank its candidates; {@code null} for a question of a cornerstone. */
        EmbeddingPort.EmbeddingVector vector;
        /** The candidates of a cornerstone question: the places the drafting named, then those of its cornerstone. */
        final List<String> candidates = new ArrayList<String>();

        Question(String id, String text, Origin origin, String groupId, StockQuestions.Verdict verdict) {
            this.id = id;
            this.text = text;
            this.origin = origin;
            this.groupId = groupId;
            this.verdict = verdict;
        }

        public Origin getOrigin() {
            return origin;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        /** The cornerstone (topic) it belongs to; {@code ""} when the basket has none. */
        public String getGroupId() {
            return groupId;
        }

        public StockQuestions.Verdict getVerdict() {
            return verdict;
        }

        /**
         * Pursued: a question of the reader always, else inside the fence, unclear (the evidence check decides per
         * passage) or not checked.
         */
        public boolean isPursued() {
            return origin == Origin.LESER || verdict.getRelation() != StockQuestions.Relation.AUSSEN;
        }
    }

    /** The basket opened up: profiles, groups and questions. */
    public static final class Opening {
        private final Fence fence;
        private final List<BasketPassage> passages;
        private final Map<String, String> setAside;
        private final Map<String, EmbeddingPort.EmbeddingVector> vectors;
        private final Map<String, SourceRating> profiles;
        private final List<Group> groups;
        private final List<Question> questions;
        private final List<Step> steps;
        private final Switches switches;
        private final List<OrderingHint> hints;
        /** The working title of the book (from the questions of the mission), else the mission; may be empty. */
        String title = "";

        Opening(Fence fence, List<BasketPassage> passages, Map<String, String> setAside,
                Map<String, EmbeddingPort.EmbeddingVector> vectors, Map<String, SourceRating> profiles,
                List<Group> groups, List<Question> questions, List<Step> steps, Switches switches,
                List<OrderingHint> hints) {
            this.hints = Collections.unmodifiableList(new ArrayList<OrderingHint>(hints));
            this.fence = fence;
            this.passages = Collections.unmodifiableList(new ArrayList<BasketPassage>(passages));
            this.setAside = Collections.unmodifiableMap(new LinkedHashMap<String, String>(setAside));
            this.vectors = vectors;
            this.profiles = Collections.unmodifiableMap(new LinkedHashMap<String, SourceRating>(profiles));
            this.groups = Collections.unmodifiableList(new ArrayList<Group>(groups));
            this.questions = Collections.unmodifiableList(new ArrayList<Question>(questions));
            this.steps = new ArrayList<Step>(steps);
            this.switches = switches;
        }

        public List<BasketPassage> getPassages() {
            return passages;
        }

        /** Passages outside the fence by their source's profile, no candidates: the reason by passage id. */
        public Map<String, String> getSetAside() {
            return setAside;
        }

        public Map<String, SourceRating> getProfiles() {
            return profiles;
        }

        /** The cornerstones with their passages, in rank order. */
        public List<Group> getGroups() {
            return groups;
        }

        /** The fence of the book: the basket's, widened by the fields the reader wanted or left lying. */
        public Fence getFence() {
            return fence;
        }

        /** The reader's order (of Phase 3 and of the sorting), non-binding. */
        public List<OrderingHint> getHints() {
            return hints;
        }

        public List<Question> getQuestions() {
            return questions;
        }

        public List<Step> getSteps() {
            return Collections.unmodifiableList(steps);
        }

        public int getModelSwitches() {
            return switches.count;
        }
    }

    /** Where a question stands at the end. */
    public static final class QuestionLine {
        /** NACHRECHERCHE: a question the mission demands without direct evidence in the basket. */
        public enum State { BEANTWORTET, NUR_HINWEISE, OFFEN, NACHRECHERCHE, AUSSERHALB }

        private final Question question;
        private final State state;
        private final String section;
        private final String reason;
        private final int direct;
        private final int leads;

        QuestionLine(Question question, State state, String section, String reason, int direct, int leads) {
            this.question = question;
            this.state = state;
            this.section = section;
            this.reason = reason;
            this.direct = direct;
            this.leads = leads;
        }

        public Question getQuestion() {
            return question;
        }

        public State getState() {
            return state;
        }

        /** The title of the section that answers it, {@code ""} when none does. */
        public String getSection() {
            return section;
        }

        /** Why it stays open, only has leads or lies outside; {@code ""} when answered. */
        public String getReason() {
            return reason;
        }

        public int getDirect() {
            return direct;
        }

        public int getLeads() {
            return leads;
        }
    }

    /** The outcome of a whole run. */
    public static final class Result {
        private final Opening opening;
        private final List<EvidenceVerdict> verdicts;
        private final SectionPlan plan;
        private final List<WrittenSection> sections;
        private final MissionReport report;
        private final List<QuestionLine> lines;
        private final List<Step> steps;
        private final int citedOutsideFence;

        Result(Opening opening, List<EvidenceVerdict> verdicts, SectionPlan plan, List<WrittenSection> sections,
               MissionReport report, List<QuestionLine> lines, List<Step> steps, int citedOutsideFence) {
            this.opening = opening;
            this.verdicts = Collections.unmodifiableList(new ArrayList<EvidenceVerdict>(verdicts));
            this.plan = plan;
            this.sections = Collections.unmodifiableList(new ArrayList<WrittenSection>(sections));
            this.report = report;
            this.lines = Collections.unmodifiableList(new ArrayList<QuestionLine>(lines));
            this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
            this.citedOutsideFence = citedOutsideFence;
        }

        public Opening getOpening() {
            return opening;
        }

        public List<EvidenceVerdict> getVerdicts() {
            return verdicts;
        }

        public SectionPlan getPlan() {
            return plan;
        }

        public List<WrittenSection> getSections() {
            return sections;
        }

        public MissionReport getReport() {
            return report;
        }

        public List<QuestionLine> getQuestionLines() {
            return lines;
        }

        /** The steps of the opening and the writing in order. */
        public List<Step> getSteps() {
            return steps;
        }

        public int getModelSwitches() {
            return opening.getModelSwitches();
        }

        /** Distinct passages cited in the book whose source the profile places outside the fence. */
        public int getCitedOutsideFence() {
            return citedOutsideFence;
        }

        /**
         * The mission report as text, then every question of the basket with where it is answered or why it stays
         * open.
         */
        public String report(java.time.ZoneId zone) {
            StringBuilder out = new StringBuilder(getReport().text(zone));
            out.append("\n\nFragen aus dem Korb\n");
            for (QuestionLine line : getQuestionLines()) {
                out.append("- ").append(line.getQuestion().getId()).append(' ').append(line.getState()).append(": ")
                        .append(line.getQuestion().getText())
                        .append(line.getSection().isEmpty() ? "" : " → " + line.getSection())
                        .append(line.getReason().isEmpty() ? "" : " (" + line.getReason() + ")").append('\n');
            }
            return out.toString();
        }

        /** The book as Markdown: chapter and section headings, the written text with its markers. */
        public String book() {
            StringBuilder out = new StringBuilder();
            String chapter = null;
            for (WrittenSection section : sections) {
                if (!section.getChapterTitle().equals(chapter)) {
                    chapter = section.getChapterTitle();
                    out.append("# ").append(chapter).append("\n\n");
                }
                out.append("## ").append(section.getTitle()).append("\n\n");
                out.append(section.isOk() ? section.getText().trim() : "(" + section.getStatus() + ": "
                        + section.getFailure() + ")").append("\n\n");
            }
            return out.toString();
        }
    }

    private final MainModelChat researchChat;
    private final MainModelChat writerChat;
    private final MainModelChat checkChat;
    private final EmbeddingPort embeddings;
    private final List<MainModelChat> sieveModels;
    private final Settings settings;
    private final Clock clock;
    private final Consumer<String> log;

    /**
     * @param researchChat the model of profiles, questions and evidence check
     * @param writerChat   the model of the writer; the same as {@code researchChat} avoids a model switch
     * @param checkChat    the model of the sentence check; {@code null} leaves the sentences unchecked
     * @param embeddings   the vectors of passages and questions (nearest passages of a demanded question, merging);
     *                     {@code null} without an embedding model: shared words choose the passages, and only questions
     *                     with the same wording are merged
     */
    public BookRun(MainModelChat researchChat, MainModelChat writerChat, MainModelChat checkChat,
                   EmbeddingPort embeddings, Settings settings, Clock clock, Consumer<String> log) {
        this(researchChat, writerChat, checkChat, embeddings, Collections.<MainModelChat>emptyList(), settings, clock,
                log);
    }

    /**
     * @param sieveModels the models the host offers for the sieve, each one votes; empty for the research model alone
     */
    public BookRun(MainModelChat researchChat, MainModelChat writerChat, MainModelChat checkChat,
                   EmbeddingPort embeddings, List<MainModelChat> sieveModels, Settings settings, Clock clock,
                   Consumer<String> log) {
        if (researchChat == null || writerChat == null) {
            throw new IllegalArgumentException("the run needs a research model and a writer model");
        }
        this.researchChat = researchChat;
        this.writerChat = writerChat;
        this.checkChat = checkChat;
        this.embeddings = embeddings;
        this.sieveModels = sieveModels == null || sieveModels.isEmpty()
                ? Collections.singletonList(researchChat) : new ArrayList<MainModelChat>(sieveModels);
        this.settings = settings == null ? Settings.of("de", 0) : settings;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.log = log == null ? line -> {
        } : log;
    }

    /** The whole run without the reader: {@link #prepare}, {@link #open} with the safe terms, {@link #write}. */
    public Result run(SourceBasket basket, BasketReader reader) throws IOException {
        return write(open(basket, reader), Progress.NONE);
    }

    /** The address of a source for its name and site: the id of a source whose id is a web address, else none. */
    static String urlOf(SourceRef source) {
        String id = source.id();
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? id : "";
    }

    // ------------------------------------------------------------------------------------------------- open

    /** The basket prepared for the reader: profiles, passages set aside and the cornerstones with their votes. */
    public static final class Preparation {
        private final SourceBasket basket;
        private final List<BasketPassage> passages;
        private final List<BasketPassage> inside;
        private final Map<String, String> setAside;
        private final Map<String, SourceRating> profiles;
        private final CornerstoneSurvey.Survey survey;
        private final List<Step> steps;
        private final Switches switches;

        Preparation(SourceBasket basket, List<BasketPassage> passages, List<BasketPassage> inside,
                    Map<String, String> setAside, Map<String, SourceRating> profiles, CornerstoneSurvey.Survey survey,
                    List<Step> steps, Switches switches) {
            this.basket = basket;
            this.passages = passages;
            this.inside = inside;
            this.setAside = setAside;
            this.profiles = profiles;
            this.survey = survey;
            this.steps = steps;
            this.switches = switches;
        }

        /** The cornerstones: three sets with their numbers, the fields as cards, the fence. */
        public CornerstoneSurvey.Survey getSurvey() {
            return survey;
        }

        public Map<String, String> getSetAside() {
            return Collections.unmodifiableMap(setAside);
        }

        public Map<String, SourceRating> getProfiles() {
            return Collections.unmodifiableMap(profiles);
        }

        public int getPassageCount() {
            return passages.size();
        }

        public List<Step> getSteps() {
            return Collections.unmodifiableList(steps);
        }

        /** The ordering hints that came with the basket (the reader's order of Phase 3). */
        public List<OrderingHint> basketHints() {
            return basket.hints();
        }
    }

    /** Without the reader: {@link #prepare}, then {@link #open} with the safe terms. */
    public Opening open(SourceBasket basket, BasketReader reader) {
        return open(prepare(basket, reader, Progress.NONE), (Map<String, String>) null, Progress.NONE);
    }

    /**
     * Profiles, passages outside the fence set aside, cornerstones (candidates, sieve, fields); the fence is the
     * basket's.
     */
    public Preparation prepare(SourceBasket basket, BasketReader reader, Progress progress) {
        Progress report = progress == null ? Progress.NONE : progress;
        Fence idea = basket.fence();
        Switches switches = new Switches();
        List<Step> steps = new ArrayList<Step>();
        List<BasketPassage> passages = new ArrayList<BasketPassage>();
        Map<String, List<BasketPassage>> bySource = new LinkedHashMap<String, List<BasketPassage>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (SourceRef source : basket.sources()) {
            List<BasketPassage> list = new ArrayList<BasketPassage>();
            for (PassageView read : reader.passages(source)) {
                if (!read.text().trim().isEmpty() && seen.add(read.id())) {
                    list.add(new BasketPassage(read.id(), source, "", read.headingPath(), read.text()));
                }
            }
            if (!list.isEmpty()) {
                bySource.put(source.id(), list);
                passages.addAll(list);
            }
        }

        // 1 source profiles
        Step profileStep = step(steps, SourceRater.STAGE);
        long began = clock.millis();
        List<SourceSample> samples = new ArrayList<SourceSample>();
        for (List<BasketPassage> list : bySource.values()) {
            BasketPassage first = list.get(0);
            List<String> headings = new ArrayList<String>();
            for (BasketPassage passage : list) {
                headings.add(passage.heading);
            }
            samples.add(new SourceSample(first.getSourceId(), urlOf(first.source), first.source.title(),
                    first.domain, first.text, headings));
        }
        report.step(profileStep.getName(), 0, samples.size());
        SourceRater rater = new SourceRater(new Metered(researchChat, profileStep, switches), KnownDomains.none(),
                settings.language, settings.rater, log, idea);
        Map<String, SourceRating> profiles = new LinkedHashMap<String, SourceRating>();
        // Profiles of an earlier, cancelled or interrupted run with the same source and fence are taken as they are.
        final com.aresstack.writing4j.sourcerating.ProfileMemory memory = settings.getProfileMemory();
        Map<String, SourceRating> earlier = new LinkedHashMap<String, SourceRating>();
        final Map<String, String> keyOf = new java.util.concurrent.ConcurrentHashMap<String, String>();
        for (SourceSample sample : samples) {
            String key = rater.memoryKey(sample);
            keyOf.put(sample.getSourceId(), key);
            SourceRating kept = SourceRating.fromJson(memory.recall(key));
            if (kept != null && kept.isOk() && kept.getSourceId().equals(sample.getSourceId())) {
                earlier.put(rater.groupKey(sample), kept);
            }
        }
        if (!earlier.isEmpty()) {
            log.accept("book run: " + earlier.size() + " of " + samples.size() + " source profiles from memory");
        }
        for (SourceRating rating : rater.rateAll(samples, earlier,
                counter(report, profileStep.getName(), samples.size()), settings.getPieces(), made -> {
                    String key = keyOf.get(made.getSourceId());
                    if (key != null) {
                        try {
                            memory.remember(key, made.toJson());
                        } catch (RuntimeException notKept) {
                            log.accept("book run: a source profile was not kept: " + notKept.getClass().getSimpleName());
                        }
                    }
                })) {
            profiles.put(rating.getSourceId(), rating);
        }
        report.step(profileStep.getName(), samples.size(), samples.size());
        profileStep.millis = clock.millis() - began;
        log.accept("book run: " + profiles.size() + " source profiles");

        // 2 passages outside the fence set aside by the profile and the heading path
        Map<String, String> setAside = new LinkedHashMap<String, String>();
        List<BasketPassage> inside = new ArrayList<BasketPassage>();
        for (BasketPassage passage : passages) {
            String reason = outside(passage, profiles.get(passage.getSourceId()));
            if (reason.isEmpty()) {
                inside.add(passage);
            } else {
                setAside.put(passage.passageId, reason);
            }
        }

        // 3 cornerstones: candidates without a model, sieve with votes, fields as cards
        final Step sieveStep = step(steps, "sieb");
        began = clock.millis();
        List<TermCandidates.Text> texts = new ArrayList<TermCandidates.Text>();
        for (BasketPassage passage : inside) {
            texts.add(new TermCandidates.Text(passage.passageId, passage.getSourceId(), passage.text, passage.heading,
                    passage.source.title()));
        }
        CornerstoneSurvey.Survey survey = cornerstones(sieveStep, switches).survey(texts, idea, settings.subjectWords,
                (done, total) -> report.step(sieveStep.getName(), done, total));
        sieveStep.millis = clock.millis() - began;
        log.accept("book run: " + inside.size() + " of " + passages.size() + " passages inside, " + setAside.size()
                + " set aside outside the fence; " + survey.numbers());
        return new Preparation(basket, passages, inside, setAside, profiles, survey, steps, switches);
    }

    /**
     * The reader's answer on the field cards: the fence widened by the wanted fields and the ones left lying, the
     * terms of the latter judged again. {@code wanted == null}: the reader skipped the cards, nothing changes.
     */
    public Preparation withFields(Preparation prepared, Set<String> wanted, Progress progress) {
        if (wanted == null || prepared.survey.getFields().isEmpty()) {
            return prepared;
        }
        final Progress report = progress == null ? Progress.NONE : progress;
        List<Step> steps = new ArrayList<Step>(prepared.steps);
        final Step step = step(steps, "sieb-zaun");
        long began = clock.millis();
        CornerstoneSurvey.Survey survey = cornerstones(step, prepared.switches).withFields(prepared.survey, wanted,
                (done, total) -> report.step(step.getName(), done, total));
        step.millis = clock.millis() - began;
        return new Preparation(prepared.basket, prepared.passages, prepared.inside, prepared.setAside,
                prepared.profiles, survey, steps, prepared.switches);
    }

    private CornerstoneSurvey cornerstones(Step step, Switches switches) {
        List<MainModelChat> models = new ArrayList<MainModelChat>();
        for (MainModelChat model : sieveModels) {
            models.add(new Metered(model, step, switches));
        }
        return new CornerstoneSurvey(new TermCandidates(GeneralLanguage.forLanguage(settings.language),
                settings.candidates), new CornerstoneSieve(settings.language, settings.sieve, log),
                new FieldCards(new Metered(researchChat, step, switches), settings.language, settings.cards, log),
                new Snippets(settings.snippets), models, log);
    }

    /**
     * Questions from the cornerstones and from the mission, merged and checked against the fence.
     *
     * @param sorted the uncertain terms the reader sorted into the mind map (key to the key or text of the term above
     *               it, {@code ""} at the top); {@code null} or empty takes the safe terms only
     */
    public Opening open(Preparation prepared, Map<String, String> sorted, Progress progress) {
        return open(prepared, CornerstoneSurvey.chosen(prepared.survey, sorted, prepared.basket.hints()), progress);
    }

    /**
     * Questions from the terms the reader chose ({@link CornerstoneSurvey#selected} with the basket's hints from
     * {@link Preparation#basketHints}, or {@link CornerstoneSurvey#chosen}) and from the mission, merged and checked.
     */
    public Opening open(Preparation prepared, CornerstoneSurvey.Chosen chosen, Progress progress) {
        final Progress report = progress == null ? Progress.NONE : progress;
        List<Step> steps = new ArrayList<Step>(prepared.steps);
        Switches switches = prepared.switches;
        CornerstoneSurvey.Survey survey = prepared.survey;
        Fence idea = survey.getFence().widened(Collections.<String>emptyList(), chosen.getExcludedFields());
        Map<String, BasketPassage> byId = byId(prepared.inside);

        // 4 questions from two sides: demanded by the mission, and the outline of the chosen cornerstones in one call
        // (chapters with questions, each naming the cornerstones it rests on; M24)
        final Step questionStep = step(steps, "fragen");
        long began = clock.millis();
        Map<String, TermCandidates.Text> textsById = texts(byId);
        List<TermCandidates.Candidate> cornerstones = chosen.getCornerstones();
        List<CornerstoneOutline.Cornerstone> outlined = new ArrayList<CornerstoneOutline.Cornerstone>();
        List<List<String>> placesOf = new ArrayList<List<String>>();
        for (TermCandidates.Candidate cornerstone : cornerstones) {
            List<String> ids = new ArrayList<String>();
            String first = "";
            for (CornerstoneSieve.Place place : CornerstoneSieve.places(cornerstone, chosen.passagesOf(cornerstone),
                    textsById, settings.placeChars)) {
                if (ids.size() >= settings.placesPerCornerstone) {
                    break;
                }
                if (ids.isEmpty()) {
                    first = CornerstoneSieve.places(cornerstone, Collections.singletonList(place.getPassageId()),
                            textsById, settings.outline.getPlaceChars()).get(0).getText();
                }
                ids.add(place.getPassageId());
            }
            for (String id : chosen.passagesOf(cornerstone)) {
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
            placesOf.add(ids);
            outlined.add(new CornerstoneOutline.Cornerstone(cornerstone.getTerm(), fieldOf(survey, cornerstone),
                    first));
        }
        report.step(questionStep.getName(), 0, 2);
        StockQuestions questions = new StockQuestions(new Metered(researchChat, questionStep, switches), idea,
                settings.language, settings.questions, log);
        StockQuestions.Drafted demanded = questions.draftMission();
        report.step(questionStep.getName(), 1, 2);
        CornerstoneOutline.Result outline = new CornerstoneOutline(new Metered(researchChat, questionStep, switches),
                idea, settings.language, settings.outline, log).outline(outlined, chosen.getHints());
        report.step(questionStep.getName(), 2, 2);
        questionStep.millis = clock.millis() - began;
        if (!demanded.getProblem().isEmpty()) {
            log.accept("book run: no questions of the mission: " + demanded.getProblem());
        }
        for (String problem : outline.getProblems()) {
            log.accept("book run: outline: " + problem);
        }

        // 5 the chapters as groups; near duplicates merged
        Step mergeStep = step(steps, "zusammenlegen");
        began = clock.millis();
        List<String> texts = new ArrayList<String>();
        List<Question.Origin> origins = new ArrayList<Question.Origin>();
        List<String> topics = new ArrayList<String>();
        List<List<String>> candidates = new ArrayList<List<String>>();
        // The reader's own questions first: merged with a near duplicate of the mission, the reader's wording stays.
        for (String question : prepared.basket.questions()) {
            texts.add(question);
            origins.add(Question.Origin.LESER);
            topics.add("");
            candidates.add(new ArrayList<String>());
        }
        for (String question : demanded.getQuestions()) {
            texts.add(question);
            origins.add(Question.Origin.PFLICHT);
            topics.add("");
            candidates.add(new ArrayList<String>());
        }
        List<Group> groups = new ArrayList<Group>();
        Map<String, List<String>> members = new LinkedHashMap<String, List<String>>();
        int number = 0;
        for (CornerstoneOutline.Chapter chapter : outline.getChapters()) {
            String id = "kapitel-" + (++number);
            List<String> inChapter = new ArrayList<String>();
            for (CornerstoneOutline.Question question : chapter.getQuestions()) {
                List<String> ids = new ArrayList<String>();
                for (int index : question.getCornerstones()) {
                    for (String passage : placesOf.get(index)) {
                        if (!ids.contains(passage)) {
                            ids.add(passage);
                        }
                    }
                }
                if (ids.isEmpty()) {
                    ids.addAll(sharedWords(question.getText(), prepared.inside));
                }
                for (String passage : ids) {
                    if (!inChapter.contains(passage)) {
                        inChapter.add(passage);
                    }
                }
                texts.add(question.getText());
                origins.add(Question.Origin.ECKPFEILER);
                topics.add(id);
                candidates.add(ids);
            }
            members.put(id, inChapter);
            groups.add(new Group(id, chapter.getTitle(), inChapter, ""));
        }
        Map<String, EmbeddingPort.EmbeddingVector> vectors = embed(prepared.inside);
        boolean semantic = embeddings != null && !texts.isEmpty();
        List<EmbeddingPort.EmbeddingVector> questionVectors = semantic ? embeddings.embedDocuments(texts)
                : Collections.<EmbeddingPort.EmbeddingVector>nCopies(texts.size(), null);
        List<Integer> kept = new ArrayList<Integer>();
        for (int i = 0; i < texts.size(); i++) {
            int same = -1;
            for (int k : kept) {
                if (texts.get(k).equalsIgnoreCase(texts.get(i)) || semantic
                        && VectorMath.cosine(questionVectors.get(k), questionVectors.get(i)) >= settings.mergeSimilarity) {
                    same = k;
                    break;
                }
            }
            if (same < 0) {
                kept.add(i);
            } else {
                for (String id : candidates.get(i)) {
                    if (!candidates.get(same).contains(id)) {
                        candidates.get(same).add(id);
                    }
                }
            }
        }
        List<EmbeddingPort.EmbeddingVector> demandedVectors = new ArrayList<EmbeddingPort.EmbeddingVector>();
        for (int k : kept) {
            if (origins.get(k).isDemanded()) {
                demandedVectors.add(questionVectors.get(k));
            }
        }
        List<String> nearest = semantic ? nearestGroups(demandedVectors, demandedVectors.size(), members, vectors)
                : null;
        mergeStep.millis = clock.millis() - began;
        log.accept("book run: " + texts.size() + " questions drafted, " + kept.size() + " after merging");

        // 6 every question checked against mission and fence
        final Step checkStep = step(steps, "fragenpruefung");
        began = clock.millis();
        List<String> keptTexts = new ArrayList<String>();
        for (int k : kept) {
            keptTexts.add(texts.get(k));
        }
        report.step(checkStep.getName(), 0, 1);
        List<StockQuestions.Verdict> verdicts = keptTexts.isEmpty()
                ? Collections.<StockQuestions.Verdict>emptyList()
                : new StockQuestions(new Metered(researchChat, checkStep, switches), idea, settings.language,
                settings.questions, log).check(keptTexts);
        report.step(checkStep.getName(), 1, 1);
        List<Question> list = new ArrayList<Question>();
        int demandedIndex = 0;
        for (int j = 0; j < kept.size(); j++) {
            int k = kept.get(j);
            boolean mission = origins.get(k).isDemanded();
            List<String> shared = mission && !semantic ? sharedWords(texts.get(k), prepared.inside) : null;
            Question question = new Question(String.format("F%03d", j + 1), texts.get(k), origins.get(k),
                    !mission ? topics.get(k) : semantic ? nearest.get(demandedIndex)
                            : nearestByWords(texts.get(k), prepared.inside, members),
                    verdicts.get(j));
            if (mission && semantic) {
                question.vector = questionVectors.get(k);
                demandedIndex++;
            } else if (mission) {
                question.candidates.addAll(shared);
            } else {
                question.candidates.addAll(candidates.get(k));
            }
            list.add(question);
        }
        checkStep.millis = clock.millis() - began;
        int pursued = 0;
        for (Question question : list) {
            pursued += question.isPursued() ? 1 : 0;
        }
        log.accept("book run: " + list.size() + " questions (" + demandedVectors.size()
                + " demanded by the mission or the reader), " + pursued + " pursued");
        Opening opening = new Opening(idea, prepared.inside, prepared.setAside, vectors, prepared.profiles, groups,
                list, steps, switches, chosen.getHints());
        opening.title = demanded.getTitle().trim().isEmpty() ? idea.getMission().trim() : demanded.getTitle().trim();
        return opening;
    }

    /**
     * Counts the finished pieces of a step and reports each at once, from whatever thread finished it, so the host sees
     * the progress while the step runs and not only at its end (Angelo 27.11.: „Quellen beschreiben“ stood at 0 of 88
     * for minutes).
     */
    private static Runnable counter(final Progress progress, final String step, final int total) {
        final AtomicInteger done = new AtomicInteger();
        return new Runnable() {
            @Override
            public void run() {
                progress.step(step, Math.min(total, done.incrementAndGet()), total);
            }
        };
    }

    /** The name of the field a cornerstone belongs to on the cards; empty when none. */
    private static String fieldOf(CornerstoneSurvey.Survey survey, TermCandidates.Candidate cornerstone) {
        for (FieldCards.Field field : survey.getFields()) {
            if (field.getKeys().contains(cornerstone.getKey())) {
                return field.getName();
            }
        }
        return "";
    }

    private static Map<String, TermCandidates.Text> texts(Map<String, BasketPassage> byId) {
        Map<String, TermCandidates.Text> out = new LinkedHashMap<String, TermCandidates.Text>();
        for (BasketPassage passage : byId.values()) {
            out.put(passage.passageId, new TermCandidates.Text(passage.passageId, passage.getSourceId(),
                    passage.text));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- write

    /** From the opened basket to the book. */
    public Result write(Opening opening) throws IOException {
        return write(opening, Progress.NONE);
    }

    /** From the opened basket to the book, with the progress of each step. */
    public Result write(Opening opening, Progress progress) throws IOException {
        final Progress progressOf = progress == null ? Progress.NONE : progress;
        List<Step> steps = new ArrayList<Step>(opening.steps);
        Switches switches = opening.switches;
        Map<String, BasketPassage> byId = byId(opening.passages);
        Map<String, Group> groups = new LinkedHashMap<String, Group>();
        for (Group group : opening.groups) {
            groups.put(group.id, group);
        }

        // 7 evidence check per pursued question over the places of its cornerstone
        final Step judgeStep = step(steps, "belegpruefung");
        long began = clock.millis();
        EvidenceBundleJudge judge = new EvidenceBundleJudge(new Metered(researchChat, judgeStep, switches),
                opening.fence, settings.language, settings.judge, log);
        Map<EvidenceJudge.Question, List<EvidenceBundleJudge.Candidate>> work =
                new LinkedHashMap<EvidenceJudge.Question, List<EvidenceBundleJudge.Candidate>>();
        for (Question question : opening.questions) {
            if (!question.isPursued()) {
                continue;
            }
            List<String> ids = question.vector == null ? question.candidates : nearest(question.vector, opening);
            List<EvidenceBundleJudge.Candidate> candidates = new ArrayList<EvidenceBundleJudge.Candidate>();
            for (String id : ids) {
                BasketPassage passage = byId.get(id);
                if (passage == null) {
                    continue;
                }
                candidates.add(new EvidenceBundleJudge.Candidate(new EvidenceJudge.Passage(passage.passageId,
                        passage.getSourceId(), urlOf(passage.source), passage.domain, passage.text),
                        passage.heading, profileLine(opening.profiles.get(passage.getSourceId()))));
            }
            EvidenceJudge.Question asked = new EvidenceJudge.Question(question.id, question.text);
            work.put(asked, judge.fitting(asked, candidates));
        }
        progressOf.step(judgeStep.getName(), 0, work.size());
        List<EvidenceVerdict> verdicts = new ArrayList<EvidenceVerdict>();
        for (EvidenceBundleJudge.Result result : judge.judgeAll(work, Collections.<EvidenceVerdict>emptyList(), 1,
                counter(progressOf, judgeStep.getName(), work.size()), settings.getPieces())) {
            verdicts.addAll(result.getVerdicts());
        }
        progressOf.step(judgeStep.getName(), work.size(), work.size());
        judgeStep.millis = clock.millis() - began;

        // 8 memory and section plan: the chapters as topics, the pursued questions under them
        Step planStep = step(steps, "abschnittsplan");
        began = clock.millis();
        ResearchMemory memory = new ResearchMemory();
        Set<String> withQuestions = new LinkedHashSet<String>();
        for (Question question : opening.questions) {
            if (question.isPursued() && groups.containsKey(question.groupId)) {
                withQuestions.add(question.groupId);
            }
        }
        for (Group group : opening.groups) {
            if (withQuestions.contains(group.id)) {
                memory.addTopic(ResearchTopic.found(group.id, group.title.isEmpty() ? group.id : group.title, "",
                        null, researchChat.modelName(), clock.millis()).withFence(ResearchTopic.Fence.INNEN,
                        "Fragen des Kapitels im Weidezaun", researchChat.modelName(), clock.millis()));
            }
        }
        for (Question question : opening.questions) {
            if (question.isPursued() && withQuestions.contains(question.groupId)) {
                memory.addQuestion(ResearchQuestion.open(question.id, question.text, question.groupId,
                        ResearchQuestion.Origin.BESTAND, clock.millis()));
            }
        }
        for (EvidenceVerdict verdict : verdicts) {
            memory.record(verdict);
        }
        SectionPlan plan = new SectionPlanner(memory).targetWords(settings.targetWords).bookTitle(opening.title)
                .plan(clock.millis());
        planStep.millis = clock.millis() - began;

        // 9 writing in order with the writing memory, bundled sentence check
        Step writeStep = step(steps, "schreiben");
        progressOf.step(writeStep.getName(), 0, plan.getSections().size());
        began = clock.millis();
        List<PassageLookup.Passage> lookup = new ArrayList<PassageLookup.Passage>();
        for (BasketPassage passage : opening.passages) {
            SourceRating profile = opening.profiles.get(passage.getSourceId());
            lookup.add(new PassageLookup.Passage(passage.passageId, passage.getSourceId(), urlOf(passage.source),
                    passage.text, profile == null || !profile.isOk() ? "" : profile.getKind().name(), ""));
        }
        PlanSectionWriter writer = new PlanSectionWriter(new Metered(writerChat, writeStep, switches),
                checkChat == null ? null : new Metered(checkChat, writeStep, switches), PassageLookup.of(lookup),
                new SentenceVerdicts(), new WrittenSections(), settings.writer, clock, log, opening.fence);
        List<WrittenSection> sections = writer.writeAll(plan, null,
                counter(progressOf, writeStep.getName(), plan.getSections().size()));
        progressOf.step(writeStep.getName(), plan.getSections().size(), plan.getSections().size());
        writeStep.millis = clock.millis() - began;

        // 10 report
        List<BookSection> book = new ArrayList<BookSection>();
        for (WrittenSection section : sections) {
            book.add(section.toBookSection());
        }
        Map<String, MissionCheck.SourceInfo> sourceInfo = new LinkedHashMap<String, MissionCheck.SourceInfo>();
        for (SourceRating profile : opening.profiles.values()) {
            if (profile.isOk()) {
                sourceInfo.put(profile.getSourceId(), new MissionCheck.SourceInfo(profile.getKind().name(),
                        profile.getJurisdiction().name()));
            }
        }
        MissionReport report = new MissionCheck(memory).sections(book).sentences(writer.sentenceLookup())
                .placements(plan.getPlacements()).sources(sourceInfo)
                .requested(settings.targetWords, settings.targetWords).report(clock.millis());
        List<QuestionLine> lines = questionLines(opening.questions, memory, plan, sections, verdicts);
        log.accept("book run: " + sections.size() + " sections, " + switches.count + " model switches");
        return new Result(opening, verdicts, plan, sections, report, lines, steps,
                citedOutside(sections, opening.profiles));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static Step step(List<Step> steps, String name) {
        Step step = new Step(name);
        steps.add(step);
        return step;
    }

    /**
     * Why a passage lies outside the fence by its source's profile: the whole source outside, or a part of a mixed
     * source the profile names by its heading (the heading itself or a heading below it); {@code ""} when it stays.
     */
    static String outside(BasketPassage passage, SourceRating profile) {
        if (profile == null || !profile.isOk() || !profile.getFit().isAsked()) {
            return "";
        }
        SourceRating.Fit fit = profile.getFit();
        if (fit.getRelation() == SourceRating.Relation.AUSSEN) {
            return "Quelle außerhalb des Weidezauns" + (fit.getSubject().isEmpty() ? "" : ": " + fit.getSubject());
        }
        if (fit.getRelation() == SourceRating.Relation.GEMISCHT) {
            String heading = passage.heading.trim();
            for (String part : fit.getOutsideParts()) {
                String p = part.trim();
                if (!p.isEmpty() && (heading.equals(p) || heading.startsWith(p + " >"))) {
                    return "Teil außerhalb des Weidezauns: " + p;
                }
            }
        }
        return "";
    }

    /**
     * Without an embedding model: the passages that share words of five letters or more with the question (cut
     * endings), most shared words first (ties in reading order).
     */
    static List<String> sharedWords(String question, List<BasketPassage> passages) {
        final Map<String, Integer> shared = sharedCounts(question, passages);
        List<String> ids = new ArrayList<String>(shared.keySet());
        Collections.sort(ids, (a, b) -> Integer.compare(shared.get(b), shared.get(a)));
        return ids;
    }

    /** Per passage that shares a word with the question, how many (in reading order). */
    private static Map<String, Integer> sharedCounts(String question, List<BasketPassage> passages) {
        Set<String> asked = words(question);
        Map<String, Integer> shared = new LinkedHashMap<String, Integer>();
        for (BasketPassage passage : passages) {
            Set<String> in = words(passage.heading + " " + passage.text);
            in.retainAll(asked);
            if (!in.isEmpty()) {
                shared.put(passage.passageId, in.size());
            }
        }
        return shared;
    }

    /**
     * Without vectors, the chapter whose passages share the most words with the question on average: the counterpart
     * of the nearest mean vector, so a large chapter does not draw every question by its size alone; {@code ""} when
     * no chapter shares a word.
     */
    static String nearestByWords(String question, List<BasketPassage> passages, Map<String, List<String>> members) {
        Map<String, Integer> shared = sharedCounts(question, passages);
        String best = "";
        double most = 0;
        for (Map.Entry<String, List<String>> topic : members.entrySet()) {
            if (topic.getValue().isEmpty()) {
                continue;
            }
            int sum = 0;
            for (String id : topic.getValue()) {
                Integer n = shared.get(id);
                sum += n == null ? 0 : n;
            }
            double mean = sum / (double) topic.getValue().size();
            if (mean > most) {
                most = mean;
                best = topic.getKey();
            }
        }
        return best;
    }

    private static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<String>();
        for (String word : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() >= 5) {
                out.add(TermCandidates.stem(word));
            }
        }
        return out;
    }

    /** The vectors of the passages, heading path and text as one document each; none without an embedding model. */
    private Map<String, EmbeddingPort.EmbeddingVector> embed(List<BasketPassage> passages) {
        Map<String, EmbeddingPort.EmbeddingVector> out = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        if (passages.isEmpty() || embeddings == null) {
            return out;
        }
        List<String> texts = new ArrayList<String>();
        for (BasketPassage passage : passages) {
            texts.add(passage.heading.isEmpty() ? passage.text : passage.heading + "\n" + passage.text);
        }
        List<EmbeddingPort.EmbeddingVector> embedded = embeddings.embedDocuments(texts);
        for (int i = 0; i < passages.size(); i++) {
            out.put(passages.get(i).passageId, embedded.get(i));
        }
        return out;
    }

    /** Per question the group whose mean vector lies nearest to it; {@code ""} without groups. */
    private static List<String> nearestGroups(List<EmbeddingPort.EmbeddingVector> questions, int count,
                                              Map<String, List<String>> members,
                                              Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        List<String> out = new ArrayList<String>();
        if (members.isEmpty() || questions.isEmpty()) {
            for (int i = 0; i < count; i++) {
                out.add("");
            }
            return out;
        }
        Map<String, EmbeddingPort.EmbeddingVector> centres = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (Map.Entry<String, List<String>> group : members.entrySet()) {
            List<EmbeddingPort.EmbeddingVector> list = new ArrayList<EmbeddingPort.EmbeddingVector>();
            for (String id : group.getValue()) {
                list.add(vectors.get(id));
            }
            centres.put(group.getKey(), VectorMath.mean(list));
        }
        for (EmbeddingPort.EmbeddingVector question : questions) {
            String best = "";
            double bestSimilarity = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, EmbeddingPort.EmbeddingVector> centre : centres.entrySet()) {
                double similarity = VectorMath.cosine(question, centre.getValue());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = centre.getKey();
                }
            }
            out.add(best);
        }
        return out;
    }

    /** The passages of the opening, nearest to the vector first (ties in reading order). */
    private static List<String> nearest(final EmbeddingPort.EmbeddingVector vector, Opening opening) {
        final Map<String, Double> similarity = new LinkedHashMap<String, Double>();
        for (BasketPassage passage : opening.passages) {
            similarity.put(passage.passageId, VectorMath.cosine(vector, opening.vectors.get(passage.passageId)));
        }
        List<String> ids = new ArrayList<String>(similarity.keySet());
        Collections.sort(ids, (a, b) -> Double.compare(similarity.get(b), similarity.get(a)));
        return ids;
    }

    private static Map<String, BasketPassage> byId(List<BasketPassage> passages) {
        Map<String, BasketPassage> out = new LinkedHashMap<String, BasketPassage>();
        for (BasketPassage passage : passages) {
            out.put(passage.passageId, passage);
        }
        return out;
    }

    /** The profile of a source in one line for the evidence check; {@code ""} without a usable profile. */
    static String profileLine(SourceRating profile) {
        if (profile == null || !profile.isOk()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        if (!profile.getPublisher().isEmpty()) {
            parts.add(profile.getPublisher());
        }
        parts.add(profile.getKind().name());
        parts.add(profile.getJurisdiction().name());
        if (!profile.getDate().isEmpty()) {
            parts.add(profile.getDate());
        }
        SourceRating.Fit fit = profile.getFit();
        if (fit.isAsked()) {
            if (!fit.getSubject().isEmpty()) {
                parts.add("Gegenstand: " + fit.getSubject());
            }
            parts.add("Bezug zum Weidezaun: " + fit.getRelation());
            if (!fit.getOutsideParts().isEmpty()) {
                parts.add("außerhalb: " + String.join("; ", fit.getOutsideParts()));
            }
        }
        return String.join(" · ", parts);
    }

    /** Every question with where it is answered or why it stays open or outside. */
    static List<QuestionLine> questionLines(List<Question> questions, ResearchMemory memory, SectionPlan plan,
                                            List<WrittenSection> sections, List<EvidenceVerdict> verdicts) {
        Map<String, WrittenSection> written = new LinkedHashMap<String, WrittenSection>();
        for (WrittenSection section : sections) {
            written.put(section.getSectionId(), section);
        }
        List<QuestionLine> out = new ArrayList<QuestionLine>();
        for (Question question : questions) {
            if (!question.isPursued()) {
                out.add(new QuestionLine(question, QuestionLine.State.AUSSERHALB, "", "außerhalb des Weidezauns: "
                        + question.verdict.getReason(), 0, 0));
                continue;
            }
            int notApplicable = 0;
            int unclear = 0;
            for (EvidenceVerdict verdict : verdicts) {
                if (verdict.getQuestionId().equals(question.id)) {
                    notApplicable += verdict.getReason().startsWith("nicht anwendbar")
                            || verdict.getReason().startsWith("does not apply") ? 1 : 0;
                    unclear += verdict.getReason().startsWith("Anwendbarkeit unklar")
                            || verdict.getReason().startsWith("applicability unclear") ? 1 : 0;
                }
            }
            QuestionEvidence evidence = memory.evidenceOf(question.id);
            int direct = evidence == null ? 0 : evidence.getDirectCount();
            int leads = evidence == null ? 0 : evidence.getLeadCount();
            Set<String> directIds = new LinkedHashSet<String>();
            if (evidence != null) {
                for (EvidenceVerdict verdict : evidence.getDirect()) {
                    directIds.add(verdict.getPassageId());
                }
            }
            SectionPlan.Section planned = null;
            for (SectionPlan.Section section : plan.getSections()) {
                for (SectionPlan.Question asked : section.getQuestions()) {
                    if (asked.getId().equals(question.id)) {
                        planned = section;
                    }
                }
            }
            WrittenSection done = planned == null ? null : written.get(planned.getId());
            boolean cited = false;
            if (done != null && done.isOk()) {
                for (WrittenSection.Citation citation : done.getCitations().values()) {
                    cited |= directIds.contains(citation.getPassageId());
                }
            }
            QuestionLine.State state;
            String reason;
            if (cited) {
                state = QuestionLine.State.BEANTWORTET;
                reason = "";
            } else if (direct == 0 && question.origin.isDemanded()) {
                state = QuestionLine.State.NACHRECHERCHE;
                reason = (question.origin == Question.Origin.LESER ? "vom Leser gefragt" : "vom Auftrag verlangt")
                        + ", aber kein direkter Beleg im Korb"
                        + (leads > 0 ? " (nur weiterführende Hinweise)" : "") + ": Nachrecherche oder ehrliche Lücke";
            } else if (direct == 0 && leads > 0) {
                state = QuestionLine.State.NUR_HINWEISE;
                reason = "nur weiterführende Hinweise, kein direkter Beleg im Korb";
            } else {
                state = QuestionLine.State.OFFEN;
                reason = direct == 0 ? (unclear > 0 ? "Belege nur mit unklarer Anwendbarkeit"
                        : notApplicable > 0 ? "Fundstellen gelten außerhalb des Weidezauns"
                        : "kein Beleg im Korb")
                        : planned == null ? "nicht im Abschnittsplan"
                        : done == null || !done.isOk() ? "Abschnitt nicht geschrieben"
                        : "Abschnitt zitiert keinen direkten Beleg der Frage";
            }
            out.add(new QuestionLine(question, state, cited ? planned.getTitle() : "", reason, direct, leads));
        }
        return out;
    }

    private static int citedOutside(List<WrittenSection> sections, Map<String, SourceRating> profiles) {
        Set<String> outside = new LinkedHashSet<String>();
        for (WrittenSection section : sections) {
            for (WrittenSection.Citation citation : section.getCitations().values()) {
                SourceRating profile = profiles.get(citation.getSourceId());
                if (profile != null && profile.getFit().getRelation() == SourceRating.Relation.AUSSEN) {
                    outside.add(citation.getPassageId());
                }
            }
        }
        return outside.size();
    }

    /** The model switches of the whole run, by the model label of each answer. */
    static final class Switches {
        private String last = "";
        int count;

        synchronized void saw(String label) {
            if (label == null || label.isEmpty()) {
                return;
            }
            if (!last.isEmpty() && !last.equals(label)) {
                count++;
            }
            last = label;
        }
    }

    /** A chat that counts its calls into a step; it passes everything through unchanged. */
    static final class Metered implements MainModelChat {
        private final MainModelChat chat;
        private final Step step;
        private final Switches switches;

        Metered(MainModelChat chat, Step step, Switches switches) {
            this.chat = chat;
            this.step = step;
            this.switches = switches;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return count(chat.complete(messages, temperature, maxOutputTokens));
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            return count(chat.completeJson(messages, temperature, maxOutputTokens, schemaJson));
        }

        @Override
        public String modelName() {
            return chat.modelName();
        }

        @Override
        public int contextTokens() {
            return chat.contextTokens();
        }

        private MainModelChatResult count(MainModelChatResult result) {
            step.calls.incrementAndGet();
            if (result == null || !result.isOk()) {
                step.failures.incrementAndGet();
            }
            ModelCallMetrics metrics = result == null ? null : result.getMetrics();
            if (metrics != null) {
                step.promptTokens.addAndGet(metrics.getPromptEvalCount());
                step.answerTokens.addAndGet(metrics.getEvalCount());
                step.modelMillis.addAndGet(metrics.getDurationMillis());
            }
            switches.saw(metrics == null || metrics.getModelLabel().isEmpty() ? chat.modelName()
                    : metrics.getModelLabel());
            return result;
        }
    }
}
