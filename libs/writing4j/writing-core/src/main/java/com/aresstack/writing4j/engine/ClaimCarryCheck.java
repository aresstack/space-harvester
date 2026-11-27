package com.aresstack.writing4j.engine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Stage 2 of the claim check (live run 3, fix 3): a cited passage must carry the sentence, not merely exist. Stage 1
 * ({@link ClaimSupportCheck}) proves that numbers and quotations occur in the cited passages; this stage asks whether
 * the content of the sentence does. A sentence is carried when at least one passage it cites
 * <ol>
 * <li>is prose ({@link ProsePassageFilter}): a menu, footer or heading carries nothing, and</li>
 * <li>covers at least {@link Criteria#getMinOverlap() min overlap} of the sentence's content words. A content word has
 * at least {@link Criteria#getMinWordLength() min length} letters; it is covered when a passage word shares its first
 * {@link Criteria#getStemLength() stem length} letters (German inflection and compounds: "erhöht" ~ "Erhöhung",
 * "Echtzeitüberwachung" ~ "Echtzeit").</li>
 * </ol>
 * A sentence that cites nothing itself is measured against the citations of its paragraph, as in stage 1. A miss is
 * an {@link ClaimFlag.Kind#UNCARRIED_CLAIM uncarried claim} flag; nothing is removed. Deterministic, no model call;
 * every bound is a setting.
 */
public final class ClaimCarryCheck {

    /** Thresholds; immutable. */
    public static final class Criteria {
        /** Default: a word of fewer letters ("und", "der", "ist") says nothing about the claim. */
        public static final int DEFAULT_MIN_WORD_LENGTH = 4;
        /** Default stem: five letters join inflected and compound forms of one German word. */
        public static final int DEFAULT_STEM_LENGTH = 5;
        /**
         * Default share of content words a cited passage must cover. The lowest share among the correctly cited
         * sentences of live run 3 is above it, every sentence of section 1.3 (cited to a footer) is below it.
         */
        public static final double DEFAULT_MIN_OVERLAP = 0.3;
        /**
         * Default cosine (sentence-embedding slot) between the sentence and its best sentence of a cited prose passage
         * from which the passage carries it although the words differ (the writer paraphrased, translated or
         * inflected beyond the stem rule); 0 turns the semantic rule off. Not yet calibrated on a live run.
         */
        public static final double DEFAULT_MIN_SIMILARITY = 0.85;

        private final int minWordLength;
        private final int stemLength;
        private final double minOverlap;
        private final ProsePassageFilter.Criteria prose;
        private final double minSimilarity;

        public Criteria(int minWordLength, int stemLength, double minOverlap, ProsePassageFilter.Criteria prose) {
            this(minWordLength, stemLength, minOverlap, prose, DEFAULT_MIN_SIMILARITY);
        }

        public Criteria(int minWordLength, int stemLength, double minOverlap, ProsePassageFilter.Criteria prose,
                        double minSimilarity) {
            if (minWordLength < 1 || stemLength < 1 || minOverlap < 0 || minOverlap > 1 || minSimilarity < 0
                    || minSimilarity > 1) {
                throw new IllegalArgumentException("lengths must be >= 1, the overlap and similarity within 0..1");
            }
            this.minWordLength = minWordLength;
            this.stemLength = stemLength;
            this.minOverlap = minOverlap;
            this.prose = prose == null ? ProsePassageFilter.Criteria.defaults() : prose;
            this.minSimilarity = minSimilarity;
        }

        public static Criteria defaults() {
            return new Criteria(DEFAULT_MIN_WORD_LENGTH, DEFAULT_STEM_LENGTH, DEFAULT_MIN_OVERLAP,
                    ProsePassageFilter.Criteria.defaults(), DEFAULT_MIN_SIMILARITY);
        }

        /** The semantic carry bound; 0 = lexical only. */
        public double getMinSimilarity() {
            return minSimilarity;
        }

        public int getMinWordLength() {
            return minWordLength;
        }

        public int getStemLength() {
            return stemLength;
        }

        public double getMinOverlap() {
            return minOverlap;
        }

        public ProsePassageFilter.Criteria getProse() {
            return prose;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Criteria)) {
                return false;
            }
            Criteria c = (Criteria) o;
            return c.minWordLength == minWordLength && c.stemLength == stemLength
                    && Double.compare(c.minOverlap, minOverlap) == 0 && c.prose.equals(prose)
                    && Double.compare(c.minSimilarity, minSimilarity) == 0;
        }

        @Override
        public int hashCode() {
            return ((minWordLength * 31 + stemLength) * 31 + Double.valueOf(minOverlap).hashCode()) * 31
                    + prose.hashCode() + Double.valueOf(minSimilarity).hashCode();
        }
    }

    private final Criteria criteria;
    private final ProsePassageFilter prose;
    private final SentenceSimilarity similarity;

    public ClaimCarryCheck(Criteria criteria) {
        this(criteria, SentenceSimilarity.none());
    }

    /**
     * @param similarity the sentence-embedding slot: a prose passage whose best sentence reaches
     *                   {@link Criteria#getMinSimilarity()} carries the sentence even below the word overlap
     */
    public ClaimCarryCheck(Criteria criteria, SentenceSimilarity similarity) {
        this.criteria = criteria == null ? Criteria.defaults() : criteria;
        this.prose = new ProsePassageFilter(this.criteria.getProse());
        this.similarity = similarity == null ? SentenceSimilarity.none() : similarity;
    }

    /** The uncarried-claim flags of a validated answer, in text order. */
    public List<ClaimFlag> check(CitationValidator.Result result, List<EvidencePassage> passages) {
        Map<Integer, EvidencePassage> byNumber = new HashMap<Integer, EvidencePassage>();
        for (EvidencePassage passage : passages) {
            byNumber.put(passage.getNumber(), passage);
        }
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        List<CitationValidator.Paragraph> paragraphs = result.getParagraphs();
        for (int p = 0; p < paragraphs.size(); p++) {
            CitationValidator.Paragraph paragraph = paragraphs.get(p);
            for (CitationValidator.Sentence sentence : paragraph.getSentences()) {
                Set<Integer> cited = sentence.getCited().isEmpty() ? paragraph.getCited() : sentence.getCited();
                if (cited.isEmpty()) {
                    continue; // stage 1 already flags the uncited sentence
                }
                List<String> markers = new ArrayList<String>();
                List<String> cites = new ArrayList<String>();
                for (Integer n : cited) {
                    EvidencePassage passage = byNumber.get(n);
                    markers.add(String.valueOf(n));
                    cites.add(passage == null ? null : passage.getText());
                }
                String why = uncarried(sentence.plainText(), markers, cites);
                if (!why.isEmpty()) {
                    flags.add(new ClaimFlag(ClaimFlag.Kind.UNCARRIED_CLAIM, p, sentence.getText(), why));
                }
            }
        }
        return flags;
    }

    /**
     * Why no cited passage carries the sentence ("[6] is no prose (…)", "overlap 0.21 < 0.30"); empty when one does
     * or the sentence has no content words.
     *
     * @param sentence the sentence without its markers
     * @param markers  the markers it cites ("6"), for the message
     * @param texts    the cited passage texts in the same order; {@code null} for a number without a passage
     */
    public String uncarried(String sentence, List<String> markers, List<String> texts) {
        List<String> words = contentWords(sentence);
        if (words.isEmpty() || texts.isEmpty()) {
            return "";
        }
        double best = 0;
        String why = "";
        List<String> proseSentences = new ArrayList<String>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null) {
                continue;
            }
            String boilerplate = prose.reason(text);
            if (!boilerplate.isEmpty()) {
                why = why.isEmpty() ? "[" + markers.get(i) + "] is no prose (" + boilerplate + ")" : why;
                continue;
            }
            best = Math.max(best, overlap(words, stemsOf(text)));
            for (CitationValidator.Sentence passageSentence
                    : CitationValidator.sentences(text.replace('\n', ' ').trim())) {
                proseSentences.add(passageSentence.plainText());
            }
        }
        if (best >= criteria.getMinOverlap()) {
            return "";
        }
        double bound = criteria.getMinSimilarity();
        if (bound > 0 && similarity.best(sentence, proseSentences) >= bound) {
            return "";
        }
        return !why.isEmpty() && best == 0 ? why
                : String.format(Locale.ROOT, "overlap %.2f < %.2f", best, criteria.getMinOverlap());
    }

    /** The share of {@code words} whose stem a passage contains. */
    double overlap(List<String> words, Set<String> passageStems) {
        int covered = 0;
        for (String word : words) {
            if (passageStems.contains(stem(word))) {
                covered++;
            }
        }
        return words.isEmpty() ? 0 : covered / (double) words.size();
    }

    /** The distinct content words of a sentence, lower case. */
    List<String> contentWords(String text) {
        Set<String> out = new LinkedHashSet<String>();
        for (String word : words(text)) {
            if (word.length() >= criteria.getMinWordLength()) {
                out.add(word);
            }
        }
        return new ArrayList<String>(out);
    }

    /** The stems of every passage word: the sentence's "Echtzeitüberwachung" (stem "echtz") meets "Echtzeit". */
    Set<String> stemsOf(String text) {
        Set<String> out = new LinkedHashSet<String>();
        for (String word : words(text)) {
            out.add(stem(word));
        }
        return out;
    }

    private String stem(String word) {
        return word.length() <= criteria.getStemLength() ? word : word.substring(0, criteria.getStemLength());
    }

    /** Letters and digits only, lower case; hyphens split ("RFID-Tagging-Systemen" gives three words). */
    private static List<String> words(String text) {
        String n = Normalizer.normalize(text == null ? "" : text.replace("­", ""), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (String w : n.split("[^\\p{L}\\p{Nd}]+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }
}
