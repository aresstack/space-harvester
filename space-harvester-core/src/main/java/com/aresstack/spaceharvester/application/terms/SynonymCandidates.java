package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kandidatengewinnung der Synonymerkennung (#336), Stufen A und B: Aus dem Begriffsindex eines Umfangs
 * ({@link SynonymScope}) entstehen Paare, die eine Prüfung lohnen, mit kurzen Fundstellen als Kontext. Ohne KI-Dienst;
 * Spring-, REST- und Swing-frei.
 *
 * <p>Stufe A, deterministisch:
 * <ul>
 *   <li>{@link SynonymCandidate.Reason#SPELLING}: gleicher Wortlaut bis auf Bindestrich, Leerraum, Umlautschreibung
 *       und ß. Flexionsformen ({@code Beleg}, {@code Belege}) sind keine Kandidaten.</li>
 *   <li>{@link SynonymCandidate.Reason#PHRASE}: ein Text sagt es („X, auch bezeichnet als Y“, „X, auch Y genannt“,
 *       „gleichbedeutend mit“, „synonym zu“, „auch bekannt als“, „(auch: Y)“); beide Seiten müssen Begriffe des
 *       Index sein. Geprüft werden die Texte, aus denen ohnehin Fundstellen gelesen werden.</li>
 *   <li>{@link SynonymCandidate.Reason#ACRONYM_SIBLINGS}: zwei bestätigte Ausschreibungen derselben Abkürzung.</li>
 *   <li>{@link SynonymCandidate.Reason#CONFIRMED_ELSEWHERE}: in einem anderen Geltungsbereich bestätigt, beide Begriffe
 *       kommen auch hier vor.</li>
 * </ul>
 * Stufe B, nur mit Embedding-Modell und nur für Begriffe mit Fundstellen aus freigegebenen Quellen: Je Begriff geht
 * ein kurzer Kontext (Begriff und Fundstellen) an das Modell; ähnliche Kontexte werden Kandidaten. Kein paarweiser
 * Vollvergleich: Zufallshyperebenen (LSH) sortieren die Vektoren in Eimer, verglichen wird nur innerhalb eines Eimers,
 * je Begriff bleiben die {@value #NEIGHBOURS} ähnlichsten. Ähnlichkeit allein bestätigt nie etwas; sie macht nur
 * einen Kandidaten für die KI-Prüfung.
 *
 * <p>Budget ({@link SynonymBudget}): höchstens {@code maxTerms} Begriffe, die mit den meisten Seiten zuerst, je
 * Begriff höchstens {@value #MAX_CONTEXTS} Fundstellen von verschiedenen Seiten; höchstens {@code maxEmbeddedTerms}
 * gehen an das Embedding-Modell. Ins Log kommen nur Anzahlen, nie Begriffe oder Text.
 */
public class SynonymCandidates {

    /** Fundstellen je Begriff, von verschiedenen Seiten. */
    static final int MAX_CONTEXTS = 4;
    /** Zeichen Kontext vor und nach einer Fundstelle. */
    static final int SNIPPET_CHARS = 120;
    /** So viele Seitentexte hält das Sammeln höchstens zugleich. */
    static final int TEXT_CACHE = 64;
    /** Größere Gruppen gleicher Faltung sind keine Schreibvarianten, sondern Rauschen. */
    static final int MAX_SPELLING_GROUP = 6;
    /** Zeichen des Kontexts je Begriff an das Embedding-Modell. */
    static final int MAX_CONTEXT_CHARS = 400;
    static final int EMBED_BATCH = 64;
    /** Unter dieser Kosinusähnlichkeit ist nichts ein Kandidat. */
    static final double MIN_SIMILARITY = 0.80;
    /** Je Begriff bleiben so viele Nachbarn. */
    static final int NEIGHBOURS = 3;
    static final int LSH_TABLES = 6;
    static final int LSH_BITS = 10;
    /** Ein Eimer mit mehr Vektoren wird nur bis hierher verglichen. */
    static final int MAX_BUCKET = 200;
    /** Höchstens so viele Wörter hat ein Begriff neben einer Formulierung. */
    static final int MAX_PHRASE_WORDS = 6;

    private static final System.Logger LOG = System.getLogger(SynonymCandidates.class.getName());
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    private static final Pattern TRAILING = Pattern.compile("[\\s.,;:)!?»\"']+$");
    private static final Pattern LEADING = Pattern.compile("^[\\s(«\"']+");
    private static final Pattern SEPARATOR_BEFORE = Pattern.compile("[\\s,(:;–-]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Formulierungen; {@code b} benennt den zweiten Begriff, sonst steht er direkt dahinter. */
    private static final List<Pattern> PHRASES = List.of(
            Pattern.compile("(?iu)[,(]?\\s*auch\\s+bezeichnet\\s+als\\s+"),
            Pattern.compile("(?iu)[,(]?\\s*auch\\s+bekannt\\s+als\\s+"),
            Pattern.compile("(?iu)[,(]?\\s*gleichbedeutend\\s+mit\\s+"),
            Pattern.compile("(?iu)[,(]?\\s*synonym\\s+(?:zu|für|mit)\\s+"),
            Pattern.compile("(?iu)[,(]?\\s*auch\\s+(?<b>[\\p{L}\\p{N}][\\p{L}\\p{N} \\-]{1,79}?)\\s+genannt\\b"),
            Pattern.compile("(?iu)\\s*\\(\\s*auch\\s*:?\\s+(?<b>[^)]{2,80}?)\\s*\\)"));

    private final SourceCatalog catalog;
    private final TermIndex index;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Function<PageKey, Optional<PageText>> texts;
    private final TermRelations relations;
    private final Supplier<Optional<EmbeddingProvider>> embeddings;
    private final Predicate<String> released;
    private final String processorVersion;

    /**
     * @param texts      aktueller projizierter Text einer Seite im Cache
     * @param embeddings das Embedding-Modell der wirksamen Konfiguration oder leer
     * @param released   ob Text dieser Quelle an den KI-Dienst gehen darf ({@code external-processing-sources})
     */
    public SynonymCandidates(SourceCatalog catalog, TermIndex index, Function<PageKey, Optional<PageHeader>> headers,
                             Function<PageKey, Optional<PageText>> texts, TermRelations relations,
                             Supplier<Optional<EmbeddingProvider>> embeddings, Predicate<String> released,
                             String processorVersion) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.relations = Objects.requireNonNull(relations, "relations");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.released = Objects.requireNonNull(released, "released");
        this.processorVersion = Objects.requireNonNull(processorVersion, "processorVersion");
    }

    /**
     * Ergebnis eines Durchgangs.
     *
     * @param candidates    die Kandidaten, je Paar einer, Regelgründe vor Ähnlichkeit
     * @param terms         betrachtete Begriffe nach dem Budget
     * @param embedded      Begriffe, die an das Embedding-Modell gingen
     * @param embeddingUsed ob Stufe B lief; {@code false} ohne Modell, ohne Budget oder nach einem Fehler des Dienstes
     */
    public record Collected(List<SynonymCandidate> candidates, int terms, int embedded, boolean embeddingUsed) {

        public Collected {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * Sammelt die Kandidaten eines Umfangs.
     *
     * @param context Auftrag für Abbruch und Fortschritt oder {@code null}
     */
    public Collected collect(SynonymScope scope, SynonymBudget budget, JobContext context) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(budget, "budget");
        Run run = new Run(scope, budget, context);
        run.gather();
        List<Term> selected = run.select();
        run.spelling(selected);
        run.loadContexts(selected);
        run.acronymSiblings();
        run.confirmedElsewhere();
        boolean embedded = run.embed(selected);
        List<SynonymCandidate> candidates = run.merged();
        LOG.log(System.Logger.Level.INFO, "Synonymkandidaten: {0} Begriffe betrachtet, {1} eingebettet, {2} Kandidaten",
                selected.size(), run.embedded, candidates.size());
        return new Collected(candidates, selected.size(), run.embedded, embedded);
    }

    /** Faltung für Schreibvarianten: Umlaute und ß ausgeschrieben, nur Buchstaben und Ziffern. */
    static String fold(String normalized) {
        StringBuilder out = new StringBuilder(normalized.length());
        for (char c : Normalizer.normalize(normalized, Normalizer.Form.NFC).toCharArray()) {
            switch (c) {
                case 'ä' -> out.append("ae");
                case 'ö' -> out.append("oe");
                case 'ü' -> out.append("ue");
                case 'ß' -> out.append("ss");
                default -> {
                    if (Character.isLetterOrDigit(c)) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Ein Begriff des Umfangs während des Laufs. */
    static final class Term {

        final String normalized;
        final String term;
        final TermKind kind;
        int pages;
        final List<Sample> samples = new ArrayList<>();
        List<SynonymCandidate.Context> contexts;
        float[] vector;

        Term(String normalized, String term, TermKind kind) {
            this.normalized = normalized;
            this.term = term;
            this.kind = kind;
        }
    }

    /** Eine Fundstelle, noch ohne Text. */
    record Sample(PageKey page, TermOccurrence occurrence, String inputSha256) {
    }

    private record Neighbour(int index, double similarity) {
    }

    /** Ein Durchgang mit seinem Zustand. */
    private final class Run {

        private final SynonymScope scope;
        private final SynonymBudget budget;
        private final JobContext context;
        private final Set<String> sourceIds;
        private final Map<String, Term> terms = new HashMap<>();
        private final Map<String, SynonymCandidate> found = new LinkedHashMap<>();
        private final Set<PageKey> scanned = new HashSet<>();
        private final Map<PageKey, Optional<PageText>> current = new LinkedHashMap<>(TEXT_CACHE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<PageKey, Optional<PageText>> eldest) {
                return size() > TEXT_CACHE;
            }
        };
        int embedded;

        Run(SynonymScope scope, SynonymBudget budget, JobContext context) {
            this.scope = scope;
            this.budget = budget;
            this.context = context;
            this.sourceIds = scope.global()
                    ? catalog.all().stream().map(HarvestSource::id).collect(java.util.stream.Collectors.toSet())
                    : catalog.find(scope.sourceId()).map(source -> Set.of(source.id())).orElse(Set.of());
        }

        private void checkpoint() {
            if (context != null) {
                context.checkpoint();
            }
        }

        /** Erster Durchgang durch den Index: je Begriff Seitenzahl und wenige Fundstellen, noch ohne Text. */
        void gather() {
            if (sourceIds.isEmpty()) {
                return;
            }
            for (TermKind kind : List.of(TermKind.NOUN, TermKind.IDENTIFIER)) {
                TermIndex.Filter filter = new TermIndex.Filter(sourceIds, kind, null, null, processorVersion);
                String from = null;
                while (true) {
                    checkpoint();
                    List<TermPosting> batch = index.postings(filter, from, false, TermIndex.MAX_BATCH);
                    for (TermPosting posting : batch) {
                        if (posting.occurrences().isEmpty()) {
                            continue;
                        }
                        Optional<PageHeader> header = headers.apply(posting.page());
                        if (header.isEmpty() || !Objects.equals(posting.revision(), header.get().revision())
                                || !scope.covers(posting.page().sourceId(),
                                header.get().metadata().partitionKey())) {
                            continue;
                        }
                        Term term = terms.computeIfAbsent(posting.normalized(),
                                normalized -> new Term(normalized, posting.term(), kind));
                        term.pages++;
                        if (term.samples.size() < MAX_CONTEXTS) {
                            term.samples.add(new Sample(posting.page(), posting.occurrences().get(0),
                                    posting.inputSha256()));
                        }
                    }
                    if (batch.size() < TermIndex.MAX_BATCH) {
                        break;
                    }
                    from = batch.get(batch.size() - 1).sortKey();
                }
            }
        }

        /**
         * Die Begriffe mit den meisten Seiten zuerst, bis zum Budget; alle anderen kennt der Lauf danach nicht mehr,
         * damit auch Formulierungen, Akronym-Geschwister und anderswo Bestätigtes nur gewählte Begriffe paaren.
         */
        List<Term> select() {
            List<Term> ranked = new ArrayList<>(terms.values());
            ranked.sort(Comparator.comparingInt((Term term) -> -term.pages).thenComparing(term -> term.normalized));
            if (ranked.size() <= budget.maxTerms()) {
                return ranked;
            }
            List<Term> selected = new ArrayList<>(ranked.subList(0, budget.maxTerms()));
            Set<String> keep = new HashSet<>();
            selected.forEach(term -> keep.add(term.normalized));
            terms.keySet().retainAll(keep);
            return selected;
        }

        void spelling(List<Term> selected) {
            Map<String, List<Term>> groups = new LinkedHashMap<>();
            for (Term term : selected) {
                groups.computeIfAbsent(fold(term.normalized), key -> new ArrayList<>()).add(term);
            }
            for (List<Term> group : groups.values()) {
                if (group.size() < 2 || group.size() > MAX_SPELLING_GROUP) {
                    continue;
                }
                for (int i = 0; i < group.size(); i++) {
                    for (int j = i + 1; j < group.size(); j++) {
                        add(group.get(i), group.get(j), SynonymCandidate.Reason.SPELLING, 0);
                    }
                }
            }
        }

        /** Liest die Fundstellen der gewählten Begriffe und prüft dabei jeden Text einmal auf Formulierungen. */
        void loadContexts(List<Term> selected) {
            int done = 0;
            for (Term term : selected) {
                if (done++ % 100 == 0) {
                    checkpoint();
                }
                contexts(term);
            }
            // Kandidaten der Regeln bekommen ihre Fundstellen jetzt, damit sie Belege und Kontext haben.
            for (SynonymCandidate candidate : new ArrayList<>(found.values())) {
                if (candidate.fromContext().isEmpty() || candidate.toContext().isEmpty()) {
                    Term from = terms.get(TermText.normalize(candidate.fromTerm()));
                    Term to = terms.get(TermText.normalize(candidate.toTerm()));
                    if (from != null && to != null) {
                        found.put(candidate.pairKey(), new SynonymCandidate(candidate.fromTerm(), candidate.toTerm(),
                                candidate.reason(), contexts(from), contexts(to), candidate.similarity()));
                    }
                }
            }
        }

        List<SynonymCandidate.Context> contexts(Term term) {
            if (term.contexts != null) {
                return term.contexts;
            }
            List<SynonymCandidate.Context> contexts = new ArrayList<>();
            for (Sample sample : term.samples) {
                Optional<PageText> text = text(sample.page()).filter(page -> page.inputSha256()
                        .equals(sample.inputSha256()));
                if (text.isEmpty() || sample.occurrence().end() > text.get().text().length()) {
                    continue;
                }
                contexts.add(context(contexts.size() + 1, text.get().text(), sample.page(), sample.occurrence(),
                        sample.inputSha256()));
            }
            term.contexts = List.copyOf(contexts);
            return term.contexts;
        }

        private SynonymCandidate.Context context(int id, String text, PageKey page, TermOccurrence occurrence,
                                                 String inputSha256) {
            TermQueries.Snippet snippet = TermQueries.snippet(text, occurrence, SNIPPET_CHARS);
            return new SynonymCandidate.Context(id, snippet.before() + "[[" + snippet.match() + "]]"
                    + snippet.after(), new RelationEvidence(page.sourceId(), page.pageId(), occurrence.start(),
                    occurrence.end(), inputSha256));
        }

        private Optional<PageText> text(PageKey key) {
            Optional<PageText> text = current.get(key);
            if (text == null) {
                try {
                    text = texts.apply(key);
                } catch (RuntimeException e) {
                    text = Optional.empty();
                }
                current.put(key, text);
            }
            if (text.isPresent() && scanned.add(key)) {
                phrases(key, text.get());
            }
            return text;
        }

        /** Formulierungen im Text einer Seite, beide Seiten müssen Begriffe des Umfangs sein. */
        private void phrases(PageKey page, PageText text) {
            String body = text.text();
            for (Pattern pattern : PHRASES) {
                Matcher matcher = pattern.matcher(body);
                while (matcher.find()) {
                    Span before = before(body, matcher.start());
                    if (before == null) {
                        continue;
                    }
                    Span after;
                    if (pattern.pattern().contains("<b>")) {
                        String named = matcher.group("b").strip();
                        Term term = terms.get(TermText.normalize(named));
                        after = term == null ? null : new Span(matcher.start("b"), matcher.start("b")
                                + named.length(), term);
                    } else {
                        after = after(body, matcher.end());
                    }
                    if (after == null || after.term == before.term) {
                        continue;
                    }
                    List<SynonymCandidate.Context> from = List.of(context(1, body, page,
                            new TermOccurrence(before.start, before.end), text.inputSha256()));
                    List<SynonymCandidate.Context> to = List.of(context(1, body, page,
                            new TermOccurrence(after.start, after.end), text.inputSha256()));
                    String key = pairKey(before.term, after.term);
                    if (!found.containsKey(key)) {
                        found.put(key, new SynonymCandidate(before.term.term, after.term.term,
                                SynonymCandidate.Reason.PHRASE, from, to, 0));
                    }
                }
            }
        }

        private record Span(int start, int end, Term term) {
        }

        /** Der längste bekannte Begriff, der direkt vor {@code position} endet. */
        private Span before(String body, int position) {
            int from = Math.max(0, position - 160);
            String window = body.substring(from, position);
            Matcher separator = SEPARATOR_BEFORE.matcher(window);
            int end = separator.find() ? separator.start() : window.length();
            window = window.substring(0, end);
            List<int[]> tokens = new ArrayList<>();
            Matcher token = TOKEN.matcher(window);
            while (token.find()) {
                tokens.add(new int[]{token.start(), token.end()});
            }
            for (int n = Math.min(MAX_PHRASE_WORDS, tokens.size()); n >= 1; n--) {
                int start = tokens.get(tokens.size() - n)[0];
                String candidate = window.substring(start);
                Matcher leading = LEADING.matcher(candidate);
                int skip = leading.find() ? leading.end() : 0;
                candidate = candidate.substring(skip);
                Term term = candidate.isEmpty() ? null : terms.get(TermText.normalize(candidate));
                if (term != null) {
                    return new Span(from + start + skip, from + end, term);
                }
            }
            return null;
        }

        /** Der längste bekannte Begriff, der bei {@code position} beginnt. */
        private Span after(String body, int position) {
            int to = Math.min(body.length(), position + 160);
            String window = body.substring(position, to);
            List<int[]> tokens = new ArrayList<>();
            Matcher token = TOKEN.matcher(window);
            while (token.find() && tokens.size() < MAX_PHRASE_WORDS) {
                tokens.add(new int[]{token.start(), token.end()});
            }
            for (int n = tokens.size(); n >= 1; n--) {
                String candidate = window.substring(0, tokens.get(n - 1)[1]);
                Matcher trailing = TRAILING.matcher(candidate);
                if (trailing.find()) {
                    candidate = candidate.substring(0, trailing.start());
                }
                Term term = candidate.isEmpty() ? null : terms.get(TermText.normalize(candidate));
                if (term != null) {
                    return new Span(position, position + candidate.length(), term);
                }
            }
            return null;
        }

        /** Zwei bestätigte Ausschreibungen derselben Abkürzung, deren Geltungsbereich diesen Umfang berührt. */
        void acronymSiblings() {
            Map<String, List<TermRelation>> byAcronym = new LinkedHashMap<>();
            for (TermRelation relation : relations.stored()) {
                if (relation.type() == RelationType.ABBREVIATION && relation.status() == ReviewStatus.CONFIRMED
                        && touches(relation.scope())) {
                    byAcronym.computeIfAbsent(TermText.normalize(relation.fromTerm()), key -> new ArrayList<>())
                            .add(relation);
                }
            }
            for (List<TermRelation> expansions : byAcronym.values()) {
                for (int i = 0; i < expansions.size(); i++) {
                    for (int j = i + 1; j < expansions.size(); j++) {
                        TermRelation a = expansions.get(i);
                        TermRelation b = expansions.get(j);
                        if (TermText.normalize(a.toTerm()).equals(TermText.normalize(b.toTerm()))) {
                            continue;
                        }
                        String key = pairKey(a.toTerm(), b.toTerm());
                        if (!found.containsKey(key)) {
                            found.put(key, new SynonymCandidate(a.toTerm(), b.toTerm(),
                                    SynonymCandidate.Reason.ACRONYM_SIBLINGS, contextsOf(a.toTerm(), a.evidence()),
                                    contextsOf(b.toTerm(), b.evidence()), 0));
                        }
                    }
                }
            }
        }

        /** Fundstellen eines Begriffs: aus dem Index, sonst aus den Belegen einer Beziehung. */
        private List<SynonymCandidate.Context> contextsOf(String term, List<RelationEvidence> evidence) {
            Term known = terms.get(TermText.normalize(term));
            if (known != null) {
                List<SynonymCandidate.Context> contexts = contexts(known);
                if (!contexts.isEmpty()) {
                    return contexts;
                }
            }
            List<SynonymCandidate.Context> contexts = new ArrayList<>();
            for (RelationEvidence e : evidence) {
                if (contexts.size() >= MAX_CONTEXTS || !sourceIds.contains(e.sourceId())) {
                    continue;
                }
                PageKey page = new PageKey(e.sourceId(), e.pageId());
                Optional<PageHeader> header = headers.apply(page);
                if (header.isEmpty() || !scope.covers(e.sourceId(), header.get().metadata().partitionKey())) {
                    continue;
                }
                Optional<PageText> text = text(page).filter(t -> t.inputSha256().equals(e.inputSha256()));
                if (text.isEmpty() || e.end() > text.get().text().length()) {
                    continue;
                }
                contexts.add(context(contexts.size() + 1, text.get().text(), page,
                        new TermOccurrence(e.start(), e.end()), e.inputSha256()));
            }
            return contexts;
        }

        /** Ob eine Beziehung dieses Geltungsbereichs in diesem Umfang gilt. */
        private boolean touches(RelationScope relationScope) {
            if (relationScope.global()) {
                return true;
            }
            if (scope.global()) {
                return sourceIds.contains(relationScope.sourceId());
            }
            return relationScope.sourceId().equals(scope.sourceId())
                    && (relationScope.spaceKey() == null || scope.spaceKey() == null
                    || relationScope.spaceKey().equals(scope.spaceKey()));
        }

        /** Bestätigte Synonyme und Varianten eines anderen Geltungsbereichs, deren Begriffe auch hier vorkommen. */
        void confirmedElsewhere() {
            RelationScope own = scope.relationScope();
            for (TermRelation relation : relations.stored()) {
                if (relation.status() != ReviewStatus.CONFIRMED || relation.scope().global()
                        || relation.scope().equals(own)
                        || relation.type() != RelationType.SYNONYM && relation.type() != RelationType.VARIANT) {
                    continue;
                }
                Term from = terms.get(TermText.normalize(relation.fromTerm()));
                Term to = terms.get(TermText.normalize(relation.toTerm()));
                if (from != null && to != null) {
                    add(from, to, SynonymCandidate.Reason.CONFIRMED_ELSEWHERE, 0);
                }
            }
        }

        /** Stufe B: Kontexte freigegebener Quellen einbetten und Nachbarn über Zufallshyperebenen finden. */
        boolean embed(List<Term> selected) {
            if (budget.maxEmbeddedTerms() == 0) {
                return false;
            }
            Optional<EmbeddingProvider> provider;
            try {
                provider = embeddings.get();
            } catch (RuntimeException e) {
                provider = Optional.empty();
            }
            if (provider.isEmpty()) {
                return false;
            }
            List<Term> embeddable = new ArrayList<>();
            List<String> inputs = new ArrayList<>();
            for (Term term : selected) {
                if (embeddable.size() >= budget.maxEmbeddedTerms()) {
                    break;
                }
                String input = embeddingInput(term);
                if (input != null) {
                    embeddable.add(term);
                    inputs.add(input);
                }
            }
            if (embeddable.size() < 2) {
                return false;
            }
            int dimensions = provider.get().dimensions();
            try {
                for (int from = 0; from < inputs.size(); from += EMBED_BATCH) {
                    checkpoint();
                    List<String> batch = inputs.subList(from, Math.min(inputs.size(), from + EMBED_BATCH));
                    List<float[]> vectors = provider.get().embed(batch);
                    if (vectors.size() != batch.size()) {
                        throw new IllegalStateException("Antwort passt nicht zur Anfrage");
                    }
                    for (int i = 0; i < batch.size(); i++) {
                        float[] vector = vectors.get(i);
                        if (vector.length != dimensions) {
                            throw new IllegalStateException("Vektorlänge passt nicht zum Modell");
                        }
                        embeddable.get(from + i).vector = EmbeddingIndex.normalized(vector);
                    }
                }
            } catch (JobCancelledException | JobFailedException e) {
                // Abbruch oder Frist des Auftrags: kein Dienstfehler, der Lauf endet.
                throw e;
            } catch (RuntimeException e) {
                // Nur der Typ ins Log; die übrigen Stufen bleiben nutzbar.
                LOG.log(System.Logger.Level.WARNING, "Semantische Vorauswahl der Synonyme übersprungen ({0})",
                        e.getClass().getName());
                embeddable.forEach(term -> term.vector = null);
                return false;
            }
            embedded = embeddable.size();
            neighbours(embeddable, dimensions, provider.get().modelId());
            return true;
        }

        /** Begriff und Fundstellen aus freigegebenen Quellen oder {@code null} ohne solche. */
        private String embeddingInput(Term term) {
            StringBuilder input = new StringBuilder(term.term).append(": ");
            boolean any = false;
            for (SynonymCandidate.Context context : contexts(term)) {
                if (!released.test(context.evidence().sourceId())) {
                    continue;
                }
                if (any) {
                    input.append(" … ");
                }
                input.append(WHITESPACE.matcher(context.text().replace("[[", "").replace("]]", ""))
                        .replaceAll(" ").strip());
                any = true;
                if (input.length() >= MAX_CONTEXT_CHARS) {
                    break;
                }
            }
            if (!any) {
                return null;
            }
            return input.length() > MAX_CONTEXT_CHARS ? input.substring(0, MAX_CONTEXT_CHARS) : input.toString();
        }

        private void neighbours(List<Term> embeddable, int dimensions, String modelId) {
            Random random = new Random(modelId.hashCode() * 31L + dimensions);
            List<List<Neighbour>> nearest = new ArrayList<>();
            for (int i = 0; i < embeddable.size(); i++) {
                nearest.add(new ArrayList<>());
            }
            Set<Long> compared = new HashSet<>();
            for (int table = 0; table < LSH_TABLES; table++) {
                checkpoint();
                float[][] planes = new float[LSH_BITS][dimensions];
                for (float[] plane : planes) {
                    for (int d = 0; d < dimensions; d++) {
                        plane[d] = (float) random.nextGaussian();
                    }
                }
                Map<Integer, List<Integer>> buckets = new HashMap<>();
                for (int i = 0; i < embeddable.size(); i++) {
                    int signature = 0;
                    for (int bit = 0; bit < LSH_BITS; bit++) {
                        if (dot(planes[bit], embeddable.get(i).vector) >= 0) {
                            signature |= 1 << bit;
                        }
                    }
                    buckets.computeIfAbsent(signature, key -> new ArrayList<>()).add(i);
                }
                for (List<Integer> bucket : buckets.values()) {
                    int size = Math.min(bucket.size(), MAX_BUCKET);
                    for (int a = 0; a < size; a++) {
                        for (int b = a + 1; b < size; b++) {
                            int i = bucket.get(a);
                            int j = bucket.get(b);
                            if (!compared.add((long) Math.min(i, j) << 32 | Math.max(i, j))) {
                                continue;
                            }
                            double similarity = dot(embeddable.get(i).vector, embeddable.get(j).vector);
                            if (similarity >= MIN_SIMILARITY) {
                                nearest.get(i).add(new Neighbour(j, similarity));
                                nearest.get(j).add(new Neighbour(i, similarity));
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < embeddable.size(); i++) {
                List<Neighbour> list = nearest.get(i);
                list.sort(Comparator.comparingDouble((Neighbour n) -> -n.similarity()).thenComparingInt(Neighbour::index));
                for (Neighbour neighbour : list.subList(0, Math.min(NEIGHBOURS, list.size()))) {
                    add(embeddable.get(i), embeddable.get(neighbour.index()), SynonymCandidate.Reason.EMBEDDING,
                            neighbour.similarity());
                }
            }
        }

        private void add(Term from, Term to, SynonymCandidate.Reason reason, double similarity) {
            String key = pairKey(from, to);
            SynonymCandidate existing = found.get(key);
            if (existing == null) {
                found.put(key, new SynonymCandidate(from.term, to.term, reason,
                        from.contexts == null ? List.of() : from.contexts,
                        to.contexts == null ? List.of() : to.contexts, similarity));
            } else if (existing.similarity() == 0 && similarity > 0) {
                found.put(key, new SynonymCandidate(existing.fromTerm(), existing.toTerm(), existing.reason(),
                        existing.fromContext(), existing.toContext(), similarity));
            }
        }

        private static String pairKey(Term a, Term b) {
            return a.normalized.compareTo(b.normalized) <= 0 ? a.normalized + '\u0000' + b.normalized
                    : b.normalized + '\u0000' + a.normalized;
        }

        private static String pairKey(String a, String b) {
            String from = TermText.normalize(a);
            String to = TermText.normalize(b);
            return from.compareTo(to) <= 0 ? from + '\u0000' + to : to + '\u0000' + from;
        }

        /** Regelgründe zuerst, dann Ähnlichkeit absteigend; Kandidaten ohne Fundstellen entfallen. */
        List<SynonymCandidate> merged() {
            List<SynonymCandidate> list = new ArrayList<>();
            for (SynonymCandidate candidate : found.values()) {
                if (candidate.fromContext().isEmpty() || candidate.toContext().isEmpty()) {
                    continue;
                }
                list.add(candidate);
            }
            list.sort(Comparator.comparingInt((SynonymCandidate c) -> c.reason().ordinal())
                    .thenComparingDouble(c -> -c.similarity()).thenComparing(SynonymCandidate::pairKey));
            return list;
        }

        private static double dot(float[] a, float[] b) {
            double sum = 0;
            for (int i = 0; i < a.length; i++) {
                sum += (double) a[i] * b[i];
            }
            return sum;
        }
    }
}
