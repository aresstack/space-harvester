package com.aresstack.writing4j.cornerstone;

import com.aresstack.knowledge4j.basket.OrderingHint;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.scope.Fence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The cornerstones of a book, from the basket to the terms the questions hang on (measurements M7 to M20, Angelo's
 * decisions of 27.11.2026). Three steps, the reader's two optional between them:
 * <ol>
 * <li>{@link #survey}: candidates without a model ({@link TermCandidates}), the sieve with votes
 * ({@link CornerstoneSieve}) and the fields as cards ({@link FieldCards}). The result has three sets, safe in, safe
 * out and uncertain, with their numbers.</li>
 * <li>{@link #withFields}: the reader dragged up the wanted fields; the fence is widened by them (and by the fields
 * left lying as not wanted). Only the terms of fields left lying are judged again with the widened fence; the terms
 * of wanted fields keep their votes, and a neighbour vote counts for them as well (the reader wants that
 * neighbourhood). Skipped, nothing changes.</li>
 * <li>{@link #selected}: the terms the reader selected as tags (the safe ones preselected, the uncertain middle pale,
 * a few places of each as examples), or {@link #chosen}: the safe terms and those of the uncertain middle the reader
 * sorted into the mind map; his order goes on as non-binding ordering hints. An empty middle asks nothing; going on
 * without choosing takes the safe terms only.</li>
 * </ol>
 */
public final class CornerstoneSurvey {

    /** The state after a step: the fence, the terms, their votes and the fields. */
    public static final class Survey {
        private final Fence fence;
        private final TermCandidates.Result candidates;
        private final Map<String, TermCandidates.Candidate> byKey;
        private final Map<String, CornerstoneSieve.Votes> votes;
        private final List<FieldCards.Field> fields;
        private final Set<String> wantedFields;
        private final boolean fieldsAnswered;
        private final Map<String, List<CornerstoneSieve.Place>> places;
        private final CornerstoneSieve sieve;
        private final Map<String, TermCandidates.Text> passages;
        private final Map<String, String> fieldOfPassage;
        private final Snippets snippets;

        Survey(Fence fence, TermCandidates.Result candidates, Map<String, CornerstoneSieve.Votes> votes,
               List<FieldCards.Field> fields, Set<String> wantedFields, boolean fieldsAnswered,
               Map<String, List<CornerstoneSieve.Place>> places, CornerstoneSieve sieve,
               Map<String, TermCandidates.Text> passages, int passageTerms, Snippets snippets) {
            this.passages = passages;
            this.snippets = snippets;
            this.fence = fence;
            this.candidates = candidates;
            this.byKey = new LinkedHashMap<String, TermCandidates.Candidate>();
            for (TermCandidates.Candidate candidate : candidates.getCandidates()) {
                byKey.put(candidate.getKey(), candidate);
            }
            this.votes = Collections.unmodifiableMap(new LinkedHashMap<String, CornerstoneSieve.Votes>(votes));
            this.fields = Collections.unmodifiableList(new ArrayList<FieldCards.Field>(fields));
            this.wantedFields = Collections.unmodifiableSet(new LinkedHashSet<String>(wantedFields));
            this.fieldsAnswered = fieldsAnswered;
            this.places = places;
            this.sieve = sieve;
            this.fieldOfPassage = fieldOfPassage(passageTerms);
        }

        /**
         * Per passage its field, without a model: the field with the most of its terms in the passage, at least
         * {@code passageTerms} and more than any other field; passages without a field are not listed.
         */
        private Map<String, String> fieldOfPassage(int passageTerms) {
            Map<String, Map<String, Integer>> counts = new LinkedHashMap<String, Map<String, Integer>>();
            for (FieldCards.Field field : fields) {
                for (String key : field.getKeys()) {
                    TermCandidates.Candidate term = byKey.get(key);
                    if (term == null) {
                        continue;
                    }
                    for (String id : term.getPassageIds()) {
                        counts.computeIfAbsent(id, k -> new LinkedHashMap<String, Integer>())
                                .merge(field.getName(), 1, Integer::sum);
                    }
                }
            }
            Map<String, String> out = new LinkedHashMap<String, String>();
            for (Map.Entry<String, Map<String, Integer>> passage : counts.entrySet()) {
                String best = null;
                int most = 0;
                boolean tie = false;
                for (Map.Entry<String, Integer> field : passage.getValue().entrySet()) {
                    if (field.getValue() > most) {
                        most = field.getValue();
                        best = field.getKey();
                        tie = false;
                    } else if (field.getValue() == most) {
                        tie = true;
                    }
                }
                if (best != null && !tie && most >= passageTerms) {
                    out.put(passage.getKey(), best);
                }
            }
            return out;
        }

        /** The field of a passage; {@code ""} when it has none ("weitere Sätze"). */
        public String fieldOf(String passageId) {
            String field = fieldOfPassage.get(passageId);
            return field == null ? "" : field;
        }

        /** The passages of a term in the given field ({@code ""}: the passages without a field). */
        public List<String> passagesIn(String key, String field) {
            List<String> out = new ArrayList<String>();
            TermCandidates.Candidate term = byKey.get(key);
            if (term != null) {
                for (String id : term.getPassageIds()) {
                    if (fieldOf(id).equals(field == null ? "" : field)) {
                        out.add(id);
                    }
                }
            }
            return out;
        }

        /**
         * The examples at a term split by field, in the order of the fields, those without a field last under
         * {@code ""}: per snippet the passage, the sentence position, heading and source, and the sentences around it.
         * A term in one field only has one entry.
         */
        public Map<String, List<Snippets.Snippet>> snippetsByField(String key) {
            Map<String, List<Snippets.Snippet>> out = new LinkedHashMap<String, List<Snippets.Snippet>>();
            TermCandidates.Candidate term = byKey.get(key);
            if (term == null) {
                return out;
            }
            List<String> names = new ArrayList<String>();
            for (FieldCards.Field field : fields) {
                names.add(field.getName());
            }
            names.add("");
            for (String name : names) {
                List<String> ids = passagesIn(key, name);
                if (!ids.isEmpty()) {
                    out.put(name, snippets.of(term, ids, passages));
                }
            }
            return out;
        }

        /** The examples at a term over all its passages. */
        public List<Snippets.Snippet> snippets(String key) {
            TermCandidates.Candidate term = byKey.get(key);
            return term == null ? Collections.<Snippets.Snippet>emptyList()
                    : snippets.of(term, term.getPassageIds(), passages);
        }

        /** The whole paragraph of a snippet. */
        public TermCandidates.Text passage(String passageId) {
            return passages.get(passageId);
        }

        public Fence getFence() {
            return fence;
        }

        public TermCandidates.Result getCandidates() {
            return candidates;
        }

        public Map<String, CornerstoneSieve.Votes> getVotes() {
            return votes;
        }

        /** The fields as cards, in the order of the model. */
        public List<FieldCards.Field> getFields() {
            return fields;
        }

        public boolean isFieldsAnswered() {
            return fieldsAnswered;
        }

        public Set<String> getWantedFields() {
            return wantedFields;
        }

        /** The places of a term as the sieve showed them. */
        public List<CornerstoneSieve.Place> places(String key) {
            List<CornerstoneSieve.Place> list = places.get(key);
            return list == null ? Collections.<CornerstoneSieve.Place>emptyList() : list;
        }

        public TermCandidates.Candidate candidate(String key) {
            return byKey.get(key);
        }

        /** Where a term stands now. */
        public CornerstoneSieve.Verdict verdict(String key) {
            CornerstoneSieve.Votes v = votes.get(key);
            if (v == null) {
                return CornerstoneSieve.Verdict.SICHER_DRAUSSEN;
            }
            boolean wanted = false;
            for (FieldCards.Field field : fields) {
                wanted |= wantedFields.contains(field.getName()) && field.getKeys().contains(key);
            }
            return sieve.verdict(v, wanted ? EnumSet.of(CornerstoneSieve.Role.ECKPFEILER, CornerstoneSieve.Role.UMFELD)
                    : EnumSet.of(CornerstoneSieve.Role.ECKPFEILER));
        }

        public List<TermCandidates.Candidate> safeIn() {
            return with(CornerstoneSieve.Verdict.SICHER_DRIN);
        }

        /** The uncertain middle, in rank order. */
        public List<TermCandidates.Candidate> uncertain() {
            return with(CornerstoneSieve.Verdict.UNSICHER);
        }

        public List<TermCandidates.Candidate> safeOut() {
            return with(CornerstoneSieve.Verdict.SICHER_DRAUSSEN);
        }

        private List<TermCandidates.Candidate> with(CornerstoneSieve.Verdict verdict) {
            List<TermCandidates.Candidate> out = new ArrayList<TermCandidates.Candidate>();
            for (TermCandidates.Candidate candidate : candidates.getCandidates()) {
                if (verdict(candidate.getKey()) == verdict) {
                    out.add(candidate);
                }
            }
            return out;
        }

        /** The numbers in one line for the log: candidates, safe in, uncertain, safe out, fields. */
        public String numbers() {
            return candidates.getCandidates().size() + " candidates, " + safeIn().size() + " safe in, "
                    + uncertain().size() + " uncertain, " + safeOut().size() + " safe out, " + fields.size()
                    + " fields";
        }
    }

    /** The terms the questions hang on and the reader's order. */
    public static final class Chosen {
        private final List<TermCandidates.Candidate> cornerstones;
        private final List<OrderingHint> hints;
        private final Map<String, List<String>> passages;
        private final List<String> excludedFields;

        Chosen(List<TermCandidates.Candidate> cornerstones, List<OrderingHint> hints,
               Map<String, List<String>> passages, List<String> excludedFields) {
            this.cornerstones = Collections.unmodifiableList(new ArrayList<TermCandidates.Candidate>(cornerstones));
            this.hints = Collections.unmodifiableList(new ArrayList<OrderingHint>(hints));
            this.passages = passages;
            this.excludedFields = Collections.unmodifiableList(new ArrayList<String>(excludedFields));
        }

        /** The passages Phase 4 builds the questions of a cornerstone from: those of its fields, or all of the word. */
        public List<String> passagesOf(TermCandidates.Candidate cornerstone) {
            List<String> list = passages.get(cornerstone.getKey());
            return list == null ? cornerstone.getPassageIds() : list;
        }

        /** The fields the reader deselected: they go into the Weidezaun as exclusions. */
        public List<String> getExcludedFields() {
            return excludedFields;
        }

        public List<TermCandidates.Candidate> getCornerstones() {
            return cornerstones;
        }

        public List<OrderingHint> getHints() {
            return hints;
        }
    }

    private final TermCandidates candidates;
    private final CornerstoneSieve sieve;
    private final FieldCards cards;
    private final Snippets snippets;
    private final List<MainModelChat> models;
    private final Consumer<String> log;

    /**
     * @param models the models the host offers for the sieve (at least one); each one gives its votes
     * @param cards  the model that names the fields
     */
    public CornerstoneSurvey(TermCandidates candidates, CornerstoneSieve sieve, FieldCards cards,
                             List<MainModelChat> models, Consumer<String> log) {
        this(candidates, sieve, cards, new Snippets(null), models, log);
    }

    public CornerstoneSurvey(TermCandidates candidates, CornerstoneSieve sieve, FieldCards cards, Snippets snippets,
                             List<MainModelChat> models, Consumer<String> log) {
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("the sieve needs at least one model");
        }
        this.candidates = candidates;
        this.sieve = sieve;
        this.cards = cards;
        this.snippets = snippets == null ? new Snippets(null) : snippets;
        this.models = new ArrayList<MainModelChat>(models);
        this.log = log == null ? line -> {
        } : log;
    }

    /** Candidates, sieve and fields. */
    public Survey survey(List<TermCandidates.Text> passages, Fence fence, List<String> subjectWords,
                         CornerstoneSieve.Progress progress) {
        TermCandidates.Result found = candidates.find(passages, fence, subjectWords);
        Map<String, TermCandidates.Text> byId = new LinkedHashMap<String, TermCandidates.Text>();
        for (TermCandidates.Text passage : passages) {
            byId.put(passage.getId(), passage);
        }
        Map<String, List<CornerstoneSieve.Place>> places = new LinkedHashMap<String, List<CornerstoneSieve.Place>>();
        for (TermCandidates.Candidate candidate : found.getCandidates()) {
            places.put(candidate.getKey(), CornerstoneSieve.places(candidate, byId, sieve.placeChars()));
        }
        Map<String, CornerstoneSieve.Votes> votes = sieve.judge(found.getCandidates(), places,
                sieve.runs(models, fence), fence, progress);
        List<TermCandidates.Candidate> forFields = new ArrayList<TermCandidates.Candidate>();
        for (TermCandidates.Candidate candidate : found.getCandidates()) {
            CornerstoneSieve.Votes v = votes.get(candidate.getKey());
            if (v.count(CornerstoneSieve.Role.ECKPFEILER) + v.count(CornerstoneSieve.Role.UMFELD) > 0) {
                forFields.add(candidate);
            }
        }
        List<FieldCards.Field> fields = cards.fields(forFields, fence);
        Survey survey = new Survey(fence, found, votes, fields, Collections.<String>emptySet(), false, places, sieve,
                byId, cards.passageTerms(), snippets);
        log.accept("cornerstones: " + survey.numbers());
        return survey;
    }

    /**
     * The reader's answer on the cards: the wanted fields widen the fence, the others are excluded, and only the terms
     * of the excluded fields are judged again with the widened fence (with every model and set of places).
     *
     * @param wanted the names of the fields dragged up; {@code null} when the reader skipped the cards
     */
    public Survey withFields(Survey survey, Set<String> wanted, CornerstoneSieve.Progress progress) {
        if (wanted == null || survey.fields.isEmpty()) {
            return survey;
        }
        List<String> in = new ArrayList<String>();
        List<String> out = new ArrayList<String>();
        Set<String> denied = new LinkedHashSet<String>();
        for (FieldCards.Field field : survey.fields) {
            if (wanted.contains(field.getName())) {
                in.add(field.getName());
            } else {
                out.add(field.getName());
                denied.addAll(field.getKeys());
            }
        }
        Fence widened = survey.fence.widened(in, out);
        List<TermCandidates.Candidate> again = new ArrayList<TermCandidates.Candidate>();
        for (TermCandidates.Candidate candidate : survey.candidates.getCandidates()) {
            if (denied.contains(candidate.getKey())) {
                again.add(candidate);
            }
        }
        List<CornerstoneSieve.Run> runs = new ArrayList<CornerstoneSieve.Run>();
        for (CornerstoneSieve.Run run : sieve.runs(models, widened)) {
            if (run.isWithFence()) {
                runs.add(run);
            }
        }
        Map<String, CornerstoneSieve.Votes> votes = new LinkedHashMap<String, CornerstoneSieve.Votes>(survey.votes);
        votes.putAll(sieve.judge(again, survey.places, runs, widened, progress));
        Survey next = new Survey(widened, survey.candidates, votes, survey.fields, new LinkedHashSet<String>(in), true,
                survey.places, sieve, survey.passages, cards.passageTerms(), snippets);
        log.accept("cornerstones after the cards (" + in.size() + " fields wanted, " + again.size()
                + " terms judged again): " + next.numbers());
        return next;
    }

    /**
     * Exactly the terms the reader selected (the tags he coloured), in rank order, whatever the sieve said; the sieve
     * only preselects (safe in coloured, the uncertain middle pale, safe out folded away).
     *
     * @param selected the keys of the selected terms
     * @param parents  the term each selected term was placed under, by key ({@code ""} or missing at the top)
     * @param given    the ordering hints that came with the basket
     */
    public static Chosen selected(Survey survey, Set<String> selected, Map<String, String> parents,
                                  List<OrderingHint> given) {
        Map<String, Set<String>> whole = new LinkedHashMap<String, Set<String>>();
        for (String key : selected == null ? Collections.<String>emptySet() : selected) {
            whole.put(key, Collections.<String>emptySet());
        }
        return selected(survey, whole, Collections.<String>emptySet(), parents, given);
    }

    /**
     * The reader's choice of "Begriffe wählen": per selected term the fields it was chosen for (empty: the whole
     * word, all its passages), the fields he deselected as a whole (exclusions of the Weidezaun), his order. A term
     * not selected gets no questions of its own, but its passages stay in the basket as evidence.
     */
    public static Chosen selected(Survey survey, Map<String, Set<String>> selected, Set<String> deselectedFields,
                                  Map<String, String> parents, List<OrderingHint> given) {
        List<TermCandidates.Candidate> out = new ArrayList<TermCandidates.Candidate>();
        Map<String, List<String>> passages = new LinkedHashMap<String, List<String>>();
        for (TermCandidates.Candidate candidate : survey.candidates.getCandidates()) {
            Set<String> fields = selected == null ? null : selected.get(candidate.getKey());
            if (fields == null) {
                continue;
            }
            out.add(candidate);
            if (!fields.isEmpty()) {
                List<String> ids = new ArrayList<String>();
                for (String id : candidate.getPassageIds()) {
                    if (fields.contains(survey.fieldOf(id))) {
                        ids.add(id);
                    }
                }
                passages.put(candidate.getKey(), ids);
            }
        }
        List<String> excluded = new ArrayList<String>();
        for (FieldCards.Field field : survey.fields) {
            if (deselectedFields != null && deselectedFields.contains(field.getName())) {
                excluded.add(field.getName());
            }
        }
        return new Chosen(out, hints(survey, parents, given), passages, excluded);
    }

    /**
     * The safe terms and the uncertain ones the reader sorted.
     *
     * @param sorted the keys of the uncertain terms the reader placed, each with the key or text of the term it was
     *               placed under ({@code ""} for the top)
     * @param given  the ordering hints that came with the basket (the reader's order of Phase 3)
     */
    public static Chosen chosen(Survey survey, Map<String, String> sorted, List<OrderingHint> given) {
        Map<String, String> placed = sorted == null ? Collections.<String, String>emptyMap() : sorted;
        List<TermCandidates.Candidate> out = new ArrayList<TermCandidates.Candidate>();
        for (TermCandidates.Candidate candidate : survey.candidates.getCandidates()) {
            CornerstoneSieve.Verdict verdict = survey.verdict(candidate.getKey());
            if (verdict == CornerstoneSieve.Verdict.SICHER_DRIN
                    || verdict == CornerstoneSieve.Verdict.UNSICHER && placed.containsKey(candidate.getKey())) {
                out.add(candidate);
            }
        }
        return new Chosen(out, hints(survey, placed, given), Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList());
    }

    /** The given hints, then one per placed term with a term above it. */
    private static List<OrderingHint> hints(Survey survey, Map<String, String> placed, List<OrderingHint> given) {
        List<OrderingHint> hints = new ArrayList<OrderingHint>(given == null
                ? Collections.<OrderingHint>emptyList() : given);
        if (placed == null) {
            return hints;
        }
        for (Map.Entry<String, String> entry : placed.entrySet()) {
            TermCandidates.Candidate term = survey.byKey.get(entry.getKey());
            String parent = entry.getValue() == null ? "" : entry.getValue().trim();
            TermCandidates.Candidate parentTerm = survey.byKey.get(parent);
            String parentText = parentTerm == null ? parent : parentTerm.getTerm();
            if (term != null && !parentText.isEmpty() && !parentText.equalsIgnoreCase(term.getTerm())) {
                OrderingHint hint = new OrderingHint(term.getTerm(), parentText);
                if (!hints.contains(hint)) {
                    hints.add(hint);
                }
            }
        }
        return hints;
    }
}
