package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.query.SearchExpansion;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Begriffsbeziehungen (#120, ADR 0019): lesen, manuell pflegen, Vorschläge bestätigen, ablehnen und wiederherstellen,
 * Vorschlagsläufe (#122, #336) abgleichen. Ohne KI, ohne Netz; Spring-, REST- und Swing-frei.
 *
 * <p>Regeln:
 * <ul>
 *   <li>Herkunft und Prüfstatus sind getrennt; Bestätigen ändert nur den Status.</li>
 *   <li>Manuell angelegt heißt {@code MANUAL} und {@code CONFIRMED}. Bestätigte und abgelehnte Beziehungen ändert nur
 *       eine Aktion eines Bearbeiters; ein Vorschlagslauf ersetzt oder zieht nur unbestätigte Vorschläge seiner
 *       Herkunft in seinem Geltungsbereich zurück und markiert bestätigte, die er nicht mehr belegt, als
 *       {@code evidenceStale}.</li>
 *   <li>Ablehnen ist eine dauerhafte Sperre für genau diesen Schlüssel (Endpunkte, Art, Geltungsbereich). Entfernen
 *       eines Vorschlags lehnt ihn ab; Entfernen einer bestätigten automatischen Beziehung ebenso, damit sie nicht
 *       sofort wiederkommt. Nur manuell angelegte Beziehungen verschwinden ganz, das Protokoll bleibt.</li>
 * </ul>
 *
 * <p>Rechte: Eine Beziehung mit Quelle braucht {@code metadata} auf Quelle oder Space; Belege zählen nur von Seiten,
 * deren Inhalt der Aufrufer lesen darf, und ein automatischer Vorschlag, dessen Belege alle verborgen sind, ist selbst
 * verborgen, denn er stammt aus deren Inhalt. Ändern braucht {@code sync} auf den Geltungsbereich, global auf jede
 * konfigurierte Quelle. Verborgene Beziehungen gelten als unbekannt.
 */
public class TermRelations {

    /** Höchstens so viele Beziehungen je Antwort. */
    public static final int MAX_LIMIT = 200;
    /** Höchstens so viele Belege je Beziehung. */
    public static final int MAX_EVIDENCE = 20;

    private static final String CURSOR_VERSION = "r1";

    private final SourceCatalog catalog;
    private final TermRelationStore store;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Clock clock;
    private final TermIndex index;
    private final String processorVersion;
    /** Beobachter jeder Änderung ({@link #subscribe}). */
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * @param headers Kopfdaten einer Seite im Cache, für die Rechte an Belegen; leer, wenn sie dort nicht liegt
     */
    public TermRelations(SourceCatalog catalog, TermRelationStore store, AccessGuard access,
                         Function<PageKey, Optional<PageHeader>> headers, Clock clock) {
        this(catalog, store, access, headers, clock, null, null);
    }

    /**
     * @param index            Begriffsindex für {@link RelationSearch#pages()} (BG1, ADR 0029) oder {@code null}
     * @param processorVersion aktuelle Version des Prozessors {@code terms}, mit {@code index}
     */
    public TermRelations(SourceCatalog catalog, TermRelationStore store, AccessGuard access,
                         Function<PageKey, Optional<PageHeader>> headers, Clock clock, TermIndex index,
                         String processorVersion) {
        if ((index == null) != (processorVersion == null)) {
            throw new IllegalArgumentException("index und processorVersion nur zusammen");
        }
        this.index = index;
        this.processorVersion = processorVersion;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Suche nach Beziehungen.
     *
     * @param term     nur Beziehungen mit diesem Begriff an einem Ende (Suchwert) oder {@code null}
     * @param type     nur diese Art oder {@code null}
     * @param status   nur dieser Status; {@code null} heißt Vorschläge und bestätigte, ohne abgelehnte
     * @param origin   nur diese Herkunft oder {@code null}
     * @param sourceId nur globale und für diese Quelle geltende oder {@code null} für alle
     * @param limit      1 bis {@link #MAX_LIMIT}
     * @param cursor     {@code nextCursor} der vorigen Antwort oder {@code null}
     * @param startsWith nur Beziehungen, deren Sortierbegriff ({@link TermRelation#sortTerm()}) so beginnt
     *                   (normalisiert, etwa ein Buchstabe der A–Z-Leiste), oder {@code null}
     * @param pages      nur Beziehungen, von denen mindestens ein Begriff auf einer Seite der Menge vorkommt und
     *                   deren Geltungsbereich diese Seite deckt, etwa eine Quelle oder ein Space samt Unterseiten
     *                   (BG1, ADR 0029); {@code null} ohne diese Grenze. Für genau eine Seite wie {@link #forTerms}.
     */
    public record RelationSearch(String term, RelationType type, ReviewStatus status, RelationOrigin origin,
                                 String sourceId, int limit, String cursor, String startsWith, PageSet pages) {

        public RelationSearch(String term, RelationType type, ReviewStatus status, RelationOrigin origin,
                              String sourceId, int limit, String cursor) {
            this(term, type, status, origin, sourceId, limit, cursor, null, null);
        }

        public RelationSearch(String term, RelationType type, ReviewStatus status, RelationOrigin origin,
                              String sourceId, int limit, String cursor, String startsWith) {
            this(term, type, status, origin, sourceId, limit, cursor, startsWith, null);
        }

        /** Dieselbe Suche, begrenzt auf diese Seiten. */
        public RelationSearch within(PageSet pages) {
            return new RelationSearch(term, type, status, origin, sourceId, limit, cursor, startsWith, pages);
        }
    }

    /** Ein Stapel Beziehungen, nach Schlüssel sortiert. */
    public record RelationList(List<TermRelation> relations, String nextCursor) {

        public RelationList {
            relations = List.copyOf(relations);
        }
    }

    /**
     * Eine manuell angelegte Beziehung.
     *
     * @param note     Notiz oder {@code null}
     * @param evidence Belege oder leer
     */
    public record NewRelation(String fromTerm, String toTerm, RelationType type, RelationScope scope, String note,
                              List<RelationEvidence> evidence) {

        public NewRelation {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            scope = scope == null ? RelationScope.GLOBAL : scope;
        }
    }

    /** Ein Vorschlag eines Laufs. */
    public record Proposal(String fromTerm, String toTerm, RelationType type, List<RelationEvidence> evidence) {

        public Proposal {
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * Ausgang eines Vorschlagslaufs.
     *
     * @param added      neue Vorschläge
     * @param updated    bestehende Vorschläge und bestätigte Beziehungen dieser Herkunft mit geänderten Belegen;
     *                   unverändert belegte zählen nirgends
     * @param withdrawn  unbestätigte Vorschläge, die der Lauf nicht mehr belegt
     * @param suppressed Vorschläge, die eine Ablehnung oder eine manuelle Beziehung unterdrückt
     * @param stale      bestätigte Beziehungen dieser Herkunft, die der Lauf nicht mehr belegt
     */
    public record ProposalRun(int added, int updated, int withdrawn, int suppressed, int stale) {
    }

    /**
     * @throws InvalidQueryException  bei ungültigem Limit oder Cursor
     * @throws UnknownSourceException bei unbekannter {@code sourceId}
     */
    public RelationList relations(AccessContext caller, RelationSearch search) {
        Objects.requireNonNull(search, "search");
        if (search.limit() < 1 || search.limit() > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        String after = search.cursor() == null ? null : decode(search.cursor());
        List<TermRelation> sorted = new ArrayList<>(store.all());
        sorted.sort(Comparator.comparing(TermRelation::key, TermIndex.ORDER));
        Matching matching = new Matching(caller, search);
        List<TermRelation> found = new ArrayList<>();
        for (TermRelation relation : sorted) {
            if (after != null && TermIndex.ORDER.compare(relation.key(), after) <= 0) {
                continue;
            }
            Optional<TermRelation> visible = matching.visible(relation);
            if (visible.isEmpty()) {
                continue;
            }
            if (found.size() == search.limit()) {
                return new RelationList(found, encode(found.get(found.size() - 1).key()));
            }
            found.add(visible.get());
        }
        return new RelationList(found, null);
    }

    /**
     * Wie viele Beziehungen {@link #relations} für diese Suche insgesamt liefert, je Art (BG1b); {@code limit} und
     * {@code cursor} zählen nicht. Dieselben Filter, Vorgabe „ohne abgelehnte“ und Sichtbarkeit.
     *
     * @return Zahl je Art; Arten ohne Beziehung fehlen
     * @throws UnknownSourceException bei unbekannter {@code sourceId}
     */
    public Map<RelationType, Integer> counts(AccessContext caller, RelationSearch search) {
        Objects.requireNonNull(search, "search");
        Matching matching = new Matching(caller, search);
        Map<RelationType, Integer> counts = new java.util.EnumMap<>(RelationType.class);
        for (TermRelation relation : store.all()) {
            matching.visible(relation).ifPresent(visible -> counts.merge(visible.type(), 1, Integer::sum));
        }
        return Map.copyOf(counts);
    }

    /** Filter und Sichtbarkeit einer {@link RelationSearch}, gemeinsam für {@link #relations} und {@link #counts}. */
    private final class Matching {

        private final RelationSearch search;
        private final String term;
        private final String prefix;
        private final Visibility visibility;
        private final Occurrences occurrences;

        Matching(AccessContext caller, RelationSearch search) {
            if (search.sourceId() != null && catalog.find(search.sourceId()).isEmpty()) {
                throw new UnknownSourceException(search.sourceId());
            }
            if (search.pages() != null && index == null) {
                throw new IllegalStateException("Ohne Begriffsindex keine Suche je Seitenmenge");
            }
            this.search = search;
            this.term = search.term() == null || search.term().isBlank() ? null : TermText.normalize(search.term());
            this.prefix = search.startsWith() == null || search.startsWith().isBlank() ? null
                    : TermText.normalize(search.startsWith());
            this.visibility = new Visibility(caller, id -> search.sourceId() != null && search.sourceId().equals(id)
                    || search.pages() != null && search.pages().sourceIds().contains(id)
                    || catalog.includeInGlobalSearch(id));
            this.occurrences = search.pages() == null ? null : new Occurrences(caller, search.pages());
        }

        Optional<TermRelation> visible(TermRelation relation) {
            if (term != null && !relation.touches(term)
                    || prefix != null && !relation.sortTerm().startsWith(prefix)
                    || search.type() != null && relation.type() != search.type()
                    || search.origin() != null && relation.origin() != search.origin()
                    || (search.status() == null ? relation.status() == ReviewStatus.REJECTED
                    : relation.status() != search.status())
                    || search.sourceId() != null && !relation.scope().global()
                    && !search.sourceId().equals(relation.scope().sourceId())
                    || occurrences != null && !occurrences.covered(relation)) {
                return Optional.empty();
            }
            return visibility.visible(relation);
        }
    }

    /**
     * Die nicht abgelehnten Beziehungen der Begriffe einer Seite, für den Reiter „Begriffe“ der Leseansicht.
     *
     * @param normalized Suchwerte der Begriffe dieser Seite ({@link PageTerms})
     * @param sourceId   Quelle der Seite
     * @param spaceKey   Space der Seite oder {@code null}
     */
    public List<TermRelation> forTerms(AccessContext caller, Collection<String> normalized, String sourceId,
                                       String spaceKey) {
        Set<String> terms = new HashSet<>(normalized);
        Visibility visibility = new Visibility(caller);
        List<TermRelation> found = new ArrayList<>();
        for (TermRelation relation : store.all()) {
            if (relation.status() == ReviewStatus.REJECTED || !relation.scope().covers(sourceId, spaceKey)
                    || !(terms.contains(TermText.normalize(relation.fromTerm()))
                    || terms.contains(TermText.normalize(relation.toTerm())))) {
                continue;
            }
            visibility.visible(relation).ifPresent(found::add);
        }
        found.sort(Comparator.comparing(TermRelation::key, TermIndex.ORDER));
        return found;
    }

    /**
     * Begriffe, um die eine Suche nach {@code term} ausgeweitet werden darf: die anderen Enden bestätigter Beziehungen
     * außer {@link RelationType#RELATED}, global oder für diese Quelle. Nie transitiv; Fundstellen der Ausweitung
     * prüft die Suche selbst.
     */
    /**
     * Für die Suche (#336, Teil 4): die bestätigten Gegenstücke eines Begriffs im Umfang der Suche. Global gilt
     * überall; eine Beziehung der Quelle gilt in der Quelle und in jedem ihrer Spaces; eine Beziehung eines Space nur
     * bei einer Suche in genau diesem Space. Nie Vorschläge, Abgelehntes oder „verwandt“; nicht transitiv.
     *
     * @param sourceId Quelle der Suche oder {@code null} für alle Quellen (dann nur globale Beziehungen)
     * @param spaceKey Space der Suche oder {@code null} für die ganze Quelle
     */
    public List<String> expansions(AccessContext caller, String term, String sourceId, String spaceKey) {
        String normalized = TermText.normalize(term);
        Visibility visibility = new Visibility(caller, id -> id.equals(sourceId) || catalog.includeInGlobalSearch(id));
        Set<String> found = new LinkedHashSet<>();
        for (TermRelation relation : store.all()) {
            RelationScope scope = relation.scope();
            boolean inScope = scope.global()
                    || sourceId != null && scope.sourceId().equals(sourceId)
                    && (scope.spaceKey() == null || scope.spaceKey().equals(spaceKey));
            if (inScope && relation.status() == ReviewStatus.CONFIRMED && relation.type().expandsSearch()
                    && relation.touches(normalized) && visibility.visible(relation).isPresent()) {
                found.add(relation.other(normalized));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Wie {@link #expansions(AccessContext, String, String, String)}, je Gegenstück mit der Art seiner Beziehung (R1);
     * gibt es mehrere Beziehungen zum selben Gegenstück, die erste.
     */
    public List<SearchExpansion.Counterpart> counterparts(AccessContext caller, String term, String sourceId,
                                                          String spaceKey) {
        String normalized = TermText.normalize(term);
        Visibility visibility = new Visibility(caller, id -> id.equals(sourceId) || catalog.includeInGlobalSearch(id));
        Map<String, SearchExpansion.Counterpart> found = new LinkedHashMap<>();
        for (TermRelation relation : store.all()) {
            RelationScope scope = relation.scope();
            boolean inScope = scope.global()
                    || sourceId != null && scope.sourceId().equals(sourceId)
                    && (scope.spaceKey() == null || scope.spaceKey().equals(spaceKey));
            if (inScope && relation.status() == ReviewStatus.CONFIRMED && relation.type().expandsSearch()
                    && relation.touches(normalized) && visibility.visible(relation).isPresent()) {
                String other = relation.other(normalized);
                found.putIfAbsent(other, new SearchExpansion.Counterpart(other, relation.type().id()));
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * Für die Suchmethode „Synonyme der Fachbegriffe“ (K5a): die VORGESCHLAGENEN Synonyme eines Begriffs im Umfang der
     * Suche (Umfang wie {@link #counterparts}). Bestätigte liefert schon {@link #counterparts}, Abgelehntes nie.
     */
    public List<String> proposedSynonyms(AccessContext caller, String term, String sourceId, String spaceKey) {
        String normalized = TermText.normalize(term);
        Visibility visibility = new Visibility(caller, id -> id.equals(sourceId) || catalog.includeInGlobalSearch(id));
        Set<String> found = new LinkedHashSet<>();
        for (TermRelation relation : store.all()) {
            RelationScope scope = relation.scope();
            boolean inScope = scope.global()
                    || sourceId != null && scope.sourceId().equals(sourceId)
                    && (scope.spaceKey() == null || scope.spaceKey().equals(spaceKey));
            if (inScope && relation.status() == ReviewStatus.PROPOSED && relation.type() == RelationType.SYNONYM
                    && relation.touches(normalized) && visibility.visible(relation).isPresent()) {
                found.add(relation.other(normalized));
            }
        }
        return List.copyOf(found);
    }

    public List<String> expansions(AccessContext caller, String term, String sourceId) {
        String normalized = TermText.normalize(term);
        Visibility visibility = new Visibility(caller, id -> id.equals(sourceId) || catalog.includeInGlobalSearch(id));
        Set<String> found = new LinkedHashSet<>();
        for (TermRelation relation : store.all()) {
            if (relation.status() == ReviewStatus.CONFIRMED && relation.type().expandsSearch()
                    && relation.touches(normalized)
                    && (relation.scope().global() || relation.scope().sourceId().equals(sourceId))
                    && visibility.visible(relation).isPresent()) {
                found.add(relation.other(normalized));
            }
        }
        return List.copyOf(found);
    }

    /**
     * @throws UnknownRelationException wenn es sie nicht gibt oder sie verborgen ist
     */
    public TermRelation relation(AccessContext caller, String id) {
        return store.find(id).flatMap(new Visibility(caller)::visible)
                .orElseThrow(() -> new UnknownRelationException(id));
    }

    /** Protokoll einer sichtbaren Beziehung, älteste zuerst. */
    public List<TermRelationStore.AuditEntry> history(AccessContext caller, String id) {
        relation(caller, id);
        return store.history(id);
    }

    /**
     * Legt eine Beziehung manuell an ({@code MANUAL}, {@code CONFIRMED}). Gibt es den Schlüssel schon, wird er
     * bestätigt, seine Herkunft bleibt; das ist idempotent.
     *
     * @throws InvalidQueryException   bei leeren, gleichen oder zu langen Begriffen oder zu vielen Belegen
     * @throws UnknownSourceException  bei unbekannter Quelle im Geltungsbereich
     * @throws AccessDeniedException   ohne {@code sync} auf den Geltungsbereich
     */
    public synchronized TermRelation add(AccessContext caller, NewRelation relation) {
        Objects.requireNonNull(relation, "relation");
        // Erst die Rechte, damit eine Antwort ohne Freigabe nichts über die Prüfung verrät.
        requireWrite(caller, relation.scope());
        validate(relation.fromTerm(), relation.toTerm(), relation.type(), relation.evidence());
        String key = TermRelation.key(relation.fromTerm(), relation.toTerm(), relation.type(), relation.scope());
        String id = TermRelation.idOf(key);
        String actor = caller.jobOwner();
        Optional<TermRelation> existing = store.find(id);
        if (existing.isPresent()) {
            TermRelation current = existing.get();
            if (current.status() == ReviewStatus.CONFIRMED) {
                return current;
            }
            return save(current.with(ReviewStatus.CONFIRMED, actor, clock.instant()), "confirm", actor);
        }
        TermRelation created = new TermRelation(id, relation.fromTerm().trim(), relation.toTerm().trim(),
                relation.type(), relation.scope(), RelationOrigin.MANUAL, ReviewStatus.CONFIRMED,
                relation.evidence(), blankToNull(relation.note()), null, false, actor, clock.instant(), actor,
                clock.instant(), 1);
        return save(created, "create", actor);
    }

    /**
     * Bestätigt eine Beziehung.
     *
     * @param expectedRevision Revision, die der Bearbeiter gesehen hat, oder {@code null}
     * @throws UnknownRelationException  wenn es sie nicht gibt oder sie verborgen ist
     * @throws RelationConflictException wenn sie inzwischen eine andere Revision hat
     * @throws AccessDeniedException     ohne {@code sync} auf ihren Geltungsbereich
     */
    public synchronized TermRelation confirm(AccessContext caller, String id, Long expectedRevision) {
        TermRelation current = writable(caller, id, expectedRevision);
        if (current.status() == ReviewStatus.CONFIRMED) {
            return current;
        }
        return save(current.with(ReviewStatus.CONFIRMED, caller.jobOwner(), clock.instant()), "confirm",
                caller.jobOwner());
    }

    /** Lehnt eine Beziehung dauerhaft ab; Fehler wie {@link #confirm}. */
    public synchronized TermRelation reject(AccessContext caller, String id, Long expectedRevision) {
        TermRelation current = writable(caller, id, expectedRevision);
        if (current.status() == ReviewStatus.REJECTED) {
            return current;
        }
        return save(current.with(ReviewStatus.REJECTED, caller.jobOwner(), clock.instant()), "reject",
                caller.jobOwner());
    }

    /**
     * Hebt eine Ablehnung auf: Ein automatischer Vorschlag wird wieder Vorschlag, eine manuelle Beziehung wieder
     * bestätigt. Fehler wie {@link #confirm}.
     */
    public synchronized TermRelation restore(AccessContext caller, String id, Long expectedRevision) {
        TermRelation current = writable(caller, id, expectedRevision);
        if (current.status() != ReviewStatus.REJECTED) {
            return current;
        }
        ReviewStatus restored = current.origin() == RelationOrigin.MANUAL ? ReviewStatus.CONFIRMED
                : ReviewStatus.PROPOSED;
        return save(current.with(restored, caller.jobOwner(), clock.instant()), "restore", caller.jobOwner());
    }

    /**
     * Entfernt eine Beziehung auf ausdrücklichen Wunsch: eine manuelle ganz, eine automatische als Ablehnung, damit
     * sie nicht wiederkommt. Fehler wie {@link #confirm}.
     *
     * @return die verbleibende, abgelehnte Beziehung oder leer, wenn sie ganz entfernt ist
     */
    public synchronized Optional<TermRelation> remove(AccessContext caller, String id, Long expectedRevision) {
        TermRelation current = writable(caller, id, expectedRevision);
        if (current.origin() != RelationOrigin.MANUAL) {
            if (current.status() == ReviewStatus.REJECTED) {
                return Optional.of(current);
            }
            return Optional.of(save(current.with(ReviewStatus.REJECTED, caller.jobOwner(), clock.instant()),
                    "reject", caller.jobOwner()));
        }
        write(List.of(), List.of(id), List.of(new TermRelationStore.AuditEntry(id, "remove",
                caller.jobOwner(), clock.instant(), null, current.revision() + 1)));
        return Optional.empty();
    }

    /**
     * Gleicht die Vorschläge eines Laufs ab (#122, #336), in einem Schritt: neue werden {@code PROPOSED}, bestehende
     * Vorschläge dieser Herkunft bekommen neue Belege, abgelehnte und manuell vorhandene Schlüssel bleiben unberührt,
     * unbestätigte Vorschläge dieser Herkunft, dieses Geltungsbereichs und dieser Arten, die der Lauf nicht mehr
     * nennt, entfallen, bestätigte werden nur als {@code evidenceStale} markiert. Scheitert der Lauf vorher, ruft er
     * dies nicht auf, und nichts ändert sich. Rechte und Freigaben prüft der Lauf, bevor er Inhalte liest.
     *
     * <p>Die Arten trennen unabhängige Erzeuger: Die Akronymläufe (#122) erzeugen nur {@code ABBREVIATION}, der
     * Synonymlauf (#336) nur {@code SYNONYM}, {@code VARIANT} und {@code RELATED}. So zieht ein Synonymlauf derselben
     * Herkunft im selben Geltungsbereich nie Akronymvorschläge zurück und umgekehrt.
     *
     * @param actor      Kennung des Laufs für Protokoll und {@code createdBy}
     * @param origin     {@link RelationOrigin#RULE} oder {@link RelationOrigin#AI}
     * @param types      die Arten, die dieser Lauf erzeugt; nur Vorschläge dieser Arten gleicht er ab
     * @param generation Kennung dieses Laufs
     * @throws IllegalArgumentException für {@link RelationOrigin#MANUAL}, ohne Arten oder bei einem Vorschlag einer
     *                                  anderen Art
     */
    public synchronized ProposalRun propose(String actor, RelationOrigin origin, RelationScope scope,
                                            Set<RelationType> types, String generation, List<Proposal> proposals) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(generation, "generation");
        if (origin == RelationOrigin.MANUAL) {
            throw new IllegalArgumentException("Vorschläge sind nie manuell");
        }
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("Ein Lauf nennt die Arten, die er erzeugt");
        }
        for (Proposal proposal : proposals) {
            if (proposal.type() != null && !types.contains(proposal.type())) {
                throw new IllegalArgumentException("Vorschlag der Art " + proposal.type().id()
                        + " in einem Lauf für " + types);
            }
        }
        Map<String, TermRelation> existing = new HashMap<>();
        store.all().forEach(relation -> existing.put(relation.id(), relation));
        Map<String, TermRelation> put = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        List<TermRelationStore.AuditEntry> audit = new ArrayList<>();
        Set<String> named = new HashSet<>();
        int added = 0;
        int updated = 0;
        int suppressed = 0;
        int withdrawn = 0;
        int stale = 0;
        var now = clock.instant();
        for (Proposal proposal : proposals) {
            try {
                validate(proposal.fromTerm(), proposal.toTerm(), proposal.type(), proposal.evidence());
            } catch (InvalidQueryException e) {
                suppressed++;
                continue;
            }
            String id = TermRelation.idOf(TermRelation.key(proposal.fromTerm(), proposal.toTerm(), proposal.type(),
                    scope));
            if (!named.add(id)) {
                continue;
            }
            TermRelation current = put.containsKey(id) ? put.get(id) : existing.get(id);
            if (current == null) {
                TermRelation created = new TermRelation(id, proposal.fromTerm().trim(), proposal.toTerm().trim(),
                        proposal.type(), scope, origin, ReviewStatus.PROPOSED, proposal.evidence(), null, generation,
                        false, actor, now, null, null, 1);
                put.put(id, created);
                audit.add(new TermRelationStore.AuditEntry(id, "propose", actor, now, ReviewStatus.PROPOSED, 1));
                added++;
            } else if (current.status() == ReviewStatus.REJECTED || current.origin() != origin) {
                // Benutzerentscheidungen und andere Herkunft haben Vorrang.
                suppressed++;
            } else if (current.evidence().equals(proposal.evidence()) && !current.evidenceStale()) {
                // Unverändert belegt: kein Schreiben, keine Protokollzeile, so bleibt ein Lauf je Abgleich billig.
                continue;
            } else {
                TermRelation refreshed = current.proposedAgain(proposal.evidence(), generation);
                put.put(id, refreshed);
                audit.add(new TermRelationStore.AuditEntry(id, "propose", actor, now, refreshed.status(),
                        refreshed.revision()));
                updated++;
            }
        }
        for (TermRelation relation : existing.values()) {
            if (relation.origin() != origin || !relation.scope().equals(scope) || !types.contains(relation.type())
                    || named.contains(relation.id())) {
                continue;
            }
            if (relation.status() == ReviewStatus.PROPOSED) {
                removed.add(relation.id());
                audit.add(new TermRelationStore.AuditEntry(relation.id(), "withdraw", actor, now, null,
                        relation.revision() + 1));
                withdrawn++;
            } else if (relation.status() == ReviewStatus.CONFIRMED && !relation.evidenceStale()) {
                TermRelation marked = relation.stale(true);
                put.put(relation.id(), marked);
                audit.add(new TermRelationStore.AuditEntry(relation.id(), "stale", actor, now, marked.status(),
                        marked.revision()));
                stale++;
            }
        }
        if (!put.isEmpty() || !removed.isEmpty()) {
            write(put.values(), removed, audit);
        }
        return new ProposalRun(added, updated, withdrawn, suppressed, stale);
    }

    /**
     * „Daten bereinigen“ einer Quelle, ohne Rechteprüfung: Beziehungen mit Geltungsbereich in dieser Quelle entfallen
     * ganz, alle übrigen verlieren ihre Belege aus dieser Quelle. Eine automatische Beziehung (Regel, KI) ohne
     * verbleibenden Beleg entfällt ebenfalls; eine manuelle, quellübergreifende bleibt, sie stammt vom Bearbeiter.
     *
     * @param actor Kennung des Laufs für das Protokoll
     * @return Anzahl der entfernten Beziehungen
     */
    public synchronized int removeSource(String sourceId, String actor) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(actor, "actor");
        List<TermRelation> put = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<TermRelationStore.AuditEntry> audit = new ArrayList<>();
        var now = clock.instant();
        for (TermRelation relation : store.all()) {
            boolean scoped = sourceId.equals(relation.scope().sourceId());
            boolean evidenced = relation.evidence().stream().anyMatch(e -> e.sourceId().equals(sourceId));
            if (!scoped && !evidenced) {
                continue;
            }
            TermRelation stripped = relation.withoutEvidenceOf(sourceId);
            if (scoped || stripped.evidence().isEmpty() && relation.origin() != RelationOrigin.MANUAL) {
                removed.add(relation.id());
                audit.add(new TermRelationStore.AuditEntry(relation.id(), "purge", actor, now, null,
                        relation.revision() + 1));
            } else {
                put.add(stripped);
                audit.add(new TermRelationStore.AuditEntry(relation.id(), "purge", actor, now, stripped.status(),
                        stripped.revision()));
            }
        }
        if (!put.isEmpty() || !removed.isEmpty()) {
            write(put, removed, audit);
        }
        return removed.size();
    }

    /** Alle Beziehungen, auch abgelehnte, ohne Rechteprüfung; nur für Vorschlagsläufe (#336, Stufe A). */
    List<TermRelation> stored() {
        return List.copyOf(store.all());
    }

    /** Die Beziehungen einer Herkunft in genau diesem Geltungsbereich, ohne Rechteprüfung; nur für Vorschlagsläufe. */
    List<TermRelation> automatic(RelationOrigin origin, RelationScope scope) {
        List<TermRelation> found = new ArrayList<>();
        for (TermRelation relation : store.all()) {
            if (relation.origin() == origin && relation.scope().equals(scope)) {
                found.add(relation);
            }
        }
        return found;
    }

    private TermRelation writable(AccessContext caller, String id, Long expectedRevision) {
        TermRelation current = relation(caller, id);
        requireWrite(caller, current.scope());
        if (expectedRevision != null && expectedRevision != current.revision()) {
            throw new RelationConflictException(id, current.revision());
        }
        return current;
    }

    private TermRelation save(TermRelation relation, String action, String actor) {
        write(List.of(relation), List.of(), List.of(new TermRelationStore.AuditEntry(relation.id(), action,
                actor, clock.instant(), relation.status(), relation.revision())));
        return relation;
    }

    private void requireWrite(AccessContext caller, RelationScope scope) {
        if (!scope.global()) {
            if (catalog.find(scope.sourceId()).isEmpty()) {
                throw new UnknownSourceException(scope.sourceId());
            }
            access.require(caller, scope.spaceKey() == null ? ResourceScope.source(scope.sourceId())
                    : ResourceScope.space(scope.sourceId(), scope.spaceKey()), AccessKind.SYNC);
            return;
        }
        List<HarvestSource> sources = catalog.all();
        if (sources.isEmpty()) {
            throw new InvalidQueryException("Globale Beziehungen brauchen mindestens eine Quelle");
        }
        sources.forEach(source -> access.require(caller, ResourceScope.source(source.id()), AccessKind.SYNC));
    }

    private static void validate(String fromTerm, String toTerm, RelationType type, List<RelationEvidence> evidence) {
        if (fromTerm == null || toTerm == null || type == null) {
            throw new InvalidQueryException("fromTerm, toTerm und type sind Pflicht");
        }
        if (!TermText.usable(fromTerm.trim()) || !TermText.usable(toTerm.trim())) {
            throw new InvalidQueryException("Begriffe müssen 1 bis 200 Zeichen ohne Steuerzeichen haben");
        }
        if (TermText.normalize(fromTerm).equals(TermText.normalize(toTerm))) {
            throw new InvalidQueryException("Eine Beziehung braucht zwei verschiedene Begriffe");
        }
        if (evidence.size() > MAX_EVIDENCE) {
            throw new InvalidQueryException("Höchstens " + MAX_EVIDENCE + " Belege");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String encode(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((CURSOR_VERSION + '\u0000' + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!value.startsWith(CURSOR_VERSION + '\u0000')) {
                throw new InvalidQueryException("cursor ist beschädigt");
            }
            return value.substring(CURSOR_VERSION.length() + 1);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("cursor ist beschädigt");
        }
    }

    /**
     * Wo die Begriffe einer Seitenmenge vorkommen (BG1): je Suchwert die lesbaren Seiten der Menge mit Quelle und
     * Space, aus dem Begriffsindex, nur Seiten selbst. Je Suchwert einmal gefragt.
     */
    private final class Occurrences {

        private final AccessContext caller;
        private final PageSet pages;
        private Map<String, List<PageScope>> byTerm;
        private final Map<PageKey, Optional<PageScope>> scopes = new HashMap<>();

        Occurrences(AccessContext caller, PageSet pages) {
            this.caller = caller;
            this.pages = pages;
        }

        /** Mindestens ein Begriff kommt auf einer Seite der Menge vor, deren Ort der Geltungsbereich deckt. */
        boolean covered(TermRelation relation) {
            return covered(relation, TermText.normalize(relation.fromTerm()))
                    || covered(relation, TermText.normalize(relation.toTerm()));
        }

        private boolean covered(TermRelation relation, String normalized) {
            return byTerm().getOrDefault(normalized, List.of()).stream()
                    .anyMatch(page -> relation.scope().covers(page.sourceId(), page.spaceKey()));
        }

        /**
         * Wo die Begriffe aller Beziehungen in der Seitenmenge vorkommen, mit einer Abfrage für alle zusammen und nur
         * über Schlüssel. Vorher lief je Begriff eine eigene Abfrage über die ganze Seitenmenge, die jede Fundstelle
         * vollständig las: bei vielen Beziehungen der teure Teil von Liste und Zahl (Angelo 24.11.2026).
         */
        private Map<String, List<PageScope>> byTerm() {
            if (byTerm != null) {
                return byTerm;
            }
            Set<String> wanted = new HashSet<>();
            for (TermRelation relation : store.all()) {
                for (String term : List.of(relation.fromTerm(), relation.toTerm())) {
                    String normalized = TermText.normalize(term);
                    if (TermText.usable(normalized)) {
                        wanted.add(normalized);
                    }
                }
            }
            Map<String, Set<PageScope>> found = new HashMap<>();
            if (!pages.isEmpty() && !wanted.isEmpty()) {
                TermIndex.Filter filter = new TermIndex.Filter(pages.sourceIds(), null, null, null, processorVersion,
                        null, TermOrigin.PAGES, pages).anyOf(wanted);
                String from = null;
                while (true) {
                    List<TermIndex.TermKey> batch = index.keys(filter, from, TermIndex.MAX_KEY_BATCH);
                    for (TermIndex.TermKey key : batch) {
                        scopes.computeIfAbsent(key.page(), this::scope).ifPresent(scope -> found
                                .computeIfAbsent(key.normalized(), term -> new LinkedHashSet<>()).add(scope));
                    }
                    if (batch.size() < TermIndex.MAX_KEY_BATCH) {
                        break;
                    }
                    from = batch.get(batch.size() - 1).sortKey();
                }
            }
            Map<String, List<PageScope>> lists = new HashMap<>();
            found.forEach((term, where) -> lists.put(term, List.copyOf(where)));
            byTerm = lists;
            return lists;
        }

        private Optional<PageScope> scope(PageKey key) {
            return headers.apply(key).filter(header -> access.canRead(caller, key.sourceId(),
                    header.metadata().partitionKey(), key.pageId()))
                    .map(header -> new PageScope(key.sourceId(), header.metadata().partitionKey()));
        }
    }

    private record PageScope(String sourceId, String spaceKey) {
    }

    /** Sichtbarkeit von Beziehungen und Belegen für einen Aufrufer, je Seite und Geltungsbereich einmal geprüft. */
    private final class Visibility {

        private final AccessContext caller;
        private final java.util.function.Predicate<String> included;
        private final Map<RelationScope, Boolean> scopes = new HashMap<>();
        private final Map<PageKey, Boolean> pages = new HashMap<>();

        Visibility(AccessContext caller) {
            this(caller, id -> true);
        }

        Visibility(AccessContext caller, java.util.function.Predicate<String> included) {
            this.caller = caller;
            this.included = included;
        }

        Optional<TermRelation> visible(TermRelation relation) {
            if (!scopes.computeIfAbsent(relation.scope(), this::scope)) {
                return Optional.empty();
            }
            List<RelationEvidence> evidence = relation.evidence().stream().filter(this::readable).toList();
            if (relation.origin() != RelationOrigin.MANUAL && !relation.evidence().isEmpty() && evidence.isEmpty()) {
                return Optional.empty();
            }
            if (evidence.size() == relation.evidence().size()) {
                return Optional.of(relation);
            }
            return Optional.of(new TermRelation(relation.id(), relation.fromTerm(), relation.toTerm(),
                    relation.type(), relation.scope(), relation.origin(), relation.status(), evidence,
                    relation.note(), relation.generation(), relation.evidenceStale(), relation.createdBy(),
                    relation.createdAt(), relation.reviewedBy(), relation.reviewedAt(), relation.revision()));
        }

        private boolean scope(RelationScope scope) {
            if (scope.global()) {
                return caller != null;
            }
            // Abgeschaltete Quellen nehmen an nichts teil, auch nicht mit schon abgelegten Beziehungen.
            if (!included.test(scope.sourceId()) || catalog.find(scope.sourceId()).isEmpty()) {
                return false;
            }
            return access.permits(caller, scope.spaceKey() == null ? ResourceScope.source(scope.sourceId())
                    : ResourceScope.space(scope.sourceId(), scope.spaceKey()), AccessKind.METADATA);
        }

        private boolean readable(RelationEvidence evidence) {
            PageKey key = new PageKey(evidence.sourceId(), evidence.pageId());
            return pages.computeIfAbsent(key, page -> included.test(page.sourceId())
                    && catalog.find(page.sourceId()).isPresent() && headers.apply(page)
                    .map(header -> access.canRead(caller, page.sourceId(), header.metadata().partitionKey(),
                            page.pageId()))
                    .orElse(false));
        }
    }

    /**
     * Meldet jede Änderung der Beziehungen (anlegen, prüfen, entfernen, Vorschlagsläufe), damit eine Ansicht neu lädt,
     * sobald es Neues gibt (ADR 0027, kein Abfragen in Abständen). Der Beobachter läuft im schreibenden Thread und
     * muss schnell sein.
     *
     * @return meldet den Beobachter wieder ab
     */
    public Runnable subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void write(Collection<TermRelation> put, Collection<String> removed,
                       List<TermRelationStore.AuditEntry> audit) {
        store.write(put, removed, audit);
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // Ein fehlerhafter Beobachter hält die Beziehungen nicht auf.
            }
        }
    }
}
