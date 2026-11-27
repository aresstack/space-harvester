package com.aresstack.writing4j.planwriter;

import com.aresstack.writing4j.acronym.AcronymAnalysis;
import com.aresstack.writing4j.acronym.AcronymExtractor;
import com.aresstack.writing4j.acronym.AcronymFinding;
import com.aresstack.writing4j.acronym.AcronymSettings;
import com.aresstack.writing4j.acronym.SentenceSegmenter;
import com.aresstack.writing4j.engine.CitationValidator;
import com.aresstack.writing4j.sectionplan.SectionPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The writing memory (Schreibgedächtnis) of a book written section after section: what the writer of the next
 * section must know of what is already written, derived deterministically from the plan and the manuscript, never by
 * a model and never stored on its own. It holds
 * <ul>
 * <li>the questions of this section already answered in an earlier section, with that section (refer back, do not
 * answer again),</li>
 * <li>the passages of this section an earlier section already cited (do not repeat their statement),</li>
 * <li>the contradictions an earlier section already presented,</li>
 * <li>the sections that come next with their questions (do not anticipate them),</li>
 * <li>the acronyms the manuscript introduced with their long form (do not introduce them again),</li>
 * <li>the end of the previous section, and the text of every written section for the repeat check.</li>
 * </ul>
 * The memory lines are compact and fit into a character budget ({@link MemoryBlock#getText()}): the lines on this
 * section come first, the outlook last, and what does not fit is left out whole, so the prompt never grows with the
 * length of the book. Nothing is copied from the evidence: the memory names sections, questions and passage numbers.
 */
public final class WritingMemory {

    /** What the memory gives the writer of one section; immutable. */
    public static final class MemoryBlock {
        private final String text;
        private final String previousEnd;
        private final List<String> glossary;
        private final List<String> written;
        private final int lines;
        private final int left;

        MemoryBlock(String text, String previousEnd, List<String> glossary, List<String> written, int lines,
                    int left) {
            this.text = text;
            this.previousEnd = previousEnd;
            this.glossary = Collections.unmodifiableList(new ArrayList<String>(glossary));
            this.written = Collections.unmodifiableList(new ArrayList<String>(written));
            this.lines = lines;
            this.left = left;
        }

        /** The memory lines within the budget, empty when nothing is written yet and nothing comes next. */
        public String getText() {
            return text;
        }

        /** The last sentences of the previous section, its citations left out, within the budget. */
        public String getPreviousEnd() {
            return previousEnd;
        }

        /** The acronyms the manuscript introduced, as {@code "ABC: long form"}. */
        public List<String> getGlossary() {
            return glossary;
        }

        /** The text of every written section, for the repeat check against the book. */
        public List<String> getWritten() {
            return written;
        }

        /** Memory lines in {@link #getText()}. */
        public int getLines() {
            return lines;
        }

        /** Memory lines that did not fit into the budget. */
        public int getLeft() {
            return left;
        }
    }

    /** The budget of the memory; every value is a setting. */
    public static final class Budget {
        public static final int DEFAULT_TEXT_CHARS = 900;
        public static final int DEFAULT_PREVIOUS_CHARS = 600;
        public static final int DEFAULT_GLOSSARY_CHARS = 400;

        private final int textChars;
        private final int previousChars;
        private final int glossaryChars;

        public Budget(int textChars, int previousChars, int glossaryChars) {
            if (textChars < 0 || previousChars < 0 || glossaryChars < 0) {
                throw new IllegalArgumentException("a budget is not negative");
            }
            this.textChars = textChars;
            this.previousChars = previousChars;
            this.glossaryChars = glossaryChars;
        }

        public static Budget defaults() {
            return new Budget(DEFAULT_TEXT_CHARS, DEFAULT_PREVIOUS_CHARS, DEFAULT_GLOSSARY_CHARS);
        }

        public int getTextChars() {
            return textChars;
        }

        public int getPreviousChars() {
            return previousChars;
        }

        public int getGlossaryChars() {
            return glossaryChars;
        }
    }

    private static final Pattern MARKERS = Pattern.compile("\\s*\\[\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*\\]");

    private final Budget budget;
    private final boolean german;
    private final AcronymExtractor acronyms = new AcronymExtractor(SentenceSegmenter.splitter());

    public WritingMemory(Budget budget, boolean german) {
        this.budget = budget == null ? Budget.defaults() : budget;
        this.german = german;
    }

    /**
     * The memory for {@code section}.
     *
     * @param written the sections written so far, in the plan's order; a failed one counts as not written
     * @param numbers the number this section's prompt gives each passage id
     */
    public MemoryBlock forSection(SectionPlan plan, SectionPlan.Section section, List<WrittenSection> written,
                                  Map<String, Integer> numbers) {
        Map<String, String> answeredIn = new LinkedHashMap<String, String>();
        Map<String, String> citedIn = new LinkedHashMap<String, String>();
        Map<String, String> presentedIn = new LinkedHashMap<String, String>();
        List<String> texts = new ArrayList<String>();
        WrittenSection previous = null;
        for (WrittenSection done : written) {
            if (!done.isOk() || done.getText().isEmpty()) {
                continue;
            }
            previous = done;
            texts.add(done.getText());
            SectionPlan.Section planned = plan.section(done.getSectionId());
            if (planned == null) {
                continue;
            }
            for (String questionId : answered(planned, done)) {
                if (!answeredIn.containsKey(questionId)) {
                    answeredIn.put(questionId, done.getTitle());
                }
            }
            for (WrittenSection.Citation citation : done.getCitations().values()) {
                if (!citedIn.containsKey(citation.getPassageId())) {
                    citedIn.put(citation.getPassageId(), done.getTitle());
                }
            }
            for (SectionPlan.Contradiction contradiction : planned.getContradictions()) {
                if (!presentedIn.containsKey(contradiction.getId())) {
                    presentedIn.put(contradiction.getId(), done.getTitle());
                }
            }
        }

        List<String> lines = new ArrayList<String>();
        for (SectionPlan.Question question : section.getQuestions()) {
            String where = answeredIn.get(question.getId());
            if (where != null) {
                lines.add((german ? "Schon beantwortet in „" : "Already answered in \"") + where
                        + (german ? "“ (darauf verweisen, nicht wiederholen): " : "\" (refer back, do not repeat): ")
                        + question.getText());
            }
        }
        Map<String, List<Integer>> reused = new LinkedHashMap<String, List<Integer>>();
        for (Map.Entry<String, Integer> number : numbers.entrySet()) {
            String where = citedIn.get(number.getKey());
            if (where != null) {
                List<Integer> list = reused.get(where);
                if (list == null) {
                    list = new ArrayList<Integer>();
                    reused.put(where, list);
                }
                list.add(number.getValue());
            }
        }
        for (Map.Entry<String, List<Integer>> entry : reused.entrySet()) {
            Collections.sort(entry.getValue());
            lines.add((german ? "Schon zitiert in „" : "Already cited in \"") + entry.getKey() + (german ? "“: "
                    : "\": ") + markers(entry.getValue()) + (german ? " (ihre Aussage nicht wiederholen, nur Neues "
                    + "daraus)" : " (do not repeat their statement, only what is new)"));
        }
        for (SectionPlan.Contradiction contradiction : section.getContradictions()) {
            String where = presentedIn.get(contradiction.getId());
            if (where != null) {
                lines.add((german ? "Widerspruch schon dargestellt in „" : "Contradiction already presented in \"")
                        + where + (german ? "“: kurz darauf verweisen" : "\": refer to it briefly"));
            }
        }
        boolean after = false;
        for (SectionPlan.Section next : plan.getSections()) {
            if (next.getId().equals(section.getId())) {
                after = true;
                continue;
            }
            if (!after) {
                continue;
            }
            List<String> questions = new ArrayList<String>();
            for (SectionPlan.Question question : next.getQuestions()) {
                questions.add(question.getText());
            }
            lines.add((german ? "Folgt später, hier nicht vorwegnehmen: „" : "Comes later, do not anticipate it "
                    + "here: \"") + next.getTitle() + (german ? "“" : "\"")
                    + (questions.isEmpty() ? "" : " (" + String.join(" | ", questions) + ")"));
        }

        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (String line : lines) {
            String add = "- " + line + "\n";
            if (text.length() + add.length() > budget.textChars) {
                continue;
            }
            text.append(add);
            shown++;
        }
        String end = previous == null ? "" : tail(withoutMarkers(previous.getText()),
                budget.previousChars);
        return new MemoryBlock(text.toString().trim(), end, glossary(texts), texts, shown, lines.size() - shown);
    }

    /** The questions a written section answered: those whose direct evidence in the plan its text cites. */
    private static Set<String> answered(SectionPlan.Section planned, WrittenSection done) {
        Set<String> cited = new LinkedHashSet<String>();
        for (WrittenSection.Citation citation : done.getCitations().values()) {
            cited.add(citation.getPassageId());
        }
        Set<String> out = new LinkedHashSet<String>();
        for (SectionPlan.Evidence evidence : planned.getEvidence()) {
            if (cited.contains(evidence.getPassageId())) {
                out.addAll(evidence.getQuestionIds());
            }
        }
        return out;
    }

    /** The text without its citation markers ({@code [2]}, {@code [3, 5]}). */
    static String withoutMarkers(String text) {
        return MARKERS.matcher(text == null ? "" : text).replaceAll("");
    }

    /** The acronyms with a long form the manuscript stated, within the budget, in order of first use. */
    List<String> glossary(List<String> texts) {
        List<String> out = new ArrayList<String>();
        if (texts.isEmpty()) {
            return out;
        }
        AcronymAnalysis analysis = acronyms.analyze(withoutMarkers(String.join("\n\n", texts)),
                AcronymSettings.defaults());
        Set<String> seen = new LinkedHashSet<String>();
        int used = 0;
        for (AcronymFinding finding : analysis.acronyms()) {
            if (finding.longForms().isEmpty() || !seen.add(finding.normalized())) {
                continue;
            }
            String line = finding.shortForm() + ": " + finding.longForms().get(0);
            if (used + line.length() + 2 > budget.glossaryChars) {
                continue;
            }
            out.add(line);
            used += line.length() + 2;
        }
        return out;
    }

    private static String markers(List<Integer> numbers) {
        StringBuilder out = new StringBuilder();
        for (Integer number : numbers) {
            out.append('[').append(number).append(']');
        }
        return out.toString();
    }

    /** The last whole sentences of a text that fit into {@code chars}. */
    static String tail(String text, int chars) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= chars) {
            return clean;
        }
        List<CitationValidator.Sentence> sentences = CitationValidator.sentencesOf(clean);
        StringBuilder out = new StringBuilder();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i).getText().trim();
            if (out.length() + sentence.length() + 1 > chars) {
                break;
            }
            out.insert(0, sentence + (out.length() == 0 ? "" : " "));
        }
        return out.toString();
    }
}
