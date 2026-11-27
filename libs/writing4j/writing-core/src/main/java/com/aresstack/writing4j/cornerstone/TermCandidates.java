package com.aresstack.writing4j.cornerstone;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.writing4j.acronym.TermScanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The candidates for the cornerstones of a book, without a model: the terms of the basket that are much more frequent
 * there than in the general language (measurements M4, M7, M8e of the book function).
 * <ul>
 * <li>Terms: German nouns (capitalised inside a sentence), abbreviations in capitals, and adjective plus noun. A rough
 * cut of the endings lets singular and plural fall together.</li>
 * <li>Specificity: the Zipf value of the term in the basket minus the Zipf value in the general language (for a term
 * of two words the last word; an unknown or rarer word counts with {@link Options#getUnknownFloor()}).</li>
 * <li>Near the subject: passages naming the subject of the book are the anchors; a term counts with its anchor
 * passages and the sources of those, and its rank is specificity times ln(1 + sources). Without subject words or
 * anchors every passage counts.</li>
 * <li>The subject words are the caller's, or else the nouns of the mission and of what the fence includes that are
 * more frequent in the basket than in the general language.</li>
 * </ul>
 * The candidates may carry by-catch; the sieve ({@link CornerstoneSieve}) decides. Every size is a setting.
 */
public final class TermCandidates {

    /** Words that are never the adjective of a pair: articles, pronouns, quantifiers, auxiliary and modal verbs. */
    private static final Set<String> NO_ADJECTIVE = new HashSet<String>(Arrays.asList(("der die das den dem des ein "
            + "eine einen einem einer eines kein keine keinen keinem keiner keines dieser diese dieses diesen diesem "
            + "jener jene jenes jenen jenem welche welcher welches welchen welchem alle allen aller alles viele vielen "
            + "vieler einige einigen einiger mehrere mehreren manche manchen mancher solche solchen solcher beide beiden "
            + "andere anderen anderer anderes anderem weitere weiteren weiterer weiteres jede jeden jedem jeder jedes "
            + "meine meinen meinem meiner deine deinen seine seinen seinem seiner ihre ihren ihrem ihrer unsere unseren "
            + "unserem unserer eure euren deren dessen werden wurden worden haben hatten habe sein sind waren seien "
            + "wäre wären können konnten müssen mussten sollen sollten dürfen durften wollen wollten mögen möchten "
            + "würde würden eigene eigenen eigener eigenes sowie gegen ohne unter über zwischen wegen").split(" ")));

    /** The settings; the defaults are those of the measurements. */
    public static final class Options {
        public static final int DEFAULT_MIN_PASSAGES = 3;
        public static final int DEFAULT_MIN_SOURCES = 2;
        public static final int DEFAULT_DEPTH = 300;
        public static final double DEFAULT_UNKNOWN_FLOOR = 1.5;

        private final int minPassages;
        private final int minSources;
        private final int depth;
        private final double unknownFloor;

        /**
         * @param minPassages  a term stands in at least this many (anchor) passages
         * @param minSources   … from at least this many sources
         * @param depth        how many candidates of the ranking go to the sieve
         * @param unknownFloor the Zipf value of the general language for an unknown or rarer word
         */
        public Options(int minPassages, int minSources, int depth, double unknownFloor) {
            if (minPassages < 1 || minSources < 1 || depth < 1 || unknownFloor < 0) {
                throw new IllegalArgumentException("minPassages >= 1, minSources >= 1, depth >= 1, unknownFloor >= 0 "
                        + "expected");
            }
            this.minPassages = minPassages;
            this.minSources = minSources;
            this.depth = depth;
            this.unknownFloor = unknownFloor;
        }

        public static Options defaults() {
            return new Options(DEFAULT_MIN_PASSAGES, DEFAULT_MIN_SOURCES, DEFAULT_DEPTH, DEFAULT_UNKNOWN_FLOOR);
        }

        public int getMinPassages() {
            return minPassages;
        }

        public int getMinSources() {
            return minSources;
        }

        public int getDepth() {
            return depth;
        }

        public double getUnknownFloor() {
            return unknownFloor;
        }
    }

    /** A passage as the candidates read it. */
    public static final class Text {
        private final String id;
        private final String sourceId;
        private final String text;
        private final String heading;
        private final String sourceTitle;

        public Text(String id, String sourceId, String text) {
            this(id, sourceId, text, "", "");
        }

        /** With the heading path and the title of the source, which the examples show. */
        public Text(String id, String sourceId, String text, String heading, String sourceTitle) {
            this.id = id;
            this.sourceId = sourceId;
            this.text = text == null ? "" : text;
            this.heading = heading == null ? "" : heading;
            this.sourceTitle = sourceTitle == null ? "" : sourceTitle;
        }

        public String getHeading() {
            return heading;
        }

        public String getSourceTitle() {
            return sourceTitle;
        }

        public String getId() {
            return id;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getText() {
            return text;
        }
    }

    /** One candidate with its passages and numbers. */
    public static final class Candidate {
        private final String term;
        private final String key;
        private final List<String> passageIds;
        private final int anchorPassages;
        private final int sources;
        private final int occurrences;
        private final double specificity;
        private final double rank;

        Candidate(String term, String key, List<String> passageIds, int anchorPassages, int sources, int occurrences,
                  double specificity, double rank) {
            this.term = term;
            this.key = key;
            this.passageIds = Collections.unmodifiableList(new ArrayList<String>(passageIds));
            this.anchorPassages = anchorPassages;
            this.sources = sources;
            this.occurrences = occurrences;
            this.specificity = specificity;
            this.rank = rank;
        }

        /** The term as the basket writes it most often. */
        public String getTerm() {
            return term;
        }

        /** The term with cut endings, the same for singular and plural. */
        public String getKey() {
            return key;
        }

        /** The passages with the term, the anchor passages first. */
        public List<String> getPassageIds() {
            return passageIds;
        }

        public int getAnchorPassages() {
            return anchorPassages;
        }

        /** The sources the rank counts (of the anchor passages when there are anchors). */
        public int getSources() {
            return sources;
        }

        public int getOccurrences() {
            return occurrences;
        }

        public double getSpecificity() {
            return specificity;
        }

        public double getRank() {
            return rank;
        }

        @Override
        public String toString() {
            return term + String.format(Locale.ROOT, " (%.2f)", rank);
        }
    }

    /** The candidates in rank order with what they were measured against. */
    public static final class Result {
        private final List<Candidate> candidates;
        private final List<String> subjectWords;
        private final int anchorPassages;
        private final int terms;

        Result(List<Candidate> candidates, List<String> subjectWords, int anchorPassages, int terms) {
            this.candidates = Collections.unmodifiableList(new ArrayList<Candidate>(candidates));
            this.subjectWords = Collections.unmodifiableList(new ArrayList<String>(subjectWords));
            this.anchorPassages = anchorPassages;
            this.terms = terms;
        }

        public List<Candidate> getCandidates() {
            return candidates;
        }

        /** The words of the subject the anchors were found with; empty when every passage counted. */
        public List<String> getSubjectWords() {
            return subjectWords;
        }

        /** Passages naming the subject; {@code 0} when every passage counted. */
        public int getAnchorPassages() {
            return anchorPassages;
        }

        /** Terms that met the minimums, before the depth. */
        public int getTerms() {
            return terms;
        }
    }

    private final GeneralLanguage general;
    private final Options options;

    public TermCandidates(GeneralLanguage general, Options options) {
        this.general = general == null ? GeneralLanguage.german() : general;
        this.options = options == null ? Options.defaults() : options;
    }

    /**
     * The candidates of the passages.
     *
     * @param subjectWords the words of the subject; empty to take them from the fence
     */
    public Result find(List<Text> passages, Fence fence, List<String> subjectWords) {
        Map<String, Term> terms = new LinkedHashMap<String, Term>();
        Map<String, Integer> words = new HashMap<String, Integer>();
        Map<String, Set<String>> lowerOf = new HashMap<String, Set<String>>();
        long total = 0;
        for (Text passage : passages) {
            List<TermScanner.Token> tokens = TermScanner.tokens(passage.text);
            total += tokens.size();
            Set<String> lower = new HashSet<String>();
            for (int i = 0; i < tokens.size(); i++) {
                TermScanner.Token token = tokens.get(i);
                String text = token.text();
                lower.add(text.toLowerCase(Locale.ROOT));
                words.merge(stem(text), 1, Integer::sum);
                boolean start = i == 0 || sentenceBreak(passage.text, tokens.get(i - 1).span().end(),
                        token.span().start());
                if (isAcronym(text)) {
                    term(terms, text.toLowerCase(Locale.ROOT), text).seen(passage, true);
                } else if (isCapitalised(text)) {
                    term(terms, stem(text), text).seen(passage, !start);
                    if (!start && isAdjective(tokens.get(i - 1).text())
                            && onlySpace(passage.text, tokens.get(i - 1).span().end(), token.span().start())) {
                        String adjective = tokens.get(i - 1).text();
                        term(terms, stem(adjective) + " " + stem(text), adjective + " " + text).seen(passage, true);
                    }
                }
            }
            lowerOf.put(passage.id, lower);
        }

        List<String> subject = new ArrayList<String>();
        for (String word : subjectWords == null || subjectWords.isEmpty() ? fenceNouns(fence) : subjectWords) {
            String key = stem(word);
            if (key.length() < 2 || subject.contains(key)) {
                continue;
            }
            boolean given = subjectWords != null && !subjectWords.isEmpty();
            Integer count = words.get(key);
            if (given || count != null && zipf(count, total) > general.zipf(word)) {
                subject.add(key);
            }
        }
        Set<String> anchors = new HashSet<String>();
        for (Text passage : passages) {
            for (String word : lowerOf.get(passage.id)) {
                if (containsAny(word, subject)) {
                    anchors.add(passage.id);
                    break;
                }
            }
        }
        boolean near = !anchors.isEmpty();

        Map<String, String> sourceOf = new HashMap<String, String>();
        for (Text passage : passages) {
            sourceOf.put(passage.id, passage.sourceId);
        }
        List<Candidate> out = new ArrayList<Candidate>();
        for (Term term : terms.values()) {
            if (!term.candidate || isSubject(term.key, subject)) {
                continue;
            }
            List<String> anchored = new ArrayList<String>();
            List<String> rest = new ArrayList<String>();
            for (String id : term.passages) {
                (anchors.contains(id) ? anchored : rest).add(id);
            }
            List<String> counted = near ? anchored : rest;
            Set<String> sources = new LinkedHashSet<String>();
            for (String id : counted) {
                sources.add(sourceOf.get(id));
            }
            if (counted.size() < options.minPassages || sources.size() < options.minSources) {
                continue;
            }
            String shown = term.shown();
            double specificity = zipf(term.occurrences, total) - generalZipf(term);
            double rank = specificity * Math.log(1 + sources.size());
            List<String> ordered = new ArrayList<String>(anchored);
            ordered.addAll(near ? rest : Collections.<String>emptyList());
            out.add(new Candidate(shown, term.key, ordered, near ? anchored.size() : 0, sources.size(),
                    term.occurrences, specificity, rank));
        }
        int met = out.size();
        Collections.sort(out, (a, b) -> {
            int c = Double.compare(b.rank, a.rank);
            return c != 0 ? c : a.term.compareTo(b.term);
        });
        List<Candidate> kept = out.size() > options.depth ? out.subList(0, options.depth) : out;
        return new Result(kept, near ? subject : Collections.<String>emptyList(), near ? anchors.size() : 0, met);
    }

    /** The general Zipf value of a term: the highest of its written forms (the last word of a pair), at least the floor. */
    private double generalZipf(Term term) {
        double best = 0;
        for (String form : term.forms.keySet()) {
            String word = form.contains(" ") ? form.substring(form.lastIndexOf(' ') + 1) : form;
            best = Math.max(best, general.zipf(word));
        }
        return Math.max(best, options.unknownFloor);
    }

    private static double zipf(int occurrences, long total) {
        return total == 0 ? 0 : Math.log10(occurrences * 1e9 / total);
    }

    /** The nouns (inside a sentence) and abbreviations of the mission and of the inclusions. */
    static List<String> fenceNouns(Fence fence) {
        List<String> out = new ArrayList<String>();
        if (fence == null) {
            return out;
        }
        List<String> texts = new ArrayList<String>();
        texts.add(fence.getMission());
        texts.addAll(fence.getIncluded());
        for (String text : texts) {
            List<TermScanner.Token> tokens = TermScanner.tokens(text);
            for (int i = 0; i < tokens.size(); i++) {
                TermScanner.Token token = tokens.get(i);
                boolean start = i == 0 || sentenceBreak(text, tokens.get(i - 1).span().end(), token.span().start());
                for (TermScanner.Token part : TermScanner.parts(token)) {
                    String word = part.text();
                    if ((isAcronym(word) || !start && isCapitalised(word)) && !out.contains(word)) {
                        out.add(word);
                    }
                }
            }
        }
        return out;
    }

    private static boolean isSubject(String key, List<String> subject) {
        for (String part : key.split(" ")) {
            if (subject.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String word, List<String> keys) {
        for (String key : keys) {
            if (word.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static Term term(Map<String, Term> terms, String key, String form) {
        Term term = terms.get(key);
        if (term == null) {
            term = new Term(key);
            terms.put(key, term);
        }
        term.forms.merge(form, 1, Integer::sum);
        return term;
    }

    /** A rough cut of German endings so that singular and plural fall together. */
    public static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (String ending : new String[]{"en", "er", "es", "e", "n", "s"}) {
            if (w.endsWith(ending) && w.length() - ending.length() >= 4) {
                return w.substring(0, w.length() - ending.length());
            }
        }
        return w;
    }

    /** Capitals and digits only, at least two capitals: DSGVO, BLE, ASR. */
    static boolean isAcronym(String word) {
        int capitals = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLowerCase(c)) {
                return false;
            }
            capitals += Character.isUpperCase(c) ? 1 : 0;
        }
        return capitals >= 2;
    }

    /** A capital first, then at least one small letter, and no more than an abbreviation-free word. */
    static boolean isCapitalised(String word) {
        if (word.length() < 2 || !Character.isUpperCase(word.charAt(0))) {
            return false;
        }
        for (int i = 1; i < word.length(); i++) {
            if (Character.isLowerCase(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** A small word with an adjective ending that is no article, pronoun or verb of the list. */
    static boolean isAdjective(String word) {
        if (word.length() < 4 || !Character.isLowerCase(word.charAt(0))) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isLetter(word.charAt(i))) {
                return false;
            }
        }
        String w = word.toLowerCase(Locale.ROOT);
        if (NO_ADJECTIVE.contains(w)) {
            return false;
        }
        return w.endsWith("e") || w.endsWith("en") || w.endsWith("er") || w.endsWith("es") || w.endsWith("em");
    }

    /** Whether a sentence or a line ends between two words. */
    static boolean sentenceBreak(String text, int from, int to) {
        for (int i = from; i < to && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ':' || c == '\n' || c == '•' || c == '"' || c == '„'
                    || c == '“' || c == '#' || c == '*' || c == '|') {
                return true;
            }
        }
        return false;
    }

    private static boolean onlySpace(String text, int from, int to) {
        for (int i = from; i < to && i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** A term while counting. */
    private static final class Term {
        final String key;
        final Map<String, Integer> forms = new LinkedHashMap<String, Integer>();
        final Set<String> passages = new LinkedHashSet<String>();
        int occurrences;
        /** Seen at least once where it is a candidate (a noun inside a sentence, an abbreviation, a pair). */
        boolean candidate;

        Term(String key) {
            this.key = key;
        }

        void seen(Text passage, boolean asCandidate) {
            occurrences++;
            passages.add(passage.id);
            candidate |= asCandidate;
        }

        String shown() {
            String best = "";
            int most = -1;
            for (Map.Entry<String, Integer> form : forms.entrySet()) {
                if (form.getValue() > most) {
                    most = form.getValue();
                    best = form.getKey();
                }
            }
            return best;
        }
    }
}
