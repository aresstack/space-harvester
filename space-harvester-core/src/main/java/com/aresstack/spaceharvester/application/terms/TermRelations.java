package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
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

    /**
     * @param headers Kopfdaten einer Seite im Cache, für die Rechte an Belegen; leer, wenn sie dort nicht liegt
     */
    public TermRelations(SourceCatalog catalog, TermRelationStore store, AccessGuard access,
                         Function<PageKey, Optional<PageHeader>> headers, Clock clock) {
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
     * @param limit    1 bis {@link #MAX_LIMIT}
     * @param cursor   {@code nextCursor} der vorigen Antwort oder {@code null}
     */
    public record RelationSearch(String term, RelationType type, ReviewStatus status, RelationOrigin origin,
                                 String sourceId, int limit, String cursor) {
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
     * @param updated    bestehende Vorschläge und bestätigte Beziehungen dieser Herkunft mit neuen Belegen
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
        if (search.sourceId() != null && catalog.find(search.sourceId()).isEmpty()) {
            throw new UnknownSourceException(search.sourceId());
        }
        String term = search.term() == null || search.term().isBlank() ? null : TermText.normalize(search.term());
        String after = search.cursor() == null ? null : decode(search.cursor());
        Visibility visibility = new Visibility(caller);
        List<TermRelation> sorted = new ArrayList<>(store.all());
        sorted.sort(Comparator.comparing(TermRelation::key, TermIndex.ORDER));
        List<TermRelation> found = new ArrayList<>();
        for (TermRelation relation : sorted) {
            if (after != null && TermIndex.ORDER.compare(relation.key(), after) <= 0
                    || term != null && !relation.touches(term)
                    || search.type() != null && relation.type() != search.type()
                    || search.origin() != null && relation.origin() != search.origin()
                    || (search.status() == null ? relation.status() == ReviewStatus.REJECTED
                    : relation.status() != search.status())
                    || search.sourceId() != null && !relation.scope().global()
                    && !search.sourceId().equals(relation.scope().sourceId())) {
                continue;
            }
            Optional<TermRelation> visible = visibility.visible(relation);
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
    public List<String> expansions(AccessContext caller, String term, String sourceId) {
        String normalized = TermText.normalize(term);
        Visibility visibility = new Visibility(caller);
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
        store.write(List.of(), List.of(id), List.of(new TermRelationStore.AuditEntry(id, "remove",
                caller.jobOwner(), clock.instant(), null, current.revision() + 1)));
        return Optional.empty();
    }

    /**
     * Gleicht die Vorschläge eines Laufs ab (#122, #336), in einem Schritt: neue werden {@code PROPOSED}, bestehende
     * Vorschläge dieser Herkunft bekommen neue Belege, abgelehnte und manuell vorhandene Schlüssel bleiben unberührt,
     * unbestätigte Vorschläge dieser Herkunft und dieses Geltungsbereichs, die der Lauf nicht mehr nennt, entfallen,
     * bestätigte werden nur als {@code evidenceStale} markiert. Scheitert der Lauf vorher, ruft er dies nicht auf, und
     * nichts ändert sich. Rechte und Freigaben prüft der Lauf, bevor er Inhalte liest.
     *
     * @param actor      Kennung des Laufs für Protokoll und {@code createdBy}
     * @param origin     {@link RelationOrigin#RULE} oder {@link RelationOrigin#AI}
     * @param generation Kennung dieses Laufs
     * @throws IllegalArgumentException für {@link RelationOrigin#MANUAL}
     */
    public synchronized ProposalRun propose(String actor, RelationOrigin origin, RelationScope scope,
                                            String generation, List<Proposal> proposals) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(generation, "generation");
        if (origin == RelationOrigin.MANUAL) {
            throw new IllegalArgumentException("Vorschläge sind nie manuell");
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
            } else {
                TermRelation refreshed = current.proposedAgain(proposal.evidence(), generation);
                put.put(id, refreshed);
                audit.add(new TermRelationStore.AuditEntry(id, "propose", actor, now, refreshed.status(),
                        refreshed.revision()));
                updated++;
            }
        }
        for (TermRelation relation : existing.values()) {
            if (relation.origin() != origin || !relation.scope().equals(scope) || named.contains(relation.id())) {
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
        store.write(put.values(), removed, audit);
        return new ProposalRun(added, updated, withdrawn, suppressed, stale);
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
        store.write(List.of(relation), List.of(), List.of(new TermRelationStore.AuditEntry(relation.id(), action,
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

    /** Sichtbarkeit von Beziehungen und Belegen für einen Aufrufer, je Seite und Geltungsbereich einmal geprüft. */
    private final class Visibility {

        private final AccessContext caller;
        private final Map<RelationScope, Boolean> scopes = new HashMap<>();
        private final Map<PageKey, Boolean> pages = new HashMap<>();

        Visibility(AccessContext caller) {
            this.caller = caller;
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
            return access.permits(caller, scope.spaceKey() == null ? ResourceScope.source(scope.sourceId())
                    : ResourceScope.space(scope.sourceId(), scope.spaceKey()), AccessKind.METADATA);
        }

        private boolean readable(RelationEvidence evidence) {
            PageKey key = new PageKey(evidence.sourceId(), evidence.pageId());
            return pages.computeIfAbsent(key, page -> headers.apply(page)
                    .map(header -> access.canRead(caller, page.sourceId(), header.metadata().partitionKey(),
                            page.pageId()))
                    .orElse(false));
        }
    }
}
