package com.aresstack.writing4j.memory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Component 7, the research memory: topics, questions, evidence verdicts, searches, the research lead's decisions and
 * the contradictions between pieces of evidence (component 17) of one project, with the lists and counts the other
 * components work from. It holds no model and makes no call.
 * <p>
 * It records what was found and claims nothing beyond it: it never says a question is answered or complete. Whether
 * evidence is enough is the research lead's call (component 8).
 * <p>
 * Nothing is deleted. Every record is appended; a record with the id of an earlier one (a topic, a question, a
 * search, a contradiction) or with the same question and passage (a verdict) replaces it when the memory is read, and
 * the earlier one stays in the log. Records that were merged into another (topics, questions) stay readable by their
 * id; their evidence counts for the record they were merged into. Safe for parallel callers.
 * <p>
 * Verdicts on page leftovers ({@link #useNoise}: advertising, tables of contents, imprint, ...) stay in the log but
 * count nowhere: not as evidence, not as a lead, not as a source and not as a passage to judge again.
 */
public final class ResearchMemory {

    /** Where the memory writes what it takes in, before it takes it in. */
    public interface Log {
        void topic(ResearchTopic topic) throws IOException;

        void question(ResearchQuestion question) throws IOException;

        void verdict(EvidenceVerdict verdict) throws IOException;

        void search(SearchRecord search) throws IOException;

        void decision(LeadDecision decision) throws IOException;

        void contradiction(Contradiction contradiction) throws IOException;
    }

    /** A log that keeps nothing (a memory only in RAM, for tests and dry runs). */
    public static final Log NO_LOG = new Log() {
        @Override
        public void topic(ResearchTopic topic) {
        }

        @Override
        public void question(ResearchQuestion question) {
        }

        @Override
        public void verdict(EvidenceVerdict verdict) {
        }

        @Override
        public void search(SearchRecord search) {
        }

        @Override
        public void decision(LeadDecision decision) {
        }

        @Override
        public void contradiction(Contradiction contradiction) {
        }
    };

    /** A verdict with its place in the log, so "latest" is well defined across merged questions. */
    private static final class Stamped {
        final EvidenceVerdict verdict;
        final long seq;

        Stamped(EvidenceVerdict verdict, long seq) {
            this.verdict = verdict;
            this.seq = seq;
        }
    }

    private final Log log;
    private long seq;
    private final Map<String, ResearchTopic> topics = new LinkedHashMap<String, ResearchTopic>();
    private final Map<String, ResearchQuestion> questions = new LinkedHashMap<String, ResearchQuestion>();
    private final Map<String, Map<String, Stamped>> verdicts = new HashMap<String, Map<String, Stamped>>();
    /**
     * Issue #286: per question, the first round a text was judged direct evidence (BELEGT). Never overwritten by a
     * later verdict on the same passage, so judging a text again does not make it look new.
     */
    private final Map<String, Map<String, Integer>> firstDirectRound = new HashMap<String, Map<String, Integer>>();
    private final Map<String, SearchRecord> searches = new LinkedHashMap<String, SearchRecord>();
    private final List<LeadDecision> decisions = new ArrayList<LeadDecision>();
    private final Map<String, Contradiction> contradictions = new LinkedHashMap<String, Contradiction>();
    private PassageNoise noise = PassageNoise.NONE;

    public ResearchMemory() {
        this(NO_LOG);
    }

    public ResearchMemory(Log log) {
        this.log = log == null ? NO_LOG : log;
    }

    /**
     * Which passages are page leftovers (the marks of the page-noise check); {@code null} is
     * {@link PassageNoise#NONE}. Verdicts on them count nowhere from now on; the log keeps them.
     */
    public synchronized void useNoise(PassageNoise noise) {
        this.noise = noise == null ? PassageNoise.NONE : noise;
    }

    // ----------------------------------------------------------------------------------------------- writing

    /**
     * Take a new topic in.
     *
     * @throws IllegalArgumentException when the id is taken, or a merged id is unknown
     */
    public synchronized void addTopic(ResearchTopic topic) throws IOException {
        if (topics.containsKey(topic.getId())) {
            throw new IllegalArgumentException("topic id " + topic.getId() + " is taken");
        }
        checkTopicMerge(topic);
        log.topic(topic);
        topics.put(topic.getId(), topic);
    }

    /**
     * Take a changed topic in (fence verdict, merge, weight, parent). The id must be known. A topic that takes its own
     * parent in must name the parent's parent (or none) in the same change, or it would be a subtopic of itself.
     *
     * @throws IllegalArgumentException when the id is unknown, a merged id or the parent is unknown, or the change
     *                                  makes a circle of merges or of parents
     */
    public synchronized void updateTopic(ResearchTopic topic) throws IOException {
        if (!topics.containsKey(topic.getId())) {
            throw new IllegalArgumentException("no topic " + topic.getId());
        }
        checkTopicMerge(topic);
        log.topic(topic);
        topics.put(topic.getId(), topic);
    }

    /**
     * Take a new question in. A question worded like one the memory holds (letters and digits compared, case ignored)
     * does not stand on its own: it is kept as its own record, so its origin and topic are not lost, and merged into
     * the question held, so evidence stays in one place. The same question with the same id again changes nothing.
     * Questions of the same meaning in other words are the merge's to find (component 15), not the memory's.
     *
     * @return the question that stands for this wording: the new one, or the one held (with the new one merged in)
     * @throws IllegalArgumentException when the id is taken by another question, the topic or a merged id is unknown
     */
    public synchronized ResearchQuestion addQuestion(ResearchQuestion question) throws IOException {
        ResearchQuestion sameWording = withWording(question.getText());
        if (sameWording != null && sameWording.getId().equals(question.getId())) {
            return sameWording;
        }
        if (questions.containsKey(question.getId())) {
            throw new IllegalArgumentException("question id " + question.getId() + " is taken");
        }
        checkQuestion(question);
        log.question(question);
        questions.put(question.getId(), question);
        ensureVerdictMap(question.getId());
        if (sameWording == null) {
            return question;
        }
        ResearchQuestion held = sameWording.withMerged(Collections.singletonList(question),
                question.getTimeMillis());
        log.question(held);
        questions.put(held.getId(), held);
        return held;
    }

    /**
     * Take a changed question in (state, topic, merge). The id must be known.
     *
     * @throws IllegalArgumentException when the id, the topic or a merged id is unknown
     */
    public synchronized void updateQuestion(ResearchQuestion question) throws IOException {
        if (!questions.containsKey(question.getId())) {
            throw new IllegalArgumentException("no question " + question.getId());
        }
        checkQuestion(question);
        log.question(question);
        questions.put(question.getId(), question);
    }

    /**
     * Take a verdict in. A later verdict on the same question and passage replaces the earlier one.
     *
     * @throws IllegalArgumentException when the memory holds no question with the verdict's question id
     */
    public synchronized void record(EvidenceVerdict verdict) throws IOException {
        if (!questions.containsKey(verdict.getQuestionId())) {
            throw new IllegalArgumentException("no question " + verdict.getQuestionId());
        }
        log.verdict(verdict);
        put(verdict);
    }

    /**
     * Take a search in, new or with its yield. Every question and topic it names must be known.
     *
     * @throws IllegalArgumentException when it names an unknown question or topic
     */
    public synchronized void record(SearchRecord search) throws IOException {
        for (String id : search.getQuestionIds()) {
            if (!questions.containsKey(id)) {
                throw new IllegalArgumentException("search " + search.getId() + " names no known question " + id);
            }
        }
        for (String id : search.getTopicIds()) {
            if (!topics.containsKey(id)) {
                throw new IllegalArgumentException("search " + search.getId() + " names no known topic " + id);
            }
        }
        log.search(search);
        searches.put(search.getId(), search);
    }

    /**
     * Take a decision of the research lead in. Its target must be known. The decision changes nothing by itself: the
     * lead sets a question aside with {@link #updateQuestion}.
     *
     * @throws IllegalArgumentException when it targets an unknown question or topic
     */
    public synchronized void record(LeadDecision decision) throws IOException {
        if (decision.getTarget() == LeadDecision.Target.FRAGE && !questions.containsKey(decision.getTargetId())) {
            throw new IllegalArgumentException("no question " + decision.getTargetId());
        }
        if (decision.getTarget() == LeadDecision.Target.THEMA && !topics.containsKey(decision.getTargetId())) {
            throw new IllegalArgumentException("no topic " + decision.getTargetId());
        }
        log.decision(decision);
        decisions.add(decision);
    }

    /**
     * Take a verdict of the contradiction check in (component 17), or a new state of one. Its question or topic and
     * the questions of both sides must be known.
     *
     * @throws IllegalArgumentException when it names an unknown question or topic
     */
    public synchronized void record(Contradiction contradiction) throws IOException {
        if (contradiction.getScope() == Contradiction.Scope.FRAGE
                && !questions.containsKey(contradiction.getScopeId())) {
            throw new IllegalArgumentException("no question " + contradiction.getScopeId());
        }
        if (contradiction.getScope() == Contradiction.Scope.THEMA && !topics.containsKey(contradiction.getScopeId())) {
            throw new IllegalArgumentException("no topic " + contradiction.getScopeId());
        }
        for (Contradiction.Side side : new Contradiction.Side[]{contradiction.getA(), contradiction.getB()}) {
            if (!questions.containsKey(side.getQuestionId())) {
                throw new IllegalArgumentException("contradiction " + contradiction.getId()
                        + " names no known question " + side.getQuestionId());
            }
        }
        log.contradiction(contradiction);
        contradictions.put(contradiction.getId(), contradiction);
    }

    // ------------------------------------------------------------------------- replay (no checks, no log)

    synchronized void restore(ResearchTopic topic) {
        topics.put(topic.getId(), topic);
    }

    synchronized void restore(ResearchQuestion question) {
        questions.put(question.getId(), question);
        ensureVerdictMap(question.getId());
    }

    synchronized void restore(EvidenceVerdict verdict) {
        if (!questions.containsKey(verdict.getQuestionId())) {
            throw new IllegalArgumentException("verdict on unknown question " + verdict.getQuestionId());
        }
        put(verdict);
    }

    synchronized void restore(SearchRecord search) {
        searches.put(search.getId(), search);
    }

    synchronized void restore(LeadDecision decision) {
        decisions.add(decision);
    }

    synchronized void restore(Contradiction contradiction) {
        contradictions.put(contradiction.getId(), contradiction);
    }

    // ----------------------------------------------------------------------------------------------- reading

    /** The topics that stand on their own, in the order they came in: merged-away topics left out. */
    public synchronized List<ResearchTopic> topics() {
        Set<String> mergedAway = mergedAwayTopics();
        List<ResearchTopic> list = new ArrayList<ResearchTopic>();
        for (ResearchTopic topic : topics.values()) {
            if (!mergedAway.contains(topic.getId())) {
                list.add(topic);
            }
        }
        return list;
    }

    /** The latest record of a topic id, merged away or not; {@code null} when unknown. */
    public synchronized ResearchTopic topic(String topicId) {
        return topics.get(topicId);
    }

    /** The topic a topic id stands in now: itself, or the topic it was merged into. {@code null} when unknown. */
    public synchronized ResearchTopic currentTopic(String topicId) {
        return resolve(topicId, topics, mergedIntoTopics());
    }

    /**
     * The topic a topic is a subtopic of, as it stands now: the parent its current record names, resolved through the
     * merges like {@link #currentTopic}, so a subtopic whose parent was merged into another hangs on that one.
     * {@code null} for an unknown id or a topic without parent.
     */
    public synchronized ResearchTopic parentOf(String topicId) {
        ResearchTopic topic = currentTopic(topicId);
        if (topic == null || topic.getParentId().isEmpty()) {
            return null;
        }
        ResearchTopic parent = currentTopic(topic.getParentId());
        return parent == null || parent.getId().equals(topic.getId()) ? null : parent;
    }

    /** The questions that stand on their own, open or set aside, in the order they came in. */
    public synchronized List<ResearchQuestion> questions() {
        Set<String> mergedAway = mergedIntoQuestions().keySet();
        List<ResearchQuestion> list = new ArrayList<ResearchQuestion>();
        for (ResearchQuestion question : questions.values()) {
            if (!mergedAway.contains(question.getId())) {
                list.add(question);
            }
        }
        return list;
    }

    /** The questions the research still works on. */
    public synchronized List<ResearchQuestion> openQuestions() {
        List<ResearchQuestion> list = new ArrayList<ResearchQuestion>();
        for (ResearchQuestion question : questions()) {
            if (question.getState() == ResearchQuestion.State.OFFEN) {
                list.add(question);
            }
        }
        return list;
    }

    /** The latest record of a question id, merged away or not; {@code null} when unknown. */
    public synchronized ResearchQuestion question(String questionId) {
        return questions.get(questionId);
    }

    /** The question a question id stands in now: itself, or the one it was merged into. */
    public synchronized ResearchQuestion currentQuestion(String questionId) {
        return resolve(questionId, questions, mergedIntoQuestions());
    }

    /** The verdict that holds for a question id and a passage, or {@code null} when none was recorded. */
    public synchronized EvidenceVerdict verdict(String questionId, String passageId) {
        Map<String, Stamped> ofQuestion = verdicts.get(questionId);
        Stamped stamped = ofQuestion == null ? null : ofQuestion.get(passageId);
        return stamped == null ? null : stamped.verdict;
    }

    /**
     * {@code true} when the passage holds a verdict for the question (or for a question merged into it) that is not
     * FEHLER, so the judge need not read it again.
     */
    public synchronized boolean isJudged(String questionId, String passageId) {
        Stamped stamped = currentVerdicts(questionId).get(passageId);
        return stamped != null && stamped.verdict.getKind() != EvidenceVerdict.Kind.FEHLER;
    }

    /**
     * A verdict the judge gave this question on the same text in another passage, to be reused instead of a new
     * call; {@code null} when there is none. FEHLER is never reused.
     */
    public synchronized EvidenceVerdict verdictOnSameText(String questionId, String passageText) {
        String key = PassageText.key(passageText);
        if (key.isEmpty()) {
            return null;
        }
        EvidenceVerdict found = null;
        long latest = -1;
        for (Stamped stamped : currentVerdicts(questionId).values()) {
            if (stamped.verdict.getKind() != EvidenceVerdict.Kind.FEHLER && key.equals(stamped.verdict.getTextKey())
                    && stamped.seq > latest) {
                found = stamped.verdict;
                latest = stamped.seq;
            }
        }
        return found;
    }

    /**
     * Every passage whose latest verdict is FEHLER, oldest first, to be offered to the judge again. A question merged
     * into another counts through the question it was merged into: a FEHLER that a later verdict on the same passage
     * replaced, under either question, is not offered again; nor is a page leftover.
     */
    public synchronized List<EvidenceVerdict> toRetry() {
        List<Stamped> failed = new ArrayList<Stamped>();
        for (ResearchQuestion question : questions()) {
            for (Stamped stamped : currentVerdicts(question.getId()).values()) {
                if (stamped.verdict.getKind() == EvidenceVerdict.Kind.FEHLER && !leftover(stamped)) {
                    failed.add(stamped);
                }
            }
        }
        Collections.sort(failed, (a, b) -> Long.compare(a.seq, b.seq));
        List<EvidenceVerdict> list = new ArrayList<EvidenceVerdict>();
        for (Stamped stamped : failed) {
            list.add(stamped.verdict);
        }
        return list;
    }

    /**
     * What the memory holds for a question (the verdicts of questions merged into it included, those on page
     * leftovers left out), or {@code null} for an unknown id.
     */
    public synchronized QuestionEvidence evidenceOf(String questionId) {
        ResearchQuestion question = questions.get(questionId);
        if (question == null) {
            return null;
        }
        List<Stamped> current = new ArrayList<Stamped>(currentVerdicts(questionId).values());
        current.removeIf(this::leftover);
        Collections.sort(current, (a, b) -> Long.compare(a.seq, b.seq));
        List<EvidenceVerdict> direct = new ArrayList<EvidenceVerdict>();
        List<EvidenceVerdict> allDirect = new ArrayList<EvidenceVerdict>();
        Map<String, Integer> firstRoundOfText = new HashMap<String, Integer>();
        int judged = 0;
        int errors = 0;
        int downgraded = 0;
        for (Stamped stamped : current) {
            EvidenceVerdict verdict = stamped.verdict;
            if (verdict.getKind() == EvidenceVerdict.Kind.FEHLER) {
                errors++;
                continue;
            }
            judged++;
            if (verdict.isDowngraded()) {
                downgraded++;
            }
            if (verdict.getKind() == EvidenceVerdict.Kind.BELEGT) {
                allDirect.add(verdict);
                Integer first = firstRoundOfText.get(verdict.getTextKey());
                if (first == null) {
                    direct.add(verdict);
                }
                int round = first == null ? verdict.getRound() : Math.min(first, verdict.getRound());
                // #286: a text judged again in a later round keeps the round it was first found in
                int found = firstDirectRoundOf(questionId, verdict.getTextKey());
                firstRoundOfText.put(verdict.getTextKey(), found > 0 ? Math.min(found, round) : round);
            }
        }
        List<EvidenceVerdict> leads = leadsOf(current, firstRoundOfText.keySet());
        int lastNewRound = 0;
        for (int round : firstRoundOfText.values()) {
            lastNewRound = Math.max(lastNewRound, round);
        }
        Set<Integer> laterRounds = new TreeSet<Integer>();
        for (Stamped stamped : current) {
            if (stamped.verdict.getKind() != EvidenceVerdict.Kind.FEHLER && stamped.verdict.getRound() > lastNewRound) {
                laterRounds.add(stamped.verdict.getRound());
            }
        }
        return new QuestionEvidence(question, direct, leads, independentSources(allDirect), distinctDomains(allDirect),
                judged, errors, downgraded, lastNewRound, laterRounds.size(), searchesWithoutNewEvidence(questionId));
    }

    /** Every question that stands on its own with what the memory holds for it. */
    public synchronized List<QuestionEvidence> all() {
        List<QuestionEvidence> all = new ArrayList<QuestionEvidence>();
        for (ResearchQuestion question : questions()) {
            all.add(evidenceOf(question.getId()));
        }
        return all;
    }

    /**
     * What the memory holds for a topic over all its questions (those of topics merged into it included, verdicts on
     * page leftovers left out), or {@code null} for an unknown id.
     */
    public synchronized TopicEvidence evidenceOfTopic(String topicId) {
        ResearchTopic topic = topics.get(topicId);
        if (topic == null) {
            return null;
        }
        Set<String> topicIds = new HashSet<String>(closure(topicId, topicMergeMap()));
        // #285: a question belongs to the topic of every stored entry merged into it, so a question of this topic
        // that was merged into a question of another topic still counts here, resolved to the question that holds
        // its verdicts and counted once.
        Map<String, String> mergedInto = mergedIntoQuestions();
        Set<String> effective = new LinkedHashSet<String>();
        for (ResearchQuestion question : questions.values()) {
            if (topicIds.contains(question.getTopicId())) {
                effective.add(effectiveQuestion(question.getId(), mergedInto));
            }
        }
        List<String> questionIds = new ArrayList<String>();
        Map<String, Stamped> byKey = new LinkedHashMap<String, Stamped>();
        for (String id : effective) {
            if (!questions.containsKey(id)) {
                continue;
            }
            questionIds.add(id);
            for (Stamped stamped : currentVerdicts(id).values()) {
                byKey.put(id + "\u0000" + stamped.verdict.getPassageId(), stamped);
            }
        }
        List<Stamped> current = new ArrayList<Stamped>(byKey.values());
        current.removeIf(this::leftover);
        Collections.sort(current, (a, b) -> Long.compare(a.seq, b.seq));
        List<EvidenceVerdict> allDirect = new ArrayList<EvidenceVerdict>();
        Set<String> directTexts = new LinkedHashSet<String>();
        for (Stamped stamped : current) {
            if (stamped.verdict.getKind() == EvidenceVerdict.Kind.BELEGT) {
                allDirect.add(stamped.verdict);
                directTexts.add(stamped.verdict.getTextKey());
            }
        }
        Set<String> findings = new HashSet<String>();
        for (String id : topicIds) {
            ResearchTopic part = topics.get(id);
            if (part != null) {
                for (ResearchTopic.Finding finding : part.getFindings()) {
                    findings.add(finding.getSourceId() + "\u0000" + finding.getPassageId() + "\u0000"
                            + Texts.wording(finding.getQuote()));
                }
            }
        }
        return new TopicEvidence(topic, questionIds, directTexts.size(), leadsOf(current, directTexts).size(),
                independentSources(allDirect), distinctDomains(allDirect), findings.size());
    }

    /**
     * Every place a topic was found (Fundstellen), its own and those of the topics merged into it, each place once,
     * in the order the records came in; empty for an unknown id.
     */
    public synchronized List<ResearchTopic.Finding> findingsOfTopic(String topicId) {
        List<ResearchTopic.Finding> all = new ArrayList<ResearchTopic.Finding>();
        if (!topics.containsKey(topicId)) {
            return all;
        }
        Set<String> parts = new HashSet<String>(closure(topicId, topicMergeMap()));
        Set<String> seen = new HashSet<String>();
        for (ResearchTopic part : topics.values()) {
            if (!parts.contains(part.getId())) {
                continue;
            }
            for (ResearchTopic.Finding finding : part.getFindings()) {
                if (seen.add(finding.getSourceId() + "\u0000" + finding.getPassageId() + "\u0000"
                        + Texts.wording(finding.getQuote()))) {
                    all.add(finding);
                }
            }
        }
        return all;
    }

    /** Every search, in the order it came in, with its latest record. */
    public synchronized List<SearchRecord> searches() {
        return new ArrayList<SearchRecord>(searches.values());
    }

    /** The searches that name a question (or a question merged into it), in the order they came in. */
    public synchronized List<SearchRecord> searchesFor(String questionId) {
        Set<String> ids = new HashSet<String>(closure(questionId, questionMergeMap()));
        List<SearchRecord> list = new ArrayList<SearchRecord>();
        for (SearchRecord search : searches.values()) {
            for (String id : search.getQuestionIds()) {
                if (ids.contains(id)) {
                    list.add(search);
                    break;
                }
            }
        }
        return list;
    }

    /** Every decision of the research lead, in the order it was made. */
    public synchronized List<LeadDecision> decisions() {
        return new ArrayList<LeadDecision>(decisions);
    }

    /** Every pair the contradiction check took up, with its latest record, in the order they first came in. */
    public synchronized List<Contradiction> contradictions() {
        return new ArrayList<Contradiction>(contradictions.values());
    }

    /** The latest record of a contradiction id, or {@code null}. */
    public synchronized Contradiction contradiction(String contradictionId) {
        return contradictions.get(contradictionId);
    }

    /**
     * The open contradictions (verdict WIDERSPRUCH, not shown in the text, not decided) whose two sides are still
     * direct evidence: a pair whose evidence was judged again and no longer holds is not offered.
     */
    public synchronized List<Contradiction> openContradictions() {
        List<Contradiction> open = new ArrayList<Contradiction>();
        for (Contradiction contradiction : contradictions.values()) {
            if (contradiction.isOpen() && stillDirect(contradiction.getA()) && stillDirect(contradiction.getB())) {
                open.add(contradiction);
            }
        }
        return open;
    }

    /**
     * The open contradictions on a question or a topic: on the question (or a question merged into it) or with a side
     * on it; on the topic (or a topic merged into it) or with a side on one of its questions.
     */
    public synchronized List<Contradiction> openContradictionsOf(String questionOrTopicId) {
        Set<String> scopes = new HashSet<String>();
        Set<String> sideQuestions = new HashSet<String>();
        if (questions.containsKey(questionOrTopicId)) {
            scopes.addAll(closure(questionOrTopicId, questionMergeMap()));
            sideQuestions.addAll(scopes);
        } else if (topics.containsKey(questionOrTopicId)) {
            scopes.addAll(closure(questionOrTopicId, topicMergeMap()));
            for (ResearchQuestion question : questions.values()) {
                if (scopes.contains(question.getTopicId())) {
                    sideQuestions.add(question.getId());
                }
            }
        } else {
            return new ArrayList<Contradiction>();
        }
        List<Contradiction> found = new ArrayList<Contradiction>();
        for (Contradiction contradiction : openContradictions()) {
            if (scopes.contains(contradiction.getScopeId())
                    || sideQuestions.contains(contradiction.getA().getQuestionId())
                    || sideQuestions.contains(contradiction.getB().getQuestionId())) {
                found.add(contradiction);
            }
        }
        return found;
    }

    /**
     * The memory as a plain list: per question the direct evidence and the leads under their own headings, or
     * "Kein Beleg gefunden".
     */
    public synchronized String report() {
        StringBuilder out = new StringBuilder();
        for (QuestionEvidence evidence : all()) {
            ResearchQuestion question = evidence.getQuestion();
            out.append(question.getId()).append("  ").append(question.getText());
            if (question.getState() == ResearchQuestion.State.ZURUECKGESTELLT) {
                out.append("  (zurückgestellt)");
            }
            out.append('\n');
            if (evidence.getDirectCount() == 0 && evidence.getLeadCount() == 0) {
                out.append("  ").append(QuestionEvidence.LABEL_NONE).append(" (")
                        .append(evidence.getJudgedPassages()).append(" Passagen geprüft)\n");
            }
            appendGroup(out, QuestionEvidence.LABEL_DIRECT, evidence.getDirect());
            appendGroup(out, QuestionEvidence.LABEL_LEAD, evidence.getLeads());
            if (evidence.getErrors() > 0) {
                out.append("  Fehler: ").append(evidence.getErrors()).append(" Passagen, werden erneut geprüft\n");
            }
        }
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private void put(EvidenceVerdict verdict) {
        ensureVerdictMap(verdict.getQuestionId()).put(verdict.getPassageId(), new Stamped(verdict, ++seq));
        if (verdict.getKind() == EvidenceVerdict.Kind.BELEGT) {
            Map<String, Integer> first = firstDirectRound.get(verdict.getQuestionId());
            if (first == null) {
                first = new HashMap<String, Integer>();
                firstDirectRound.put(verdict.getQuestionId(), first);
            }
            Integer held = first.get(verdict.getTextKey());
            if (held == null || verdict.getRound() < held) {
                first.put(verdict.getTextKey(), verdict.getRound());
            }
        }
    }

    /** The first round a text was direct evidence for the question or a question merged into it; 0 when never. */
    private int firstDirectRoundOf(String questionId, String textKey) {
        int first = 0;
        for (String id : closure(questionId, questionMergeMap())) {
            Map<String, Integer> ofQuestion = firstDirectRound.get(id);
            Integer round = ofQuestion == null ? null : ofQuestion.get(textKey);
            if (round != null && (first == 0 || round < first)) {
                first = round;
            }
        }
        return first;
    }

    private Map<String, Stamped> ensureVerdictMap(String questionId) {
        Map<String, Stamped> ofQuestion = verdicts.get(questionId);
        if (ofQuestion == null) {
            ofQuestion = new LinkedHashMap<String, Stamped>();
            verdicts.put(questionId, ofQuestion);
        }
        return ofQuestion;
    }

    /** Whether a verdict stands on a page leftover. */
    private boolean leftover(Stamped stamped) {
        return noise.isNoise(stamped.verdict.getTextKey());
    }

    /** The verdict that holds per passage over the question and every question merged into it. */
    private Map<String, Stamped> currentVerdicts(String questionId) {
        Map<String, Stamped> byPassage = new HashMap<String, Stamped>();
        for (String id : closure(questionId, questionMergeMap())) {
            Map<String, Stamped> ofQuestion = verdicts.get(id);
            if (ofQuestion == null) {
                continue;
            }
            for (Map.Entry<String, Stamped> entry : ofQuestion.entrySet()) {
                Stamped held = byPassage.get(entry.getKey());
                if (held == null || entry.getValue().seq > held.seq) {
                    byPassage.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return byPassage;
    }

    private static List<EvidenceVerdict> leadsOf(List<Stamped> ordered, Set<String> directTexts) {
        List<EvidenceVerdict> leads = new ArrayList<EvidenceVerdict>();
        Set<String> leadTexts = new HashSet<String>();
        for (Stamped stamped : ordered) {
            EvidenceVerdict verdict = stamped.verdict;
            if (verdict.getKind() == EvidenceVerdict.Kind.HINWEIS && !directTexts.contains(verdict.getTextKey())
                    && leadTexts.add(verdict.getTextKey())) {
                leads.add(verdict);
            }
        }
        return leads;
    }

    private int searchesWithoutNewEvidence(String questionId) {
        int without = 0;
        for (SearchRecord search : searchesFor(questionId)) {
            SearchRecord.Yield yield = search.getYield();
            if (yield == null) {
                continue;
            }
            without = yield.getNewEvidence() > 0 ? 0 : without + 1;
        }
        return without;
    }

    /**
     * Independent sources among direct evidence: sources that carry the same text are one group, whatever domain
     * they sit on (the same law on two portals, a press release copied by others); the same source id is always one
     * source. Architect's measurement of 21.11.: the same text always counted as one source group.
     */
    private static int independentSources(List<EvidenceVerdict> direct) {
        return new HashSet<String>(groups(direct).values()).size();
    }

    /** The source group of each source of these verdicts: sources that carry the same text are one group. */
    private static Map<String, String> groups(List<EvidenceVerdict> direct) {
        Map<String, String> parent = new LinkedHashMap<String, String>();
        Map<String, String> sourceOfText = new HashMap<String, String>();
        for (EvidenceVerdict verdict : direct) {
            parent.put(verdict.getSourceId(), verdict.getSourceId());
        }
        for (EvidenceVerdict verdict : direct) {
            String first = sourceOfText.get(verdict.getTextKey());
            if (first == null) {
                sourceOfText.put(verdict.getTextKey(), verdict.getSourceId());
            } else {
                parent.put(root(parent, verdict.getSourceId()), root(parent, first));
            }
        }
        Map<String, String> groups = new LinkedHashMap<String, String>();
        for (String source : parent.keySet()) {
            groups.put(source, root(parent, source));
        }
        return groups;
    }

    /**
     * The source group of every source with direct evidence on these questions (their merged questions included),
     * by source id: sources that carry the same text are one group, the same rule as the count of independent
     * sources. Two pieces of evidence from one group are no two voices (component 17 compares only across groups).
     */
    public synchronized Map<String, String> sourceGroups(Collection<String> questionIds) {
        List<EvidenceVerdict> direct = new ArrayList<EvidenceVerdict>();
        for (String questionId : questionIds) {
            for (Stamped stamped : currentVerdicts(questionId).values()) {
                if (stamped.verdict.getKind() == EvidenceVerdict.Kind.BELEGT) {
                    direct.add(stamped.verdict);
                }
            }
        }
        return groups(direct);
    }

    /**
     * Distinct domains among direct evidence ({@link SourceDomain}); a source without an address counts as a domain of
     * its own.
     */
    private static int distinctDomains(List<EvidenceVerdict> direct) {
        Set<String> domains = new HashSet<String>();
        for (EvidenceVerdict verdict : direct) {
            String domain = verdict.getSourceDomain();
            domains.add(domain.isEmpty() ? "source:" + verdict.getSourceId() : domain);
        }
        return domains.size();
    }

    private static String root(Map<String, String> parent, String source) {
        String at = source;
        while (!parent.get(at).equals(at)) {
            at = parent.get(at);
        }
        return at;
    }

    private ResearchQuestion withWording(String text) {
        String wording = Texts.wording(text);
        Set<String> mergedAway = mergedIntoQuestions().keySet();
        for (ResearchQuestion held : questions.values()) {
            if (!mergedAway.contains(held.getId()) && Texts.wording(held.getText()).equals(wording)) {
                return held;
            }
        }
        return null;
    }

    private void checkQuestion(ResearchQuestion question) {
        if (!question.getTopicId().isEmpty() && !topics.containsKey(question.getTopicId())) {
            throw new IllegalArgumentException("question " + question.getId() + " names no known topic "
                    + question.getTopicId());
        }
        for (String id : question.getMergedFrom()) {
            if (!questions.containsKey(id)) {
                throw new IllegalArgumentException("question " + question.getId() + " merges unknown question " + id);
            }
        }
        Map<String, List<String>> merges = questionMergeMap();
        merges.put(question.getId(), question.getMergedFrom());
        checkNoCycle(question.getId(), merges, "question");
    }

    private void checkTopicMerge(ResearchTopic topic) {
        for (String id : topic.getMergedFrom()) {
            if (!topics.containsKey(id)) {
                throw new IllegalArgumentException("topic " + topic.getId() + " merges unknown topic " + id);
            }
        }
        Map<String, List<String>> merges = topicMergeMap();
        merges.put(topic.getId(), topic.getMergedFrom());
        checkNoCycle(topic.getId(), merges, "topic");
        String parent = topic.getParentId();
        if (parent.isEmpty()) {
            return;
        }
        if (!topics.containsKey(parent)) {
            throw new IllegalArgumentException("topic " + topic.getId() + " names no known parent topic " + parent);
        }
        // The chain as it will stand: every parent resolved through the merges after this change, so a topic that
        // takes its own parent in is caught (it must take the parent's parent in the same change).
        Map<String, ResearchTopic> after = new LinkedHashMap<String, ResearchTopic>(topics);
        after.put(topic.getId(), topic);
        Map<String, String> into = new HashMap<String, String>();
        for (ResearchTopic record : after.values()) {
            for (String id : record.getMergedFrom()) {
                into.put(id, record.getId());
            }
        }
        Set<String> seen = new HashSet<String>();
        for (String at = resolveId(parent, into); !at.isEmpty() && seen.add(at); ) {
            if (at.equals(topic.getId())) {
                throw new IllegalArgumentException("topic " + topic.getId() + " would be a subtopic of itself");
            }
            ResearchTopic above = after.get(at);
            at = above == null || above.getParentId().isEmpty() ? "" : resolveId(above.getParentId(), into);
        }
    }

    private static String resolveId(String id, Map<String, String> into) {
        String at = id;
        Set<String> seen = new HashSet<String>();
        while (into.containsKey(at) && seen.add(at)) {
            at = into.get(at);
        }
        return at;
    }

    private static void checkNoCycle(String start, Map<String, List<String>> merges, String kind) {
        List<String> stack = new ArrayList<String>(merges.containsKey(start) ? merges.get(start)
                : Collections.<String>emptyList());
        Set<String> seen = new HashSet<String>();
        while (!stack.isEmpty()) {
            String id = stack.remove(stack.size() - 1);
            if (id.equals(start)) {
                throw new IllegalArgumentException(kind + " " + start + " would be merged into itself");
            }
            if (seen.add(id) && merges.containsKey(id)) {
                stack.addAll(merges.get(id));
            }
        }
    }

    /** Whether the verdict a side rests on still holds as direct evidence. */
    private boolean stillDirect(Contradiction.Side side) {
        EvidenceVerdict verdict = verdict(side.getQuestionId(), side.getPassageId());
        return verdict != null && verdict.getKind() == EvidenceVerdict.Kind.BELEGT;
    }

    private Map<String, List<String>> questionMergeMap() {
        Map<String, List<String>> merges = new HashMap<String, List<String>>();
        for (ResearchQuestion question : questions.values()) {
            merges.put(question.getId(), question.getMergedFrom());
        }
        return merges;
    }

    private Map<String, List<String>> topicMergeMap() {
        Map<String, List<String>> merges = new HashMap<String, List<String>>();
        for (ResearchTopic topic : topics.values()) {
            merges.put(topic.getId(), topic.getMergedFrom());
        }
        return merges;
    }

    /** The id and every id merged into it, directly or through others. */
    private static List<String> closure(String id, Map<String, List<String>> merges) {
        List<String> all = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        List<String> stack = new ArrayList<String>();
        stack.add(id);
        while (!stack.isEmpty()) {
            String at = stack.remove(stack.size() - 1);
            if (!seen.add(at)) {
                continue;
            }
            all.add(at);
            List<String> merged = merges.get(at);
            if (merged != null) {
                stack.addAll(merged);
            }
        }
        return all;
    }

    /** The question that stands on its own for an id: the end of its merge chain (the id itself when not merged). */
    private static String effectiveQuestion(String id, Map<String, String> mergedInto) {
        String at = id;
        Set<String> seen = new HashSet<String>();
        while (mergedInto.containsKey(at) && seen.add(at)) {
            at = mergedInto.get(at);
        }
        return at;
    }

    /** Merged-away id to the id that merged it (the latest record wins when two claim it). */
    private Map<String, String> mergedIntoQuestions() {
        Map<String, String> into = new HashMap<String, String>();
        for (ResearchQuestion question : questions.values()) {
            for (String id : question.getMergedFrom()) {
                into.put(id, question.getId());
            }
        }
        return into;
    }

    private Map<String, String> mergedIntoTopics() {
        Map<String, String> into = new HashMap<String, String>();
        for (ResearchTopic topic : topics.values()) {
            for (String id : topic.getMergedFrom()) {
                into.put(id, topic.getId());
            }
        }
        return into;
    }

    private Set<String> mergedAwayTopics() {
        return mergedIntoTopics().keySet();
    }

    private static <T> T resolve(String id, Map<String, T> records, Map<String, String> into) {
        if (!records.containsKey(id)) {
            return null;
        }
        String at = id;
        Set<String> seen = new HashSet<String>();
        while (into.containsKey(at) && seen.add(at)) {
            at = into.get(at);
        }
        return records.get(at);
    }

    private static void appendGroup(StringBuilder out, String label, List<EvidenceVerdict> group) {
        if (group.isEmpty()) {
            return;
        }
        out.append("  ").append(label).append(" (").append(group.size()).append(")\n");
        for (EvidenceVerdict verdict : group) {
            out.append("    ").append(verdict.getPassageId()).append("  ").append(verdict.getSourceId());
            String shown = verdict.getKind() == EvidenceVerdict.Kind.BELEGT ? verdict.getQuote()
                    : verdict.getReason();
            if (!shown.isEmpty()) {
                out.append("  ").append(shown);
            }
            out.append('\n');
        }
    }
}
