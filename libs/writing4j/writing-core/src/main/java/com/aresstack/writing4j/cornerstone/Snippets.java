package com.aresstack.writing4j.cornerstone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The example sentences at a term, so the reader judges its meaning in context (Angelo 27.11.2026): never the bare
 * sentence, but the sentence with the term and its neighbours, with the heading and the source; one per source first,
 * and sentences that stand on their own before those that start with a back reference ("Dies", "Satz 1 gilt") or are
 * very short. Every size and the list of back references are settings. Without a model.
 */
public final class Snippets {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+(?=[\\p{Lu}\\d„\"(])");

    /** The settings. */
    public static final class Options {
        public static final int DEFAULT_PER_TERM = 3;
        public static final int DEFAULT_CONTEXT_SENTENCES = 1;
        public static final int DEFAULT_MIN_SENTENCE_CHARS = 40;
        public static final List<String> DEFAULT_BACK_REFERENCES = Collections.unmodifiableList(Arrays.asList(
                "Dies", "Diese", "Dieser", "Dieses", "Diesem", "Diesen", "Das", "Danach", "Dabei", "Daher", "Dazu",
                "Darüber", "Darin", "Damit", "Dafür", "Hierbei", "Hierzu", "Hierfür", "Ebenso", "Ebenfalls",
                "Außerdem", "Zudem", "Ferner", "Er", "Sie", "Es", "Satz", "Absatz", "Abs.", "Nummer", "Nr.",
                "Buchstabe", "Halbsatz", "Gleiches", "Entsprechendes", "Letzteres", "Ersteres"));

        private final int perTerm;
        private final int contextSentences;
        private final int minSentenceChars;
        private final List<String> backReferences;

        /**
         * @param perTerm          snippets per term (and per field)
         * @param contextSentences sentences before and after the sentence with the term
         * @param minSentenceChars a shorter sentence counts as not standing on its own
         * @param backReferences   words that open a sentence leaning on the one before
         */
        public Options(int perTerm, int contextSentences, int minSentenceChars, List<String> backReferences) {
            if (perTerm < 1 || contextSentences < 0 || minSentenceChars < 0) {
                throw new IllegalArgumentException("perTerm >= 1, contextSentences >= 0, minSentenceChars >= 0 "
                        + "expected");
            }
            this.perTerm = perTerm;
            this.contextSentences = contextSentences;
            this.minSentenceChars = minSentenceChars;
            this.backReferences = Collections.unmodifiableList(new ArrayList<String>(backReferences == null
                    ? Collections.<String>emptyList() : backReferences));
        }

        public static Options defaults() {
            return new Options(DEFAULT_PER_TERM, DEFAULT_CONTEXT_SENTENCES, DEFAULT_MIN_SENTENCE_CHARS,
                    DEFAULT_BACK_REFERENCES);
        }

        public List<String> getBackReferences() {
            return backReferences;
        }
    }

    /** One example: the passage, its source and heading, the sentence with the term and its neighbours. */
    public static final class Snippet {
        private final String passageId;
        private final String sourceId;
        private final String sourceTitle;
        private final String heading;
        private final int sentenceIndex;
        private final String before;
        private final String sentence;
        private final String after;
        private final boolean standalone;

        Snippet(String passageId, String sourceId, String sourceTitle, String heading, int sentenceIndex,
                String before, String sentence, String after, boolean standalone) {
            this.passageId = passageId;
            this.sourceId = sourceId;
            this.sourceTitle = sourceTitle;
            this.heading = heading;
            this.sentenceIndex = sentenceIndex;
            this.before = before;
            this.sentence = sentence;
            this.after = after;
            this.standalone = standalone;
        }

        /** The passage, to open the whole paragraph and from there the source. */
        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getSourceTitle() {
            return sourceTitle;
        }

        public String getHeading() {
            return heading;
        }

        /** The position of the sentence in the passage, from 0. */
        public int getSentenceIndex() {
            return sentenceIndex;
        }

        public String getBefore() {
            return before;
        }

        /** The sentence with the term. */
        public String getSentence() {
            return sentence;
        }

        public String getAfter() {
            return after;
        }

        /** Whether the sentence stands on its own (no back reference at its start, not very short). */
        public boolean isStandalone() {
            return standalone;
        }

        @Override
        public String toString() {
            return (before.isEmpty() ? "" : before + " ") + sentence + (after.isEmpty() ? "" : " " + after);
        }
    }

    private final Options options;

    public Snippets(Options options) {
        this.options = options == null ? Options.defaults() : options;
    }

    /** The snippets of a term in the given passages, at most {@link Options#perTerm}. */
    public List<Snippet> of(TermCandidates.Candidate term, List<String> passageIds,
                            Map<String, TermCandidates.Text> passages) {
        String needle = needle(term.getTerm());
        List<Snippet> all = new ArrayList<Snippet>();
        for (String id : passageIds) {
            TermCandidates.Text passage = passages.get(id);
            if (passage != null) {
                Snippet snippet = snippet(passage, needle);
                if (snippet != null) {
                    all.add(snippet);
                }
            }
        }
        // standing on its own and a new source first, then standing on its own, then a new source, then the rest
        List<Snippet> out = new ArrayList<Snippet>();
        Set<String> sources = new LinkedHashSet<String>();
        for (int pass = 0; pass < 4 && out.size() < options.perTerm; pass++) {
            for (Snippet snippet : all) {
                if (out.size() >= options.perTerm || out.contains(snippet)) {
                    continue;
                }
                boolean fresh = !sources.contains(snippet.sourceId);
                boolean take = pass == 0 ? snippet.standalone && fresh
                        : pass == 1 ? snippet.standalone : pass != 2 || fresh;
                if (take) {
                    out.add(snippet);
                    sources.add(snippet.sourceId);
                }
            }
        }
        return out;
    }

    Snippet snippet(TermCandidates.Text passage, String needle) {
        String flat = passage.getText().replaceAll("\\s+", " ").trim();
        String[] sentences = SENTENCE_END.split(flat);
        for (int i = 0; i < sentences.length; i++) {
            if (sentences[i].toLowerCase(Locale.ROOT).contains(needle)) {
                int from = Math.max(0, i - options.contextSentences);
                int to = Math.min(sentences.length - 1, i + options.contextSentences);
                return new Snippet(passage.getId(), passage.getSourceId(), passage.getSourceTitle(),
                        passage.getHeading(), i, join(sentences, from, i), sentences[i].trim(),
                        join(sentences, i + 1, to + 1), standsAlone(sentences[i].trim()));
            }
        }
        return null;
    }

    boolean standsAlone(String sentence) {
        if (sentence.length() < options.minSentenceChars) {
            return false;
        }
        for (String word : options.backReferences) {
            if (sentence.startsWith(word) && (sentence.length() == word.length()
                    || !Character.isLetter(sentence.charAt(word.length())))) {
                return false;
            }
        }
        return true;
    }

    private static String join(String[] sentences, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            out.append(out.length() == 0 ? "" : " ").append(sentences[i].trim());
        }
        return out.toString();
    }

    /** The last word of the term without its last letter, so the plural is found as well. */
    static String needle(String term) {
        String[] words = term.toLowerCase(Locale.ROOT).split(" ");
        String last = words[words.length - 1];
        return last.length() > 4 ? last.substring(0, last.length() - 1) : last;
    }
}
