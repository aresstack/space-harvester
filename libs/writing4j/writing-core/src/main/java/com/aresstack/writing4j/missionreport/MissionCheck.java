package com.aresstack.writing4j.missionreport;

import com.aresstack.writing4j.memory.EvidenceVerdict;
import com.aresstack.writing4j.memory.PassageText;
import com.aresstack.writing4j.memory.QuestionEvidence;
import com.aresstack.writing4j.memory.ResearchMemory;
import com.aresstack.writing4j.memory.ResearchQuestion;
import com.aresstack.writing4j.memory.ResearchTopic;
import com.aresstack.writing4j.memory.TopicEvidence;
import com.aresstack.writing4j.engine.CitationValidator;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Verdict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Component 18 of the build plan, the mission report ("Auftragsabgleich"): built at the end without a language model
 * from what is stored. The memory (component 7) gives the topics, questions and evidence; the text of the book
 * (component 10) its sections and sentences; the sentence check (component 11) the verdict per sentence; the section
 * plan (component 9) the fate per topic; the contradiction check (component 17) the open contradictions; the source
 * rating (component 16) the kind and legal space of each source; the scope (component 1) the length asked for.
 * Every input but the memory may be left out, and the report then says what is missing instead of guessing.
 * <p>
 * A sentence belongs to a topic when it cites a passage linked to the topic: the direct evidence and the leads of the
 * topic's questions (those of topics merged into it included), the topic's findings, and the passages the section plan
 * gave it, matched by passage id or by the passage text's key. Not thread safe; one check per report.
 */
public final class MissionCheck {

    /** The kind of a cited source without a rating. */
    public static final String UNRATED = "OHNE_BEWERTUNG";

    /** {@code [3]}, {@code [1, 4]}: the markers of the writer, as {@code CitationValidator} reads them. */
    private static final Pattern MARKER = Pattern.compile("\\[\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*\\]");

    /** A contradiction as the contradiction check (component 17) stores it, reduced to what the report lists. */
    public static final class ContradictionRecord {
        private final String id;
        private final String scope;
        private final String scopeId;
        private final String sourceA;
        private final String sourceB;
        private final String verdict;
        private final String state;

        /**
         * @param scope   {@code FRAGE} or {@code THEMA}: where the two pieces of evidence meet
         * @param sourceA the address (or id) of the first source
         * @param verdict {@code WIDERSPRUCH}, {@code VEREINBAR} or {@code UNKLAR}; empty for a pair not judged yet
         * @param state   {@code OFFEN}, {@code IM_TEXT_DARGESTELLT} or {@code VOM_NUTZER_ENTSCHIEDEN}
         */
        public ContradictionRecord(String id, String scope, String scopeId, String sourceA, String sourceB,
                                   String verdict, String state) {
            this.id = BookSection.clean(id);
            this.scope = upper(scope);
            this.scopeId = BookSection.clean(scopeId);
            this.sourceA = BookSection.clean(sourceA);
            this.sourceB = BookSection.clean(sourceB);
            this.verdict = upper(verdict);
            this.state = upper(state).isEmpty() ? "OFFEN" : upper(state);
        }

        public String getId() {
            return id;
        }

        public String getScope() {
            return scope;
        }

        public String getScopeId() {
            return scopeId;
        }

        public String getSourceA() {
            return sourceA;
        }

        public String getSourceB() {
            return sourceB;
        }

        public String getVerdict() {
            return verdict;
        }

        public String getState() {
            return state;
        }

        /** A contradiction or an unclear pair that the text does not present yet and nobody decided. */
        public boolean isOpen() {
            return "OFFEN".equals(state) && ("WIDERSPRUCH".equals(verdict) || "UNKLAR".equals(verdict));
        }

        /** What a pair that is not open counts as: its verdict when it holds no conflict, else its state. */
        String otherState() {
            if ("VEREINBAR".equals(verdict)) {
                return "VEREINBAR";
            }
            if (verdict.isEmpty()) {
                return "UNBEURTEILT";
            }
            return state;
        }
    }

    /** What the source rating (component 16) says about a source. */
    public static final class SourceInfo {
        private final String kind;
        private final String jurisdiction;

