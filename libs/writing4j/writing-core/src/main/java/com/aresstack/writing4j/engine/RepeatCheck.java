package com.aresstack.writing4j.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the sentences of a writer answer that the book already says (live run 4, book 6: "Wearables wie smarte Helme,
 * Sicherheitswesten und Handschuhe … [2]" stood four times in sections 1.2 and 1.3, and the sensor paragraphs of two
 * sources came back in several sections). The section writer gives it the sentences of every other written section of
 * the manuscript and of the section's earlier parts; {@link CitationValidator} takes a sentence out of the prose like
 * an exact repeat when it is a near repeat of one of them.
 *
 * <p>A sentence is a near repeat when an earlier sentence contains at least {@link Criteria#getMinShare() min share}
 * of its content words, compared by stem as claim-check stage 2 compares them ({@link ClaimCarryCheck}: word length
 * and stem length are those settings), so "…, die dann schnell eingreifen können" after the same sentence without
 * that clause is a repeat, and so is a short sentence another sentence already holds. A sentence with fewer than
 * {@link Criteria#getMinWords() min words} content words is only a repeat when it is the same sentence (exact
 * match, {@link CitationValidator}): a short sentence shares its few words with too many others. A sentence that
 * states a number the earlier sentence does not ("um 40 %" after "um 20 %") is never a near repeat: it is another
 * claim, and the claim check judges it. Deterministic, no model call; both bounds are settings.</p>
 */
public final class RepeatCheck {

    /** Thresholds; immutable. */
    public static final class Criteria {
        /**
         * Default fewest content words for a near repeat. Book 6: every sentence with six or more content words that an
         * earlier section already held was a repeat at the default share; below six, unrelated sentences reach it
         * ("Die DSGVO schützt die Wearable-Daten der Nutzer in der EU.").
         */
        public static final int DEFAULT_MIN_WORDS = 6;
        /**
         * Default share of a sentence's content words an earlier sentence must hold. Book 6: the repeated sentences of
         * sections 1.2 and 1.3 share 0.75 to 1.0 with their first occurrence; the first related but new sentence
         * ("Die Arbeitgeber müssen auch sicherstellen, …") shares 0.69.
         */
        public static final double DEFAULT_MIN_SHARE = 0.75;
        /**
         * Default cosine (sentence-embedding slot) from which a sentence that shares too few words is still a near
         * repeat: a paraphrase of a sentence the book already has. High on purpose, a paraphrase model scores
         * restatements above it and related but new statements below; 0 turns the semantic rule off. Not yet
         * calibrated on a live run.
         */
        public static final double DEFAULT_MIN_SIMILARITY = 0.93;

        private final int minWords;
        private final double minShare;
        private final double minSimilarity;

        public Criteria(int minWords, double minShare) {
            this(minWords, minShare, DEFAULT_MIN_SIMILARITY);
        }

        public Criteria(int minWords, double minShare, double minSimilarity) {
            if (minWords < 1 || minShare <= 0 || minShare > 1 || minSimilarity < 0 || minSimilarity > 1) {
                throw new IllegalArgumentException("min words must be >= 1, the share within (0, 1] and the "
                        + "similarity within 0..1");
            }
            this.minWords = minWords;
            this.minShare = minShare;
            this.minSimilarity = minSimilarity;
        }

        public static Criteria defaults() {
            return new Criteria(DEFAULT_MIN_WORDS, DEFAULT_MIN_SHARE, DEFAULT_MIN_SIMILARITY);
        }

        /** The semantic near-repeat bound; 0 = lexical only. */
        public double getMinSimilarity() {
            return minSimilarity;
        }

        public int getMinWords() {
            return minWords;
        }

        public double getMinShare() {
            return minShare;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Criteria)) {
                return false;
            }
            Criteria c = (Criteria) o;
            return c.minWords == minWords && Double.compare(c.minShare, minShare) == 0
                    && Double.compare(c.minSimilarity, minSimilarity) == 0;
        }

        @Override
        public int hashCode() {
            return (minWords * 31 + Double.valueOf(minShare).hashCode()) * 31
                    + Double.valueOf(minSimilarity).hashCode();
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "RepeatCheck.Criteria[minWords=%d, minShare=%.2f, minSimilarity=%.2f]",
                    minWords, minShare, minSimilarity);
        }
    }

    /** A number with its sign, decimals and a directly following unit sign: "-20 °C", "15,5 %", "1.100". */
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "[-+\u2212]?\\d+(?:[.,]\\d+)*(?:\\s?(?:%|\u2030|°\\s?[CF]?))?");

    private final Criteria criteria;
    private final ClaimCarryCheck words;
    private final SentenceSimilarity similarity;

    /**
     * @param criteria the near-repeat bounds; the defaults when {@code null}
     * @param carry    the claim check's word and stem lengths; the defaults when {@code null}
     */
    public RepeatCheck(Criteria criteria, ClaimCarryCheck.Criteria carry) {
        this(criteria, carry, SentenceSimilarity.none());
    }

    /**
     * @param similarity the sentence-embedding slot; a sentence with enough content words whose cosine to a remembered
     *                   sentence reaches {@link Criteria#getMinSimilarity()} is a near repeat even with few shared
     *                   words (a paraphrase). {@link SentenceSimilarity#none()} keeps the check lexical.
     */
    public RepeatCheck(Criteria criteria, ClaimCarryCheck.Criteria carry, SentenceSimilarity similarity) {
        this.criteria = criteria == null ? Criteria.defaults() : criteria;
        this.words = new ClaimCarryCheck(carry);
        this.similarity = similarity == null ? SentenceSimilarity.none() : similarity;
    }

    /** A check that finds no near repeat: only exact repeats leave the text. */
    public static Memory none() {
        return new RepeatCheck(null, null).memory(Collections.<String>emptyList()).disabled();
    }

    /** A memory of the sentences of {@code texts} (paragraphs separated by a blank line, markers allowed). */
    public Memory memory(Collection<String> texts) {
        Memory memory = new Memory(this, new ArrayList<Remembered>(), true);
        if (texts != null) {
            for (String text : texts) {
                memory.rememberText(text);
            }
        }
        return memory;
    }

    /**
     * The sentences written so far: the other sections of the book and the earlier parts of the section, plus, while
     * one answer is checked, its own kept sentences. Not thread-safe; {@link #copy()} before each check, so a repair
     * is checked without the sentences of the answer it replaces.
     */
    /** The numbers of a sentence as written, spaces left out ("-20°C", "15,5%"). */
    static Set<String> numbers(String sentence) {
        Set<String> out = new java.util.HashSet<String>();
        java.util.regex.Matcher m = NUMBER.matcher(sentence == null ? "" : sentence.replace('\u00A0', ' '));
        while (m.find()) {
            out.add(m.group().replace(" ", "").replace('\u2212', '-'));
        }
        return out;
    }

    /** One remembered sentence: the stems of its words and its numbers; immutable. */
    private static final class Remembered {
        final String text;
        final Set<String> stems;
        final Set<String> numbers;

        Remembered(String text, Set<String> stems, Set<String> numbers) {
            this.text = text;
            this.stems = Collections.unmodifiableSet(stems);
            this.numbers = Collections.unmodifiableSet(numbers);
        }
    }

    public static final class Memory {
        private final RepeatCheck check;
        private final List<Remembered> sentences;
        private final boolean enabled;

        private Memory(RepeatCheck check, List<Remembered> sentences, boolean enabled) {
            this.check = check;
            this.sentences = sentences;
            this.enabled = enabled;
        }

        private Memory disabled() {
            return new Memory(check, sentences, false);
        }

        /** An independent copy; the stem sets are shared, they never change. */
        public Memory copy() {
            return new Memory(check, new ArrayList<Remembered>(sentences), enabled);
        }

        /** How many sentences it holds. */
        public int size() {
            return sentences.size();
        }

        /**
         * Whether {@code sentence} (without markers) is a near repeat of a remembered one: it has at least
         * {@link Criteria#getMinWords() min words} content words and a remembered sentence holds at least
         * {@link Criteria#getMinShare() min share} of them and every number it states, or (sentence-embedding slot) a
         * remembered sentence with every number it states reaches {@link Criteria#getMinSimilarity() min similarity}.
         */
        public boolean repeats(String sentence) {
            if (!enabled) {
                return false;
            }
            List<String> content = check.words.contentWords(sentence);
            if (content.size() < check.criteria.getMinWords()) {
                return false;
            }
            Set<String> numbers = numbers(sentence);
            List<String> sameNumbers = new ArrayList<String>();
            for (Remembered earlier : sentences) {
                if (!earlier.numbers.containsAll(numbers)) {
                    continue;
                }
                if (check.words.overlap(content, earlier.stems) >= check.criteria.getMinShare()) {
                    return true;
                }
                sameNumbers.add(earlier.text);
            }
            double bound = check.criteria.getMinSimilarity();
            return bound > 0 && check.similarity.best(sentence, sameNumbers) >= bound;
        }

        /** Adds one sentence (without markers). */
        public void remember(String sentence) {
            Set<String> stems = check.words.stemsOf(sentence);
            if (!stems.isEmpty()) {
                sentences.add(new Remembered(sentence, stems, numbers(sentence)));
            }
        }

        /** Adds every sentence of a written text. */
        public void rememberText(String text) {
            for (String paragraph : (text == null ? "" : text).split("\\n\\s*\\n")) {
                for (CitationValidator.Sentence sentence
                        : CitationValidator.sentences(paragraph.replace('\n', ' ').trim())) {
                    remember(sentence.plainText());
                }
            }
        }
    }
}
