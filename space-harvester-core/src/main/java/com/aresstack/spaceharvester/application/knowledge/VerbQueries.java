package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Das Verzeichnis der Verben einer Seitenmenge (Paket K1): je Grundform, in wie vielen Aussagen und auf wie vielen
 * Seiten sie vorkommt, wie die Begriffe im Reiter „Begriffe“.
 *
 * <p>Quelle sind die gespeicherten Aussagen des als Vorgabe eingestellten Analysators ({@link KnowledgeGraph#analysed},
 * mit den Rechten des Aufrufers je Seite). Es zählen nur aktuelle Analysen und nur Aussagen, die nach
 * {@link VerbForms#counts} als Verb zählen; Anhänge und enthaltene Dateien zählen für ihre Seite. Reine Auswertung bei
 * der Abfrage: nichts wird kopiert oder gespeichert, kein Modell, kein Auftrag.
 */
public final class VerbQueries {

    /** Höchstens so viele Einträge je Abfrage. */
    public static final int MAX_LIMIT = 500;

    private final java.util.function.Function<AccessContext, ? extends java.util.Collection<KnowledgeGraph.AnalysedPage>>
            analysed;
    private final java.util.function.BiFunction<AccessContext, PageKey,
            java.util.Optional<KnowledgeGraph.AnalysedPage>> page;
    /** Nur die Analysen dieser Seiten samt ihren Dokumenten, in derselben Reihenfolge wie {@link #analysed}. */
    private final java.util.function.BiFunction<AccessContext, PageSet,
            ? extends java.util.Collection<KnowledgeGraph.AnalysedPage>> some;

    public VerbQueries(KnowledgeGraph graph) {
        this(caller -> Objects.requireNonNull(graph, "graph").analysed(caller), graph::page,
                (caller, pages) -> graph.analysed(caller, pages));
    }

    /** Über die sichtbaren analysierten Seiten, die {@code analysed} je Aufrufer liefert (in Tests erfunden). */
    public VerbQueries(java.util.function.Function<AccessContext,
            ? extends java.util.Collection<KnowledgeGraph.AnalysedPage>> analysed) {
        this(analysed, (caller, key) -> analysed.apply(caller).stream()
                .filter(one -> one.page().document().equals(com.aresstack.spaceharvester.application.assets.DocumentKey.page(key))).findFirst().map(one -> (KnowledgeGraph.AnalysedPage) one),
                (caller, pages) -> analysed.apply(caller).stream()
                        .filter(one -> pages.contains(one.page().key())).toList());
    }

    private VerbQueries(java.util.function.Function<AccessContext,
            ? extends java.util.Collection<KnowledgeGraph.AnalysedPage>> analysed,
                        java.util.function.BiFunction<AccessContext, PageKey,
                                java.util.Optional<KnowledgeGraph.AnalysedPage>> page,
                        java.util.function.BiFunction<AccessContext, PageSet,
                                ? extends java.util.Collection<KnowledgeGraph.AnalysedPage>> some) {
        this.analysed = Objects.requireNonNull(analysed, "analysed");
        this.page = Objects.requireNonNull(page, "page");
        this.some = Objects.requireNonNull(some, "some");
    }

    /**
     * Was eine analysierte Seite (oder eines ihrer Dokumente) zu den Verben beiträgt: Die Seite ist der unterste
     * Verwalter, jede Auswahl darüber addiert nur noch diese Einträge (Angelo 24.11.2026).
     */
    private record PageDigest(PageKey key, boolean current, Map<Key, Integer> statements) {
    }

    /** Die Einträge eines Aufrufers, gültig bis zur nächsten Meldung ({@link #invalidate}). */
    private record Digest(AccessContext caller, List<PageDigest> pages) {
    }

    /**
     * Gemerkt an einer {@link java.lang.ref.SoftReference}: Wird der Speicher knapp, gibt die Laufzeit die Einträge
     * frei, und die nächste Abfrage liest die Analysen neu.
     */
    private volatile java.lang.ref.SoftReference<Digest> digest = new java.lang.ref.SoftReference<>(null);
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean remembering;

    /**
     * Schaltet das Merken ein: Wer die Meldungen anschließt (geänderte Analysen und Prüfungen, geschriebene oder
     * entfernte Seiten), ruft bei jeder {@link #invalidate}. Ohne diesen Anschluss liest jede Abfrage die Analysen neu.
     */
    public VerbQueries remembering() {
        remembering = true;
        return this;
    }

    /** Die gemerkten Einträge gelten nicht mehr; die nächste Abfrage liest neu. Kostet den Melder nichts. */
    public void invalidate() {
        generation.incrementAndGet();
        digest = new java.lang.ref.SoftReference<>(null);
    }

    private List<PageDigest> digest(AccessContext caller) {
        Digest held = remembering ? digest.get() : null;
        if (held != null && Objects.equals(held.caller(), caller)) {
            return held.pages();
        }
        long before = generation.get();
        List<PageDigest> pages = new ArrayList<>();
        for (KnowledgeGraph.AnalysedPage analysed : analysed.apply(caller)) {
            boolean current = analysed.page().state() == PageStatements.State.CURRENT;
            Map<Key, Integer> statements = new HashMap<>();
            if (current) {
                for (ParsedStatements.Statement statement : analysed.analysis().result().statements()) {
                    if (VerbForms.counts(statement)) {
                        statements.merge(new Key(VerbForms.lemma(statement), VerbForms.fromText(statement)), 1,
                                Integer::sum);
                    }
                }
            }
            pages.add(new PageDigest(analysed.page().key(), current, Map.copyOf(statements)));
        }
        if (remembering && before == generation.get()) {
            digest = new java.lang.ref.SoftReference<>(new Digest(caller, pages));
        }
        return pages;
    }

    /**
     * Ein Verb.
     *
     * @param lemma      Grundform in Kleinschreibung ({@link VerbForms#lemma})
     * @param statements Zahl der Aussagen
     * @param pages      Zahl der Seiten mit mindestens einer dieser Aussagen
     * @param fromText   der Analysator lieferte keine Grundform; {@code lemma} ist der Prädikatstext, oft eine
     *                   Flexionsform („Grundform nicht ermittelt“)
     */
    public record Verb(String lemma, int statements, int pages, boolean fromText) {

        public Verb {
            Objects.requireNonNull(lemma, "lemma");
        }
    }

    /**
     * Eine Seite des Verzeichnisses.
     *
     * @param verbs          Verben nach Zahl der Aussagen absteigend, dann Grundform
     * @param nextCursor     Fortsetzung oder {@code null}
     * @param total          Zahl aller Verben zum Filter
     * @param analysedPages  Seiten der Menge mit aktueller Analyse
     * @param stalePages     Seiten der Menge, deren Analyse veraltet ist (zählen nicht)
     * @param unanalysed     Seiten der Menge ohne sichtbare Analyse; ohne Menge 0
     */
    public record VerbList(List<Verb> verbs, String nextCursor, int total, int analysedPages, int stalePages,
                           int unanalysed) {

        public VerbList {
            verbs = List.copyOf(verbs);
        }
    }

    /**
     * Eine Fundstelle eines Verbs: die Aussage auf ihrer Seite.
     *
     * @param page          Seite bzw. Dokument der Analyse
     * @param statementId   Kennung der Aussage
     * @param predicateText das Prädikat, wie es im Text steht
     * @param sentence      Satzindex
     * @param start         Zeichenposition im analysierten Text (inklusive)
     * @param end           Zeichenposition (exklusive)
     */
    public record Occurrence(KnowledgeGraph.PageRef page, String statementId, String predicateText, int sentence,
                             int start, int end) {
    }

    /**
     * Die Verben der Seiten in {@code within} ({@code null}: alle sichtbaren, {@link VerbList#unanalysed()} dann 0),
     * gefiltert nach {@code q} (Teil der Grundform, ohne Groß- und Kleinschreibung; leer: alle), blätternd.
     *
     * @param cursor Fortsetzung aus {@link VerbList#nextCursor()} oder {@code null}
     */
    public VerbList verbs(AccessContext caller, PageSet within, String q, int limit, String cursor) {
        int step = Math.max(1, Math.min(limit, MAX_LIMIT));
        int offset = offset(cursor);
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        Map<Key, Counter> counters = new HashMap<>();
        Set<PageKey> current = new HashSet<>();
        Set<PageKey> stale = new HashSet<>();
        for (PageDigest page : digest(caller)) {
            PageKey key = page.key();
            if (within != null && !within.contains(key)) {
                continue;
            }
            if (!page.current()) {
                stale.add(key);
                continue;
            }
            current.add(key);
            page.statements().forEach((verb, statements) -> {
                if (needle.isEmpty() || verb.lemma().contains(needle)) {
                    Counter counter = counters.computeIfAbsent(verb, k -> new Counter());
                    counter.statements += statements;
                    counter.pages.add(key);
                }
            });
        }
        stale.removeAll(current);
        List<Verb> all = new ArrayList<>(counters.size());
        counters.forEach((key, counter) -> all.add(new Verb(key.lemma(), counter.statements, counter.pages.size(),
                key.fromText())));
        all.sort(Comparator.comparingInt(Verb::statements).reversed().thenComparing(Verb::lemma)
                .thenComparing(Verb::fromText));
        int from = Math.min(offset, all.size());
        int to = Math.min(from + step, all.size());
        int unanalysed = within == null ? 0 : Math.max(0, within.size() - current.size() - stale.size());
        return new VerbList(all.subList(from, to), to < all.size() ? String.valueOf(to) : null, all.size(),
                current.size(), stale.size(), unanalysed);
    }

    /**
     * Die Fundstellen eines Verbs in {@code within}, nach Quelle und Seite geordnet, höchstens {@code limit}.
     *
     * @param fromText wie {@link Verb#fromText()}: nur Aussagen ohne bzw. mit Grundform
     */
    public List<Occurrence> occurrences(AccessContext caller, PageSet within, String lemma, boolean fromText,
                                        int limit) {
        Objects.requireNonNull(lemma, "lemma");
        int max = Math.max(1, Math.min(limit, MAX_LIMIT));
        String wanted = lemma.strip().toLowerCase(Locale.ROOT);
        List<Occurrence> found = new ArrayList<>();
        for (KnowledgeGraph.AnalysedPage analysed : holders(caller, within, new Key(wanted, fromText))) {
            if (within != null && !within.contains(analysed.page().key())
                    || analysed.page().state() != PageStatements.State.CURRENT) {
                continue;
            }
            for (ParsedStatements.Statement statement : analysed.analysis().result().statements()) {
                if (VerbForms.counts(statement) && VerbForms.fromText(statement) == fromText
                        && VerbForms.lemma(statement).equals(wanted)) {
                    found.add(new Occurrence(analysed.page(), statement.id(), statement.predicateText(),
                            statement.sentence(), statement.start(), statement.end()));
                    if (found.size() >= max) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Die Analysen, in denen das Verb stehen kann: mit gemerkten Einträgen nur die Seiten, die es wirklich tragen,
     * statt aller Analysen samt ihren Seiten; sonst alle.
     */
    private java.util.Collection<? extends KnowledgeGraph.AnalysedPage> holders(AccessContext caller, PageSet within,
                                                                                Key verb) {
        if (!remembering) {
            return analysed.apply(caller);
        }
        List<PageKey> holding = new ArrayList<>();
        for (PageDigest page : digest(caller)) {
            if (page.current() && page.statements().containsKey(verb)
                    && (within == null || within.contains(page.key()))) {
                holding.add(page.key());
            }
        }
        return holding.isEmpty() ? List.of() : some.apply(caller, PageSet.ofKeys(holding));
    }

    /**
     * Ein Verb einer einzelnen Seite mit seinen Fundstellen, für den Reiter „Begriffe“ der Seite.
     *
     * @param verb        das Verb, gezählt nur auf dieser Seite
     * @param occurrences die Aussagen in Textreihenfolge
     */
    public record PageVerb(Verb verb, List<Occurrence> occurrences) {

        public PageVerb {
            occurrences = List.copyOf(occurrences);
        }
    }

    /**
     * Die Verben einer Seite.
     *
     * @param verbs die Verben nach Zahl der Aussagen; leer, wenn die Analyse veraltet ist
     * @param stale ob die Analyse nicht mehr zum Text passt; dann zählt die Seite nichts, ihre Zahl ist unbekannt
     */
    public record PageVerbs(List<PageVerb> verbs, boolean stale) {

        public PageVerbs {
            verbs = List.copyOf(verbs);
        }
    }

    /**
     * Die Verben der Seite selbst (ohne Anhänge), aus ihrer aktuellen Analyse, nach Zahl der Aussagen;
     * {@link PageVerbs#stale()} bei veralteter Analyse, {@code null} ohne jede sichtbare Analyse.
     */
    public PageVerbs onPage(AccessContext caller, PageKey key) {
        Objects.requireNonNull(key, "key");
        java.util.Optional<KnowledgeGraph.AnalysedPage> found = page.apply(caller, key);
        if (found.isEmpty()) {
            return null;
        }
        KnowledgeGraph.AnalysedPage analysed = found.get();
        if (analysed.page().state() != PageStatements.State.CURRENT) {
            return new PageVerbs(List.of(), true);
        }
        Map<Key, List<Occurrence>> byVerb = new java.util.LinkedHashMap<>();
        for (ParsedStatements.Statement statement : analysed.analysis().result().statements()) {
            if (VerbForms.counts(statement)) {
                byVerb.computeIfAbsent(new Key(VerbForms.lemma(statement), VerbForms.fromText(statement)),
                        k -> new ArrayList<>()).add(new Occurrence(analysed.page(), statement.id(),
                        statement.predicateText(), statement.sentence(), statement.start(), statement.end()));
            }
        }
        List<PageVerb> verbs = new ArrayList<>();
        byVerb.forEach((k, occurrences) -> {
            occurrences.sort(Comparator.comparingInt(Occurrence::start));
            verbs.add(new PageVerb(new Verb(k.lemma(), occurrences.size(), 1, k.fromText()), occurrences));
        });
        verbs.sort(Comparator.comparing((PageVerb v) -> v.verb().statements()).reversed()
                .thenComparing(v -> v.verb().lemma()));
        return new PageVerbs(verbs, false);
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ungültige Fortsetzung: " + cursor);
        }
    }

    private record Key(String lemma, boolean fromText) {
    }

    private static final class Counter {
        int statements;
        final Set<PageKey> pages = new HashSet<>();
    }
}