        /**
         * @param kind         the kind of source ({@code BEHOERDE_UNFALLVERSICHERUNG}, {@code HERSTELLER_HANDEL}, ...)
         * @param jurisdiction its legal space ({@code DE}, {@code AT}, {@code EU}, ...), empty when unknown
         */
        public SourceInfo(String kind, String jurisdiction) {
            this.kind = upper(kind).isEmpty() ? UNRATED : upper(kind);
            this.jurisdiction = upper(jurisdiction);
        }

        public String getKind() {
            return kind;
        }

        public String getJurisdiction() {
            return jurisdiction;
        }
    }

    /** A cited sentence of the text: its verdict and the keys of the passages it cites. */
    private static final class CitedSentence {
        private final Verdict verdict;
        private final Set<String> keys;

        CitedSentence(Verdict verdict, Set<String> keys) {
            this.verdict = verdict;
            this.keys = keys;
        }
    }

    private final ResearchMemory memory;
    private final List<BookSection> sections = new ArrayList<BookSection>();
    private SentenceLookup lookup = SentenceLookup.NONE;
    private final List<Placement> placements = new ArrayList<Placement>();
    private List<ContradictionRecord> contradictions;
    private final Map<String, SourceInfo> sources = new HashMap<String, SourceInfo>();
    private int requestedMinWords;
    private int requestedMaxWords;

