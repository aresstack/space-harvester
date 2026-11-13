package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Abfragen über den globalen Knowledge Graph (#499, ADR 0024): alle manuell analysierten Seiten aus allen Quellen und
 * Spaces, die der Aufrufer lesen darf. Spring-, REST- und Swing-frei; befüllt wird der Graph nur über
 * {@link StatementAnalysis#analyze}, Abfragen rechnen nie.
 *
 * <p>Rechte wie {@code TermQueries}: Eine Seite zählt nur, wenn der Aufrufer ihren Inhalt lesen darf
 * ({@link AccessGuard#canRead}). Treffer, Verbindungen und Zähler entstehen ausschließlich aus sichtbaren Seiten;
 * eine gesperrte Seite verrät weder Knoten noch Aussagen noch Anzahl.
 *
 * <p>Identität: Gleicher Wortlaut bzw. gleiche Grundform zweier Erwähnungen ist nur eine
 * {@link Match#LEXICAL lexikalische Kandidatenverbindung}; eine bestätigte Begriffsbeziehung (#120) ist eine
 * {@link Match#CONFIRMED_TERM_RELATION begriffliche} Verbindung. Keines von beiden verschmilzt Entitäten. Pronomen
 * bleiben ungeklärt und verbinden nichts.
 */
public class KnowledgeGraph {

    /** Höchstens so viele Treffer je Suche. */
    public static final int MAX_LIMIT = 200;

    /** Ausweitung eines Suchbegriffs über bestätigte Begriffsbeziehungen ({@code TermRelations#expansions}). */
    @FunctionalInterface
    public interface TermExpansion {

        /** Keine Ausweitung. */
        TermExpansion NONE = (caller, term, sourceId) -> List.of();

        List<String> expansions(AccessContext caller, String term, String sourceId);
    }

    /** Wie eine Verbindung zustande kommt. Keine der beiden ist eine bestätigte Identität. */
    public enum Match {
        /** Gleiche normalisierte Grundform oder Wortform. */
        LEXICAL,
        /** Über eine bestätigte Begriffsbeziehung (Synonym, Abkürzung, Variante). */
        CONFIRMED_TERM_RELATION
    }

    /**
     * Eine analysierte Seite mit Stand.
     *
     * @param key      Seite
     * @param spaceKey Space oder {@code null}
     * @param title    Titel beim Analysieren
     * @param state    {@link PageStatements.State#CURRENT} oder {@link PageStatements.State#STALE}
     * @param reason   Grund bei {@link PageStatements.State#STALE}, sonst {@code null}
     * @param document das analysierte Dokument (ADR 0026, Punkt 10): die Seite selbst, ein Anhang oder eine
     *                 enthaltene Datei; {@link DocumentKey#page()} ist {@code key}
     */
    public record PageRef(PageKey key, String spaceKey, String title, PageStatements.State state, String reason,
                          DocumentKey document) {

        public PageRef {
            Objects.requireNonNull(key, "key");
            document = document == null ? DocumentKey.page(key) : document;
            if (!document.page().equals(key)) {
                throw new IllegalArgumentException("Das Dokument gehört zu einer anderen Seite");
            }
        }

        /** Die Seite selbst (wie vor ADR 0026). */
        public PageRef(PageKey key, String spaceKey, String title, PageStatements.State state, String reason) {
            this(key, spaceKey, title, state, reason, null);
        }
    }

    /** Eine analysierte Seite mit ihrer Analyse und Projektion. */
    public record AnalysedPage(PageRef page, PageStatementAnalysis analysis) {

        public PageGraph graph() {
            return PageGraph.of(analysis);
        }
    }

    /**
     * Eine Beteiligung einer Erwähnung an einer Aussage.
     *
     * @param statement Aussage
     * @param role      Rolle der Erwähnung
     * @param certain   ob die Zuordnung sicher ist
     */
    public record Participation(ParsedStatements.Statement statement, ParsedStatements.Role role, boolean certain) {
    }

    /**
     * Ein Treffer: eine Erwähnung auf einer sichtbaren Seite.
     *
     * @param page        Seite
     * @param mention     Erwähnung mit Spanne als Beleg
     * @param match       wie sie zum Suchbegriff passt
     * @param matchedTerm der passende Begriff (Suchbegriff oder anderes Ende einer bestätigten Beziehung)
     * @param statements  Aussagen der Standardansicht (nur plausible), an denen sie beteiligt ist
     */
    public record Hit(PageRef page, ParsedStatements.Mention mention, Match match, String matchedTerm,
                      List<Participation> statements) {

        public Hit {
            statements = List.copyOf(statements);
        }
    }

    /**
     * Ergebnis einer Suche.
     *
     * @param term      normalisierter Suchbegriff
     * @param hits      Treffer, nach Quelle, Seite und Textstelle geordnet
     * @param total     Treffer insgesamt auf sichtbaren Seiten
     * @param pages     sichtbare Seiten mit Treffern
     */
    public record Search(String term, List<Hit> hits, int total, int pages) {

        public Search {
            hits = List.copyOf(hits);
        }
    }

    /**
     * Eine Verbindung von Erwähnungen dieser Seite zu Erwähnungen einer anderen sichtbaren, analysierten Seite.
     *
     * @param term           normalisierter Begriff, über den sie verbunden sind
     * @param match          Art der Verbindung
     * @param mentions       IDs der Erwähnungen auf dieser Seite
     * @param target         die andere Seite
     * @param targetMentions IDs der Erwähnungen dort
     */
    public record Link(String term, Match match, List<String> mentions, PageRef target, List<String> targetMentions) {

        public Link {
            mentions = List.copyOf(mentions);
            targetMentions = List.copyOf(targetMentions);
        }
    }

    private final KnowledgeGraphStore store;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;
    private final Function<PageSnapshot, Optional<PageText>> texts;
    private final StatementAnalysis statements;
    private final TermExpansion expansions;
    /** Ob eine Quelle eingeschaltet ist; Analysen abgeschalteter Quellen bleiben liegen, sind aber unsichtbar. */
    private final Predicate<String> activeSources;
    /** Eingabe-Hash je Seite und Payload, damit nicht jede Abfrage jede Seite neu projiziert. */
    private final Map<PageKey, InputMemo> inputs = new ConcurrentHashMap<>();
    /** {@code PageText#inputSha256()} eines Anhangs oder einer enthaltenen Datei; leer, solange keiner vorliegt. */
    private final Function<DocumentKey, Optional<String>> documentInputs;

    /**
     * @param snapshots  Seite im Bestand; leer, wenn sie dort nicht (mehr) liegt
     * @param texts      dieselbe Textprojektion wie der Leser
     * @param statements für den Stand je Seite (Analysator-Version)
     */
    public KnowledgeGraph(KnowledgeGraphStore store, AccessGuard access,
                          Function<PageKey, Optional<PageSnapshot>> snapshots,
                          Function<PageSnapshot, Optional<PageText>> texts, StatementAnalysis statements,
                          TermExpansion expansions) {
        this(store, access, snapshots, texts, statements, expansions, sourceId -> true);
    }

    /**
     * Wie oben, mit {@code activeSources}: Aussagen abgeschalteter Quellen erscheinen weder im Graph noch in Suche,
     * Verknüpfung oder Export, bis die Quelle wieder eingeschaltet ist.
     */
    public KnowledgeGraph(KnowledgeGraphStore store, AccessGuard access,
                          Function<PageKey, Optional<PageSnapshot>> snapshots,
                          Function<PageSnapshot, Optional<PageText>> texts, StatementAnalysis statements,
                          TermExpansion expansions, Predicate<String> activeSources) {
        this(store, access, snapshots, texts, statements, expansions, activeSources, document -> Optional.empty());
    }

    /**
     * Wie oben, mit {@code documentInputs} für Anhänge und enthaltene Dateien (ADR 0026, Punkt 10): der Eingabe-Hash
     * ihres Lesetexts, gegen den ihre Analysen als aktuell oder veraltet gelten. Ohne ihn gelten sie als veraltet
     * ({@link PageStatements#NO_TEXT}), nie stillschweigend als aktuell.
     */
    public KnowledgeGraph(KnowledgeGraphStore store, AccessGuard access,
                          Function<PageKey, Optional<PageSnapshot>> snapshots,
                          Function<PageSnapshot, Optional<PageText>> texts, StatementAnalysis statements,
                          TermExpansion expansions, Predicate<String> activeSources,
                          Function<DocumentKey, Optional<String>> documentInputs) {
        this.documentInputs = Objects.requireNonNull(documentInputs, "documentInputs");
        this.activeSources = Objects.requireNonNull(activeSources, "activeSources");
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.statements = Objects.requireNonNull(statements, "statements");
        this.expansions = Objects.requireNonNull(expansions, "expansions");
    }

    /**
     * Alle sichtbaren analysierten Seiten, nach Quelle und Seite geordnet; Anhänge und enthaltene Dateien mit eigener
     * Analyse folgen ihrer Seite ({@link PageRef#document()}).
     */
    public List<PageRef> pages(AccessContext caller) {
        return visible(caller).stream().map(AnalysedPage::page).toList();
    }

    /**
     * Alle sichtbaren analysierten Seiten mit Stand und Analyse (ohne CoNLL-U), nach Quelle und Seite geordnet; etwa für
     * den Export (#499 Abschnitt G). Rechnet nie.
     */
    public List<AnalysedPage> analysed(AccessContext caller) {
        return visible(caller);
    }

    /** Eine sichtbare analysierte Seite; leer ohne Analyse oder ohne Leserecht (nicht unterscheidbar). */
    public Optional<AnalysedPage> page(AccessContext caller, PageKey key) {
        return page(caller, DocumentKey.page(key));
    }

    /**
     * Ein sichtbares analysiertes Dokument: Seite, Anhang oder enthaltene Datei (ADR 0026, Punkt 10). Leer ohne Analyse
     * oder ohne Leserecht auf die Seite (nicht unterscheidbar); ein Dokument hat die Rechte seiner Seite.
     */
    public Optional<AnalysedPage> page(AccessContext caller, DocumentKey document) {
        return store.find(document).flatMap(analysis -> visible(caller, analysis));
    }

    /**
     * Ein sichtbares analysiertes Dokument der Seite nach {@link DocumentKey#fileName()}, für Knoten-IDs, die nur
     * diesen pfadsicheren Namen tragen ({@link PageStatementAnalysis#documentId(DocumentKey, String)}). Der Name wird nur
     * verglichen, nie zerlegt.
     */
    Optional<AnalysedPage> document(AccessContext caller, PageKey key, String fileName) {
        return store.documents().stream()
                .filter(analysis -> analysis.key().equals(key) && analysis.document().fileName().equals(fileName))
                .findFirst().flatMap(analysis -> visible(caller, analysis));
    }

    /**
     * Erwähnungen eines Begriffs auf allen sichtbaren analysierten Seiten, samt der Aussagen, an denen sie beteiligt
     * sind. Passend heißt: normalisierte Grundform, Kopfform oder Wortlaut gleich dem Suchbegriff
     * ({@link Match#LEXICAL}) oder dem anderen Ende einer bestätigten, sichtbaren Begriffsbeziehung
     * ({@link Match#CONFIRMED_TERM_RELATION}). Pronomen passen nie.
     *
     * @param limit 1 bis {@link #MAX_LIMIT}
     */
    public Search search(AccessContext caller, String term, int limit) {
        return search(caller, term, limit, page -> true);
    }

    /**
     * Wie {@link #search(AccessContext, String, int)}, aber nur auf Seiten, die {@code pages} zulässt (etwa eine Quelle,
     * ein Space oder nur aktuelle Analysen); Treffer und Zähler beziehen sich dann nur auf diese Seiten. Der Filter
     * schränkt die sichtbaren Seiten weiter ein, erweitert sie nie.
     */
    public Search search(AccessContext caller, String term, int limit, java.util.function.Predicate<PageRef> pages) {
        Objects.requireNonNull(pages, "pages");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        String normalized = TermText.normalize(Objects.requireNonNull(term, "term")).strip();
        if (normalized.isEmpty()) {
            return new Search(normalized, List.of(), 0, 0);
        }
        List<Hit> hits = new ArrayList<>();
        int total = 0;
        int matchedPages = 0;
        for (AnalysedPage page : visible(caller)) {
            if (!pages.test(page.page())) {
                continue;
            }
            Map<String, Match> wanted = new LinkedHashMap<>();
            wanted.put(normalized, Match.LEXICAL);
            for (String other : expansions.expansions(caller, term, page.page().key().sourceId())) {
                wanted.putIfAbsent(TermText.normalize(other), Match.CONFIRMED_TERM_RELATION);
            }
            boolean found = false;
            for (ParsedStatements.Mention mention : page.analysis().result().mentions()) {
                String matched = matching(mention, wanted.keySet());
                if (matched == null) {
                    continue;
                }
                found = true;
                total++;
                if (hits.size() < limit) {
                    hits.add(new Hit(page.page(), mention, wanted.get(matched), matched,
                            participations(page.analysis().result(), mention.id())));
                }
            }
            if (found) {
                matchedPages++;
            }
        }
        return new Search(normalized, hits, total, matchedPages);
    }

    /**
     * Verbindungen der Erwähnungen dieser Seite zu anderen sichtbaren, manuell analysierten Seiten: lexikalische
     * Kandidaten über die normalisierte Grundform und begriffliche über bestätigte Beziehungen. Leer ohne Analyse
     * oder ohne Leserecht auf diese Seite. Je Begriff und Zielseite eine Verbindung.
     */
    public List<Link> links(AccessContext caller, PageKey key) {
        return links(caller, DocumentKey.page(key));
    }

    /** Wie oben für ein Dokument; Ziele sind alle anderen sichtbaren Dokumente, auch die derselben Seite. */
    public List<Link> links(AccessContext caller, DocumentKey document) {
        PageKey key = document.page();
        Optional<AnalysedPage> origin = page(caller, document);
        if (origin.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> own = keys(origin.get().analysis());
        if (own.isEmpty()) {
            return List.of();
        }
        Map<String, Match> wanted = new LinkedHashMap<>();
        Map<String, List<String>> mentionsByWanted = new LinkedHashMap<>();
        own.forEach((term, ids) -> {
            wanted.put(term, Match.LEXICAL);
            mentionsByWanted.put(term, ids);
        });
        own.forEach((term, ids) -> {
            for (String other : expansions.expansions(caller, term, key.sourceId())) {
                String normalized = TermText.normalize(other);
                Match known = wanted.putIfAbsent(normalized, Match.CONFIRMED_TERM_RELATION);
                if (known == null) {
                    mentionsByWanted.put(normalized, new ArrayList<>(ids));
                } else if (known == Match.CONFIRMED_TERM_RELATION) {
                    // Mehrere eigene Begriffe mit bestätigter Beziehung zum selben Ziel: alle Erwähnungen behalten.
                    List<String> merged = mentionsByWanted.get(normalized);
                    ids.stream().filter(id -> !merged.contains(id)).forEach(merged::add);
                }
            }
        });
        List<Link> links = new ArrayList<>();
        for (AnalysedPage target : visible(caller)) {
            if (target.page().document().equals(document)) {
                continue;
            }
            Map<String, List<String>> there = keys(target.analysis());
            for (Map.Entry<String, Match> entry : wanted.entrySet()) {
                List<String> targetMentions = there.get(entry.getKey());
                if (targetMentions != null) {
                    links.add(new Link(entry.getKey(), entry.getValue(), mentionsByWanted.get(entry.getKey()),
                            target.page(), targetMentions));
                }
            }
        }
        return links;
    }

    /**
     * Löscht alle gespeicherten Aussagenanalysen, die der Aufrufer lesen darf, über alle Quellen, auch abgeschaltete
     * und veraltete (Knopf „Alle Aussagen löschen“). Danach ist der Graph für ihn leer; neue Aussagen entstehen nur
     * durch erneutes Analysieren auf Klick. Synchron; Seiten ohne Leserecht bleiben unberührt. Analysen von Anhängen
     * und enthaltenen Dateien gehen mit, sie haben die Rechte ihrer Seite.
     *
     * @return Zahl der gelöschten Analysen (Seiten und Dokumente)
     */
    public int deleteAll(AccessContext caller) {
        int deleted = 0;
        List<PageStatementAnalysis> all = new ArrayList<>(store.all());
        all.addAll(store.documents());
        for (PageStatementAnalysis analysis : all) {
            PageKey key = analysis.key();
            String spaceKey = snapshots.apply(key).map(PageSnapshot::spaceKey).orElse(analysis.spaceKey());
            if (access.canRead(caller, key.sourceId(), spaceKey, key.pageId())) {
                store.removeDocument(analysis.document());
                inputs.remove(key);
                deleted++;
            }
        }
        return deleted;
    }

    /** Vergisst gemerkte Eingabe-Hashes, etwa nach einem Neuaufbau des Caches. */
    public void forgetInputs() {
        inputs.clear();
    }

    private List<AnalysedPage> visible(AccessContext caller) {
        List<AnalysedPage> pages = new ArrayList<>();
        for (PageStatementAnalysis analysis : store.all()) {
            visible(caller, analysis).ifPresent(pages::add);
        }
        for (PageStatementAnalysis analysis : store.documents()) {
            visible(caller, analysis).ifPresent(pages::add);
        }
        // Die Seite vor ihren Dokumenten: ihr Schlüssel ist ein Präfix der Schlüssel ihrer Dokumente.
        pages.sort(Comparator.comparing((AnalysedPage page) -> page.page().key().sourceId())
                .thenComparing(page -> page.page().key().pageId())
                .thenComparing(page -> page.page().document().value()));
        return pages;
    }

    private Optional<AnalysedPage> visible(AccessContext caller, PageStatementAnalysis analysis) {
        PageKey key = analysis.key();
        if (!activeSources.test(key.sourceId())) {
            return Optional.empty();
        }
        Optional<PageSnapshot> snapshot = snapshots.apply(key);
        // Der aktuelle Space zählt, falls die Seite verschoben wurde; sonst der beim Analysieren.
        String spaceKey = snapshot.map(PageSnapshot::spaceKey).orElse(analysis.spaceKey());
        if (!access.canRead(caller, key.sourceId(), spaceKey, key.pageId())) {
            return Optional.empty();
        }
        String reason = snapshot.isEmpty() ? PageStatements.PAGE_MISSING
                : statements.reason(analysis, analysis.ofDocument() ? documentInputs.apply(analysis.document())
                : input(snapshot.get()));
        PageRef ref = new PageRef(key, spaceKey, analysis.title(),
                reason == null ? PageStatements.State.CURRENT : PageStatements.State.STALE, reason,
                analysis.document());
        return Optional.of(new AnalysedPage(ref, analysis));
    }

    private Optional<String> input(PageSnapshot snapshot) {
        String raw = snapshot.payload().rawSha256();
        InputMemo memo = inputs.get(snapshot.key());
        if (memo != null && memo.rawSha256().equals(raw)) {
            return memo.inputSha256();
        }
        Optional<String> input = texts.apply(snapshot).map(PageText::inputSha256);
        inputs.put(snapshot.key(), new InputMemo(raw, input));
        return input;
    }

    private static String matching(ParsedStatements.Mention mention, Set<String> wanted) {
        if (mention.kind() == ParsedStatements.MentionKind.PRONOUN || mention.unresolved()) {
            return null;
        }
        for (String candidate : candidates(mention)) {
            if (wanted.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Set<String> candidates(ParsedStatements.Mention mention) {
        Set<String> candidates = new LinkedHashSet<>();
        if (mention.lemma() != null && !mention.lemma().isBlank()) {
            candidates.add(TermText.normalize(mention.lemma()).strip());
        }
        if (mention.headForm() != null && !mention.headForm().isBlank()) {
            candidates.add(TermText.normalize(mention.headForm()).strip());
        }
        candidates.add(TermText.normalize(mention.text()).strip());
        return candidates;
    }

    /** Normalisierter Schlüssel → Erwähnungen, ohne Pronomen. */
    private static Map<String, List<String>> keys(PageStatementAnalysis analysis) {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        for (ParsedStatements.Mention mention : analysis.result().mentions()) {
            if (mention.kind() == ParsedStatements.MentionKind.PRONOUN || mention.unresolved()
                    || mention.key() == null || mention.key().isBlank()) {
                continue;
            }
            keys.computeIfAbsent(TermText.normalize(mention.key()).strip(), unused -> new ArrayList<>())
                    .add(mention.id());
        }
        return keys;
    }

    /** Beteiligungen an Aussagen der Standardansicht ({@link PageGraph#DEFAULT_QUALITIES}). */
    private static List<Participation> participations(ParsedStatements result, String mentionId) {
        List<Participation> found = new ArrayList<>();
        for (ParsedStatements.Statement statement : result.statements()) {
            if (!PageGraph.DEFAULT_QUALITIES.contains(statement.quality())) {
                continue;
            }
            for (ParsedStatements.Argument argument : statement.arguments()) {
                if (argument.mention().equals(mentionId)) {
                    found.add(new Participation(statement, argument.role(), argument.certain()));
                }
            }
        }
        return found;
    }

    private record InputMemo(String rawSha256, Optional<String> inputSha256) {
    }
}
