package com.aresstack.writing4j.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the section writer gets for one section (plan PR 28): the section of the approved outline with its brief and
 * key points, the evidence pack of PR 27 (numbered passages, book synopsis, previous-section digest, glossary), the
 * book language of the Phase-1 record and the writer settings of the Document row (word target and answer budget
 * per call). A section written in several key-point parts carries a pack for all of them and the number of passages
 * one call gets ({@link #getPassagesPerCall()}). Immutable; build it with {@link #builder(String)}.
 */
public final class SectionAssignment {

    /** The smallest word target of one call; a lower setting would ask the model for a sentence or two. */
    public static final int MIN_WORDS_PER_CALL = 50;

    private final String sectionId;
    private final String title;
    private final String brief;
    private final List<String> keyPoints;
    private final List<EvidencePassage> passages;
    private final String synopsis;
    private final String previousDigest;
    private final List<String> glossary;
    private final String fence;
    private final String bookLanguage;
    private final int sectionWords;
    private final int wordsPerCall;
    private final int answerTokens;
    private final String rewriteComment;
    private final String currentText;
    private final ClaimCarryCheck.Criteria carry;
    private final int passagesPerCall;
    private final List<String> writtenSections;
    private final RepeatCheck.Criteria repeats;
    private final SynthesisCriteria synthesis;
    private final CitationFormat citationFormat;
    private final TopUpCriteria topUp;
    private final List<List<Integer>> keyPointPassages;
    private final Map<String, String> sourceNames;
    private final PartContextCriteria partContextCriteria;
    private final PartContext partContext;

    private SectionAssignment(Builder b) {
        this.sectionId = b.sectionId;
        this.title = b.title;
        this.brief = b.brief;
        this.keyPoints = Collections.unmodifiableList(new ArrayList<String>(b.keyPoints));
        this.passages = Collections.unmodifiableList(new ArrayList<EvidencePassage>(b.passages));
        this.synopsis = b.synopsis;
        this.previousDigest = b.previousDigest;
        this.glossary = Collections.unmodifiableList(new ArrayList<String>(b.glossary));
        this.fence = b.fence;
        this.bookLanguage = b.bookLanguage;
        this.wordsPerCall = Math.max(MIN_WORDS_PER_CALL, b.wordsPerCall);
        this.sectionWords = b.sectionWords > 0 ? b.sectionWords : this.wordsPerCall;
        this.answerTokens = Math.max(0, b.answerTokens);
        this.rewriteComment = b.rewriteComment;
        this.currentText = b.currentText;
        this.carry = b.carry;
        this.passagesPerCall = Math.max(0, b.passagesPerCall);
        this.writtenSections = Collections.unmodifiableList(new ArrayList<String>(b.writtenSections));
        this.repeats = b.repeats;
        this.synthesis = b.synthesis;
        this.citationFormat = b.citationFormat;
        this.topUp = b.topUp;
        this.keyPointPassages = bound(b.keyPointPassages, keyPoints.size(), passages);
        this.sourceNames = Collections.unmodifiableMap(names(b.sourceNames));
        this.partContextCriteria = b.partContextCriteria;
        this.partContext = b.partContext;
    }

    /** The names as given, blank ones left out. */
    private static Map<String, String> names(Map<String, String> given) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (given != null) {
            for (Map.Entry<String, String> name : given.entrySet()) {
                if (name.getKey() != null && name.getValue() != null && !name.getValue().trim().isEmpty()) {
                    out.put(name.getKey(), name.getValue().trim());
                }
            }
        }
        return out;
    }

    /** The binding as given, checked against the key points and the pack; empty for none. */
    private static List<List<Integer>> bound(List<List<Integer>> binding, int keyPoints,
                                             List<EvidencePassage> passages) {
        if (binding == null || binding.isEmpty()) {
            return Collections.emptyList();
        }
        if (binding.size() != keyPoints) {
            throw new IllegalArgumentException("a binding needs one list of passages per key point: " + binding.size()
                    + " lists for " + keyPoints + " key points");
        }
        java.util.Set<Integer> numbers = new java.util.HashSet<Integer>();
        for (EvidencePassage passage : passages) {
            numbers.add(passage.getNumber());
        }
        List<List<Integer>> out = new ArrayList<List<Integer>>();
        for (List<Integer> list : binding) {
            List<Integer> copy = new ArrayList<Integer>();
            for (Integer n : list == null ? Collections.<Integer>emptyList() : list) {
                if (n == null || !numbers.contains(n)) {
                    throw new IllegalArgumentException("the binding names passage " + n + ", which the pack lacks");
                }
                if (!copy.contains(n)) {
                    copy.add(n);
                }
            }
            out.add(Collections.unmodifiableList(copy));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * What one part call of a section knows about its section ({@link PartContextCriteria}): its place among the
     * parts, the plan line of every key point of the section and the text of the parts before it. Immutable.
     */
    static final class PartContext {
        final int index;
        final int count;
        final List<String> planLines;
        final String soFar;

        /**
         * @param index     this part, from 0
         * @param count     parts of the section
         * @param planLines one line per key point of the section, in outline order, with its state
         * @param soFar     the checked text of the parts before this one, markers left out
         */
        PartContext(int index, int count, List<String> planLines, String soFar) {
            this.index = index;
            this.count = count;
            this.planLines = Collections.unmodifiableList(new ArrayList<String>(planLines));
            this.soFar = soFar == null ? "" : soFar.trim();
        }

        boolean isLast() {
            return index == count - 1;
        }
    }

    /**
     * How many key-point parts, one writer call each, a section is written in: its words over the words per call,
     * at least one and at most one per key point. {@code EvidenceAssignments} sizes the evidence pack with it.
     *
     * @param sectionWords words the section aims for (0 = one call's target)
     * @param wordsPerCall the word target of one call (at least {@link #MIN_WORDS_PER_CALL})
     * @param keyPoints    the number of key points
     */
    public static int partCount(int sectionWords, int wordsPerCall, int keyPoints) {
        int per = Math.max(MIN_WORDS_PER_CALL, wordsPerCall);
        int words = sectionWords > 0 ? sectionWords : per;
        return Math.max(1, Math.min((words + per - 1) / per, keyPoints));
    }

    public static Builder builder(String sectionId) {
        return new Builder(sectionId);
    }

    public String getSectionId() {
        return sectionId;
    }

    public String getTitle() {
        return title;
    }

    public String getBrief() {
        return brief;
    }

    public List<String> getKeyPoints() {
        return keyPoints;
    }

    public List<EvidencePassage> getPassages() {
        return passages;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public String getPreviousDigest() {
        return previousDigest;
    }

    public List<String> getGlossary() {
        return glossary;
    }

    /** Mission and Weidezaun of the book as the prompt shows them; {@code ""} when the book has none. */
    public String getFence() {
        return fence;
    }

    public String getBookLanguage() {
        return bookLanguage;
    }

    /** Words the whole section aims for; more than {@link #getWordsPerCall()} splits it by key points. */
    public int getSectionWords() {
        return sectionWords;
    }

    /** The word target of one writer call (writer settings, D8). */
    public int getWordsPerCall() {
        return wordsPerCall;
    }

    /** The answer budget of one writer call in tokens (writer settings, D8). */
    public int getAnswerTokens() {
        return answerTokens;
    }

    /** The user's comment of "Rewrite section" or "Rewrite chapter"; empty for a first draft. */
    public String getRewriteComment() {
        return rewriteComment;
    }

    /** The current text of the part being rewritten; empty for a first draft. Never sent with a continuation. */
    public String getCurrentText() {
        return currentText;
    }

    /**
     * For each key point, in the order of the key points, the numbers of the pack's passages bound to it (component
     * 10: the section plan says which passages answer which question, direct evidence first); empty when the
     * passages are not bound. A bound section's parts take the passages of their own key points, one of each key point
     * in turn up to {@link #getPassagesPerCall()}, also when the section is written in one part; an unbound one's
     * parts take the passages that cover their key points best.
     */
    public List<List<Integer>> getKeyPointPassages() {
        return keyPointPassages;
    }

    /** Whether the passages are bound to the key points ({@link #getKeyPointPassages()}). */
    public boolean isBound() {
        return !keyPointPassages.isEmpty();
    }

    /**
     * The name the prompt gives each source, by source id (component 10: its site's own name without its ending, e.g.
     * "Sturzwacht" for sturzwacht.example, or its host where two sites share one); a source without a name gets a
     * letter. c10 wrote "Quelle C" 19 times: a letter in the prompt ends up in the text, a name a reader understands.
     */
    public Map<String, String> getSourceNames() {
        return sourceNames;
    }

    /** True for "Rewrite section" or "Rewrite chapter" (a comment or a current text is set). */
    /** The thresholds of claim-check stage 2 for this section. */
    public ClaimCarryCheck.Criteria getCarry() {
        return carry;
    }

    /**
     * How many passages of the pack one writer call gets; 0 = every passage. A section written in parts gives
     * each part the passages that fit its key points best (live run 4: three parts of one section saw the same
     * eight passages and repeated each other).
     */
    public int getPassagesPerCall() {
        return passagesPerCall;
    }

    /**
     * The current text of every other written section of the manuscript: what the book already says. A sentence of
     * this section that repeats one of them, word for word or nearly ({@link #getRepeats()}), leaves the text (live
     * run 4, book 6: the same sentences stood in sections 1.1, 1.2 and 1.3).
     */
    public List<String> getWrittenSections() {
        return writtenSections;
    }

    /** The near-repeat bounds of {@link RepeatCheck} for this section (settings). */
    public RepeatCheck.Criteria getRepeats() {
        return repeats;
    }

    /** How many paragraphs of each part combine how many sources ({@link SynthesisCriteria}, settings). */
    /** How the writer sees and cites its passages ({@link CitationFormat#NUMBERED} by default). */
    public CitationFormat getCitationFormat() {
        return citationFormat;
    }

    /** When a part gets a top-up call because it stayed below its word target ({@link TopUpCriteria}). */
    public TopUpCriteria getTopUp() {
        return topUp;
    }

    public SynthesisCriteria getSynthesis() {
        return synthesis;
    }

    /** Whether the parts of this section know their section ({@link PartContextCriteria}); off by default. */
    public PartContextCriteria getPartContextCriteria() {
        return partContextCriteria;
    }

    /** The part's place in its section; {@code null} for a whole section and while the part context is off. */
    PartContext getPartContext() {
        return partContext;
    }

    /** A copy of this part with its place in the section. */
    SectionAssignment inSection(PartContext context) {
        Builder b = toBuilder();
        b.partContext = context;
        return new SectionAssignment(b);
    }

    public boolean isRewrite() {
        return !rewriteComment.isEmpty() || !currentText.isEmpty();
    }

    /** A builder holding every value of this assignment, for a copy with some of them changed. */
    public Builder toBuilder() {
        Builder b = new Builder(sectionId);
        b.title = title;
        b.brief = brief;
        b.keyPoints = keyPoints;
        b.passages = passages;
        b.synopsis = synopsis;
        b.previousDigest = previousDigest;
        b.glossary = glossary;
        b.fence = fence;
        b.bookLanguage = bookLanguage;
        b.sectionWords = sectionWords;
        b.wordsPerCall = wordsPerCall;
        b.answerTokens = answerTokens;
        b.rewriteComment = rewriteComment;
        b.currentText = currentText;
        b.carry = carry;
        b.passagesPerCall = passagesPerCall;
        b.writtenSections = writtenSections;
        b.repeats = repeats;
        b.synthesis = synthesis;
        b.citationFormat = citationFormat;
        b.topUp = topUp;
        b.keyPointPassages = keyPointPassages;
        b.sourceNames = sourceNames;
        b.partContextCriteria = partContextCriteria;
        b.partContext = partContext;
        return b;
    }

    /** A copy for one key-point part of the section, with that part's key points, words and digest. */
    SectionAssignment part(List<String> partKeyPoints, int words, String digest) {
        return part(partKeyPoints, passages, words, digest);
    }

    /** A copy for one key-point part with that part's passages; the part's calls get all of them. */
    SectionAssignment part(List<String> partKeyPoints, List<EvidencePassage> partPassages, int words, String digest) {
        return part(partKeyPoints, partPassages, words, digest, currentText);
    }

    /** As above, with the part's share of a rewrite's current text (empty: the part is written as a first draft). */
    SectionAssignment part(List<String> partKeyPoints, List<EvidencePassage> partPassages, int words, String digest,
                           String partText) {
        Builder b = new Builder(sectionId);
        b.title = title;
        b.brief = brief;
        b.keyPoints = partKeyPoints;
        b.passages = partPassages;
        b.synopsis = synopsis;
        b.previousDigest = digest;
        b.glossary = glossary;
        b.fence = fence;
        b.bookLanguage = bookLanguage;
        b.wordsPerCall = words;
        b.sectionWords = words;
        b.answerTokens = answerTokens;
        b.rewriteComment = rewriteComment;
        b.currentText = partText == null ? "" : partText.trim();
        b.carry = carry;
        b.writtenSections = writtenSections;
        b.repeats = repeats;
        b.synthesis = synthesis;
        b.citationFormat = citationFormat;
        b.topUp = topUp;
        b.sourceNames = sourceNames;
        b.partContextCriteria = partContextCriteria;
        return new SectionAssignment(b);
    }

    public static final class Builder {
        private final String sectionId;
        private String title = "";
        private String brief = "";
        private List<String> keyPoints = new ArrayList<String>();
        private List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
        private String synopsis = "";
        private String previousDigest = "";
        private List<String> glossary = new ArrayList<String>();
        private String fence = "";
        private String bookLanguage = "English";
        private int sectionWords;
        private int wordsPerCall = 450;
        private int answerTokens = 1200;
        private String rewriteComment = "";
        private String currentText = "";
        private ClaimCarryCheck.Criteria carry = ClaimCarryCheck.Criteria.defaults();
        private int passagesPerCall;
        private List<String> writtenSections = new ArrayList<String>();
        private RepeatCheck.Criteria repeats = RepeatCheck.Criteria.defaults();
        private SynthesisCriteria synthesis = SynthesisCriteria.defaults();
        private CitationFormat citationFormat = CitationFormat.NUMBERED;
        private TopUpCriteria topUp = TopUpCriteria.off();
        private List<List<Integer>> keyPointPassages = new ArrayList<List<Integer>>();
        private Map<String, String> sourceNames = new LinkedHashMap<String, String>();
        private PartContextCriteria partContextCriteria = PartContextCriteria.off();
        private PartContext partContext;

        private Builder(String sectionId) {
            if (sectionId == null || sectionId.trim().isEmpty()) {
                throw new IllegalArgumentException("sectionId must not be blank");
            }
            this.sectionId = sectionId.trim();
        }

        /** The writer model's citation format; {@link CitationFormat#NUMBERED} when {@code null}. */
        public Builder citationFormat(CitationFormat value) {
            this.citationFormat = value == null ? CitationFormat.NUMBERED : value;
            return this;
        }

        public Builder title(String value) {
            this.title = value == null ? "" : value.trim();
            return this;
        }

        public Builder brief(String value) {
            this.brief = value == null ? "" : value.trim();
            return this;
        }

        public Builder keyPoints(List<String> value) {
            this.keyPoints = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
            return this;
        }

        public Builder passages(List<EvidencePassage> value) {
            this.passages = value == null ? new ArrayList<EvidencePassage>() : new ArrayList<EvidencePassage>(value);
            return this;
        }

        public Builder synopsis(String value) {
            this.synopsis = value == null ? "" : value.trim();
            return this;
        }

        public Builder previousDigest(String value) {
            this.previousDigest = value == null ? "" : value.trim();
            return this;
        }

        public Builder glossary(List<String> value) {
            this.glossary = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
            return this;
        }

        /** Mission and Weidezaun as described text (inclusions and exclusions); {@code null} or blank: none. */
        public Builder fence(String value) {
            this.fence = value == null ? "" : value.trim();
            return this;
        }

        public Builder bookLanguage(String value) {
            this.bookLanguage = value == null || value.trim().isEmpty() ? "English" : value.trim();
            return this;
        }

        public Builder sectionWords(int value) {
            this.sectionWords = value;
            return this;
        }

        public Builder wordsPerCall(int value) {
            this.wordsPerCall = value;
            return this;
        }

        public Builder answerTokens(int value) {
            this.answerTokens = value;
            return this;
        }

        public Builder rewriteComment(String value) {
            this.rewriteComment = value == null ? "" : value.trim();
            return this;
        }

        public Builder currentText(String value) {
            this.currentText = value == null ? "" : value.trim();
            return this;
        }

        /** Claim-check stage 2 thresholds (settings); the defaults when {@code null}. */
        public Builder carry(ClaimCarryCheck.Criteria value) {
            this.carry = value == null ? ClaimCarryCheck.Criteria.defaults() : value;
            return this;
        }

        /** Passages one writer call gets (the pack size setting); 0 = every passage of the pack. */
        public Builder passagesPerCall(int value) {
            this.passagesPerCall = value;
            return this;
        }

        /** The current text of every other written section of the manuscript ({@link #getWrittenSections()}). */
        public Builder writtenSections(List<String> value) {
            List<String> texts = new ArrayList<String>();
            if (value != null) {
                for (String text : value) {
                    if (text != null && !text.trim().isEmpty()) {
                        texts.add(text);
                    }
                }
            }
            this.writtenSections = texts;
            return this;
        }

        /** Near-repeat bounds (settings); the defaults when {@code null}. */
        public Builder repeats(RepeatCheck.Criteria value) {
            this.repeats = value == null ? RepeatCheck.Criteria.defaults() : value;
            return this;
        }

        /** Synthesis bounds (settings); the defaults when {@code null}. */
        public Builder synthesis(SynthesisCriteria value) {
            this.synthesis = value == null ? SynthesisCriteria.defaults() : value;
            return this;
        }

        /** Top-up bounds (settings); none when {@code null} or not set. */
        public Builder topUp(TopUpCriteria value) {
            this.topUp = value == null ? TopUpCriteria.off() : value;
            return this;
        }

        /**
         * Binds the pack's passages to the key points ({@link SectionAssignment#getKeyPointPassages()}): one list of
         * passage numbers per key point, in the order of the key points; {@code null} or empty for none.
         */
        public Builder keyPointPassages(List<List<Integer>> value) {
            this.keyPointPassages = value == null ? new ArrayList<List<Integer>>() : value;
            return this;
        }

        /**
         * The name the prompt gives each source, by source id ({@link SectionAssignment#getSourceNames()}); a source
         * left out, or {@code null}, gets a letter.
         */
        public Builder sourceNames(Map<String, String> value) {
            this.sourceNames = value == null ? new LinkedHashMap<String, String>() : value;
            return this;
        }

        /** Whether the parts know their section (settings); off when {@code null} or not set. */
        public Builder partContext(PartContextCriteria value) {
            this.partContextCriteria = value == null ? PartContextCriteria.off() : value;
            return this;
        }

        public SectionAssignment build() {
            return new SectionAssignment(this);
        }
    }
}