    public MissionCheck(ResearchMemory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("the mission report needs the research memory");
        }
        this.memory = memory;
    }

    /** The sections of the book's text, in their order. */
    public MissionCheck sections(List<BookSection> values) {
        sections.addAll(values);
        return this;
    }

    /** The sentence check's verdicts; without them every cited sentence is unchecked and no topic is treated. */
    public MissionCheck sentences(SentenceLookup value) {
        lookup = value == null ? SentenceLookup.NONE : value;
        return this;
    }

    /** The section plan's fate per topic. */
    public MissionCheck placements(List<Placement> values) {
        placements.addAll(values);
        return this;
    }

    /** The contradiction check's pairs; an empty list says the check found none, leaving it out that none ran. */
    public MissionCheck contradictions(List<ContradictionRecord> values) {
        contradictions = new ArrayList<ContradictionRecord>(values);
        return this;
    }

    /** The source ratings by source id. */
    public MissionCheck sources(Map<String, SourceInfo> values) {
        sources.putAll(values);
        return this;
    }

    /** The length the mission asks for in words; 0 for a bound it does not name. */
    public MissionCheck requested(int minWords, int maxWords) {
        requestedMinWords = Math.max(0, minWords);
        requestedMaxWords = Math.max(0, maxWords);
        return this;
    }

    /** The report as the stored data stand now. */
    public MissionReport report(long timeMillis) {
        MissionReport.Counts all = new MissionReport.Counts();
        List<MissionReport.SectionLine> sectionLines = new ArrayList<MissionReport.SectionLine>();
        List<CitedSentence> cited = new ArrayList<CitedSentence>();
        Map<String, Integer> byKind = new HashMap<String, Integer>();
        Map<String, Integer> byJurisdiction = new HashMap<String, Integer>();
        Set<String> citedSources = new HashSet<String>();
        Map<String, String> titles = new HashMap<String, String>();
        int written = 0;
        int statedAsFact = 0;
        int foreignRuleUnmarked = 0;
        int unknownMarkers = 0;
        int citations = 0;
        for (BookSection section : sections) {
            titles.put(section.getSectionId(), section.getTitle().isEmpty() ? section.getSectionId()
                    : section.getTitle());
            MissionReport.Counts counts = new MissionReport.Counts();
            for (CitationValidator.Sentence sentence : CitationValidator.sentencesOf(section.getText())) {
                List<BookSection.Citation> passages = new ArrayList<BookSection.Citation>();
                for (int marker : sentence.getCited()) {
                    BookSection.Citation citation = section.citation(marker);
                    if (citation == null) {
                        unknownMarkers++;
                    } else {
                        passages.add(citation);
                    }
                }
                if (passages.isEmpty()) {
                    counts.add(null, false);
                    all.add(null, false);
                    continue;
                }
                SentenceLookup.State state = lookup.of(sentence.getText(), passages);
                if (state == null) {
                    state = SentenceLookup.State.UNCHECKED;
                }
                counts.add(state.getVerdict(), true);
                all.add(state.getVerdict(), true);
                statedAsFact += state.isStatedAsFact() ? 1 : 0;
                foreignRuleUnmarked += state.isForeignRuleUnmarked() ? 1 : 0;
                Set<String> keys = new HashSet<String>();
                Set<String> counted = new HashSet<String>();
                for (BookSection.Citation citation : passages) {
                    Set<String> ofPassage = keysOf(citation.getPassageId(), citation.getPassageText());
                    keys.addAll(ofPassage);
                    if (!Collections.disjoint(counted, ofPassage)) {
                        continue; // the same passage under two markers of one sentence counts once
                    }
                    counted.addAll(ofPassage);
                    citations++;
                    SourceInfo info = sources.get(citation.getSourceId());
                    add(byKind, info == null ? UNRATED : info.getKind());
                    if (info != null && !info.getJurisdiction().isEmpty()) {
                        add(byJurisdiction, info.getJurisdiction());
                    }
                    if (!citation.getSourceId().isEmpty()) {
                        citedSources.add(citation.getSourceId());
                    }
                }
                cited.add(new CitedSentence(state.getVerdict(), keys));
            }
            int words = words(section.getText());
            written += words;
            sectionLines.add(new MissionReport.SectionLine(section.getSectionId(), section.getTitle(),
                    section.getTargetWords(), words, counts));
        }

        Map<String, Placement> placementOf = new HashMap<String, Placement>();
        Map<String, Set<String>> planPassages = new HashMap<String, Set<String>>();
        List<String> unknownPlacements = new ArrayList<String>();
        for (Placement placement : placements) {
            ResearchTopic topic = memory.currentTopic(placement.getTopicId());
            if (topic == null) {
                unknownPlacements.add(placement.getTopicId());
                continue;
            }
            Placement earlier = placementOf.get(topic.getId());
            if (earlier == null || placement.getFate().ordinal() < earlier.getFate().ordinal()) {
                placementOf.put(topic.getId(), placement); // the best fate holds: own section, merged, not taken
            }
            Set<String> ids = planPassages.get(topic.getId());
            if (ids == null) {
                ids = new LinkedHashSet<String>();
                planPassages.put(topic.getId(), ids);
            }
            ids.addAll(placement.getPassageIds());
        }

        List<MissionReport.TopicLine> foundTopics = new ArrayList<MissionReport.TopicLine>();
        for (ResearchTopic topic : memory.topics()) {
            TopicEvidence evidence = memory.evidenceOfTopic(topic.getId());
            Set<String> keys = new HashSet<String>();
            for (String questionId : evidence.getQuestionIds()) {
                keys.addAll(evidenceKeys(memory.evidenceOf(questionId)));
            }
            Set<String> parts = new LinkedHashSet<String>(topic.getMergedFrom());
            parts.add(topic.getId());
            for (String id : parts) {
                ResearchTopic part = memory.topic(id);
                if (part != null) {
                    for (ResearchTopic.Finding finding : part.getFindings()) {
                        keys.addAll(keysOf(finding.getPassageId(), ""));
                    }
                }
            }
            Set<String> fromPlan = planPassages.get(topic.getId());
            for (String id : fromPlan == null ? Collections.<String>emptySet() : fromPlan) {
                keys.addAll(keysOf(id, ""));
            }
            MissionReport.Counts counts = new MissionReport.Counts();
            for (CitedSentence sentence : cited) {
                if (!Collections.disjoint(sentence.keys, keys)) {
                    counts.add(sentence.verdict, true);
                }
            }
            Placement placement = placementOf.get(topic.getId());
            String sectionTitle = "";
            if (placement != null && !placement.getSectionId().isEmpty()) {
                String title = titles.get(placement.getSectionId());
                sectionTitle = title != null ? title : placement.getSectionId() + ", nicht im Text";
            }
            MissionReport.TopicLine line = new MissionReport.TopicLine(topic, placement, sectionTitle,
                    evidence.getQuestionIds().size(), evidence.getDirectCount(), evidence.getLeadCount(),
                    evidence.getIndependentSources(), evidence.getFindings(), counts);
            foundTopics.add(line);
        }
        Collections.sort(foundTopics, Comparator.comparingInt(MissionCheck::group)); // stable: memory order inside

        List<MissionReport.QuestionLine> questions = new ArrayList<MissionReport.QuestionLine>();
        for (QuestionEvidence evidence : memory.all()) {
            Set<String> keys = evidenceKeys(evidence);
            int citing = 0;
            int covered = 0;
            for (CitedSentence sentence : cited) {
                if (!Collections.disjoint(sentence.keys, keys)) {
                    citing++;
                    covered += sentence.verdict == Verdict.GEDECKT ? 1 : 0;
                }
            }
            ResearchTopic topic = memory.currentTopic(evidence.getQuestion().getTopicId());
            questions.add(new MissionReport.QuestionLine(evidence, topic == null ? "" : topic.getName(), citing,
                    covered));
        }

        List<MissionReport.ContradictionLine> open = new ArrayList<MissionReport.ContradictionLine>();
        Map<String, Integer> states = new LinkedHashMap<String, Integer>();
        for (ContradictionRecord record : contradictions == null ? Collections.<ContradictionRecord>emptyList()
                : contradictions) {
            if (record.isOpen()) {
                open.add(new MissionReport.ContradictionLine(record, scopeName(record)));
            } else {
                add(states, record.otherState());
            }
        }

        return new MissionReport(timeMillis, requestedMinWords, requestedMaxWords, written, sectionLines, all,
                statedAsFact, foreignRuleUnmarked, unknownMarkers, foundTopics, unknownPlacements,
                questions, open, states, contradictions != null, citations, byCount(byKind),
                byCount(byJurisdiction), citedSources.size());
    }

    /** The order of found topics in the report: placed, merged, left out, outside the fence, unclear, no fate. */
    private static int group(MissionReport.TopicLine line) {
        if (line.getPlacement() != null) {
            return line.getPlacement().getFate().ordinal();
        }
        ResearchTopic.Fence fence = line.getTopic().getFence();
        return fence == ResearchTopic.Fence.AUSSEN ? 3 : fence == ResearchTopic.Fence.UNKLAR ? 4 : 5;
    }

    private String scopeName(ContradictionRecord record) {
        if ("THEMA".equals(record.getScope())) {
            ResearchTopic topic = memory.currentTopic(record.getScopeId());
            return topic == null ? record.getScopeId() : topic.getName();
        }
        ResearchQuestion question = memory.currentQuestion(record.getScopeId());
        return question == null ? record.getScopeId() : question.getText();
    }

    /** The keys of the passages a question's direct evidence and leads rest on. */
    private static Set<String> evidenceKeys(QuestionEvidence evidence) {
        Set<String> keys = new HashSet<String>();
        if (evidence == null) {
            return keys;
        }
        List<EvidenceVerdict> verdicts = new ArrayList<EvidenceVerdict>(evidence.getDirect());
        verdicts.addAll(evidence.getLeads());
        for (EvidenceVerdict verdict : verdicts) {
            if (!verdict.getPassageId().isEmpty()) {
                keys.add("id:" + verdict.getPassageId());
            }
            if (!verdict.getTextKey().startsWith("passage:")) {
                keys.add("text:" + verdict.getTextKey());
            }
        }
        return keys;
    }

    /** A passage is known by its id and by the key of its text, either matches. */
    static Set<String> keysOf(String passageId, String passageText) {
        Set<String> keys = new HashSet<String>();
        String id = BookSection.clean(passageId);
        if (!id.isEmpty()) {
            keys.add("id:" + id);
        }
        String key = PassageText.key(passageText == null ? "" : passageText);
        if (!key.isEmpty()) {
            keys.add("text:" + key);
        }
        return keys;
    }

    /** The words of a text, markers not counted: runs without blanks that hold a letter or a digit. */
    static int words(String text) {
        int count = 0;
        for (String token : MARKER.matcher(text == null ? "" : text).replaceAll(" ").split("\\s+")) {
            if (token.codePoints().anyMatch(Character::isLetterOrDigit)) {
                count++;
            }
        }
        return count;
    }

    private static void add(Map<String, Integer> counts, String key) {
        Integer count = counts.get(key);
        counts.put(key, count == null ? 1 : count + 1);
    }

    /** The counts, the largest first and the name breaking a tie. */
    private static Map<String, Integer> byCount(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(entries, (a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey())
                : b.getValue() - a.getValue());
        Map<String, Integer> sorted = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    private static String upper(String value) {
        return BookSection.clean(value).toUpperCase(Locale.ROOT);
    }
}
