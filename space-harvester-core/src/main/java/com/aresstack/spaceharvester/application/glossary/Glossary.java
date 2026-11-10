package com.aresstack.spaceharvester.application.glossary;

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
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Das Fachglossar (#122 Stufe 2, ADR 0019): Fachbegriffe je Geltungsbereich mit Herkunft, Prüfstatus, Belegen und
 * Protokoll. Pflege von Hand jederzeit, auch ohne Modell und für Begriffe, die der Begriffsindex nicht kennt; KI-Läufe
 * ({@link GlossaryClassification}) gleichen nur über {@link #propose} ab.
 *
 * <p>Schutzregeln: Manuelle Einträge und Benutzerentscheidungen ändert kein Lauf. Bestätigt bleibt bestätigt,
 * abgelehnt bleibt abgelehnt (auch über Neustarts und Modellwechsel, die Sperre hängt am Begriff und Geltungsbereich,
 * nicht am Modell). Nur ungeprüfte KI-Vorschläge ersetzt oder entfernt ein neuer Lauf; ein bestätigter Eintrag ohne
 * Beleg wird nur als „Beleg veraltet“ markiert. Ablehnungen lassen sich bewusst aufheben.
 *
 * <p>Lesen braucht {@code metadata} auf den Geltungsbereich, Belege nur, wenn der Aufrufer die Seite lesen darf;
 * schreiben braucht {@code sync} wie bei Begriffsbeziehungen.
 */
public class Glossary {

    public static final int MAX_LIMIT = 200;
    public static final int MAX_EVIDENCE = 20;
    public static final int MAX_EXPLANATION_CHARS = 2000;
    private static final String CURSOR_VERSION = "g1";

    private final SourceCatalog catalog;
    private final GlossaryStore store;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Clock clock;

    /** @param headers Kopfdaten einer Seite im Cache, für die Rechte an Belegen */
    public Glossary(SourceCatalog catalog, GlossaryStore store, AccessGuard access,
                    Function<PageKey, Optional<PageHeader>> headers, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Suche im Glossar.
     *
     * @param q          Präfix des Suchwerts oder eines seiner Wörter, oder {@code null}
     * @param status     nur dieser Status; {@code null} heißt Vorschläge und bestätigte, ohne abgelehnte
     * @param origin     nur diese Herkunft oder {@code null}
     * @param sourceId   nur globale und für diese Quelle geltende, oder {@code null}
     * @param spaceKey   mit {@code sourceId}: nur Einträge, die für diesen Space gelten, oder {@code null}
     * @param startsWith nur Suchwerte, die so beginnen (A–Z-Leiste), oder {@code null}
     * @param limit      1 bis {@link #MAX_LIMIT}
     * @param cursor     {@code nextCursor} der vorigen Antwort oder {@code null}
     */
    public record Search(String q, ReviewStatus status, RelationOrigin origin, String sourceId, String spaceKey,
                         String startsWith, int limit, String cursor) {
    }

    /** Ein Stapel Einträge, sortiert nach Suchwert; {@code nextCursor} ist {@code null} am Ende. */
    public record Page(List<GlossaryEntry> entries, String nextCursor) {
    }

    /**
     * Ein manueller Eintrag.
     *
     * @param evidence höchstens {@link #MAX_EVIDENCE} Belege, auch keiner
     */
    public record NewEntry(String term, RelationScope scope, String explanation, List<RelationEvidence> evidence) {

        public NewEntry {
            Objects.requireNonNull(scope, "scope");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /** Ein Vorschlag eines Laufs: Begriff mit den Belegen, die das Modell sah. */
    public record Proposal(String term, List<RelationEvidence> evidence) {

        public Proposal {
            evidence = List.copyOf(evidence);
        }
    }

    /** Ergebnis eines Abgleichs. */
    public record ProposalRun(int added, int updated, int withdrawn, int suppressed, int stale) {
    }

    /** @throws UnknownSourceException bei unbekannter Quelle */
    public Page entries(AccessContext caller, Search search) {
        Objects.requireNonNull(search, "search");
        if (search.limit() < 1 || search.limit() > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        if (search.sourceId() != null && catalog.find(search.sourceId()).isEmpty()) {
            throw new UnknownSourceException(search.sourceId());
        }
        String q = blank(search.q()) ? null : TermText.normalize(search.q());
        String startsWith = blank(search.startsWith()) ? null : TermText.normalize(search.startsWith());
        String after = search.cursor() == null ? null : decode(search.cursor());
        Visibility visibility = new Visibility(caller);
        List<GlossaryEntry> sorted = new ArrayList<>(store.all());
        sorted.sort(Comparator.comparing(Glossary::sortKey, TermIndex.ORDER));
        List<GlossaryEntry> found = new ArrayList<>();
        for (GlossaryEntry entry : sorted) {
            if (after != null && TermIndex.ORDER.compare(sortKey(entry), after) <= 0
                    || q != null && !matches(entry.normalized(), q)
                    || startsWith != null && !entry.normalized().startsWith(startsWith)
                    || search.origin() != null && entry.origin() != search.origin()
                    || (search.status() == null ? entry.status() == ReviewStatus.REJECTED
                    : entry.status() != search.status())
                    || search.sourceId() != null && !entry.scope().global()
                    && !(search.sourceId().equals(entry.scope().sourceId()) && (search.spaceKey() == null
                    || entry.scope().spaceKey() == null || search.spaceKey().equals(entry.scope().spaceKey())))) {
                continue;
            }
            Optional<GlossaryEntry> visible = visibility.visible(entry);
            if (visible.isEmpty()) {
                continue;
            }
            if (found.size() == search.limit()) {
                return new Page(found, encode(sortKey(found.get(found.size() - 1))));
            }
            found.add(visible.get());
        }
        return new Page(found, null);
    }

    /** @throws UnknownGlossaryEntryException wenn es ihn nicht gibt oder er verborgen ist */
    public GlossaryEntry entry(AccessContext caller, String id) {
        return store.find(id).flatMap(new Visibility(caller)::visible)
                .orElseThrow(() -> new UnknownGlossaryEntryException(id));
    }

    /** Protokoll eines sichtbaren Eintrags, älteste zuerst. */
    public List<GlossaryStore.AuditEntry> history(AccessContext caller, String id) {
        entry(caller, id);
        return store.history(id);
    }

    /**
     * Legt einen Fachbegriff von Hand an ({@code MANUAL}, {@code CONFIRMED}), auch ohne Fundstelle im Bestand. Gibt es
     * den Begriff im Geltungsbereich schon, wird er bestätigt und behält seine Herkunft; eine neue Erläuterung ersetzt
     * die alte. Idempotent.
     *
     * @throws InvalidQueryException  bei leerem oder zu langem Begriff, zu langer Erläuterung oder zu vielen Belegen
     * @throws UnknownSourceException bei unbekannter Quelle im Geltungsbereich
     * @throws AccessDeniedException  ohne {@code sync} auf den Geltungsbereich
     */
    public synchronized GlossaryEntry add(AccessContext caller, NewEntry entry) {
        Objects.requireNonNull(entry, "entry");
        requireWrite(caller, entry.scope());
        validate(entry.term(), entry.explanation(), entry.evidence());
        String id = GlossaryEntry.idOf(entry.term(), entry.scope());
        String actor = caller.jobOwner();
        Instant now = clock.instant();
        String explanation = blank(entry.explanation()) ? null : entry.explanation().strip();
        Optional<GlossaryEntry> existing = store.find(id);
        if (existing.isPresent()) {
            GlossaryEntry current = existing.get();
            GlossaryEntry next = current;
            if (explanation != null && !explanation.equals(current.explanation())) {
                next = next.explained(explanation, now);
            }
            if (next.status() != ReviewStatus.CONFIRMED) {
                next = next.reviewed(ReviewStatus.CONFIRMED, actor, now);
            }
            return shown(caller, next == current ? current : save(next, "confirm", actor));
        }
        GlossaryEntry created = new GlossaryEntry(id, entry.term().strip(), TermText.normalize(entry.term()),
                entry.scope(), RelationOrigin.MANUAL, ReviewStatus.CONFIRMED, entry.evidence(), false, explanation,
                null, actor, now, now, actor, now, 1);
        return shown(caller, save(created, "create", actor));
    }

    /**
     * Ändert die Erläuterung; {@code null} oder leer entfernt sie. Begriff und Geltungsbereich bleiben, sie sind die
     * Identität. Fehler wie {@link #confirm}, dazu {@link InvalidQueryException} bei zu langer Erläuterung.
     */
    public synchronized GlossaryEntry explain(AccessContext caller, String id, String explanation,
                                              Long expectedRevision) {
        GlossaryEntry current = writable(caller, id, expectedRevision);
        validate(current.term(), explanation, List.of());
        String text = blank(explanation) ? null : explanation.strip();
        if (Objects.equals(text, current.explanation())) {
            return shown(caller, current);
        }
        return shown(caller, save(current.explained(text, clock.instant()), "edit", caller.jobOwner()));
    }

    /**
     * Bestätigt einen Eintrag.
     *
     * @param expectedRevision Revision, die der Bearbeiter gesehen hat, oder {@code null}
     * @throws UnknownGlossaryEntryException wenn es ihn nicht gibt oder er verborgen ist
     * @throws GlossaryConflictException     wenn er inzwischen eine andere Revision hat
     * @throws AccessDeniedException         ohne {@code sync} auf seinen Geltungsbereich
     */
    public synchronized GlossaryEntry confirm(AccessContext caller, String id, Long expectedRevision) {
        return review(caller, id, expectedRevision, ReviewStatus.CONFIRMED, "confirm");
    }

    /** Lehnt einen Eintrag dauerhaft ab; kein Lauf schlägt ihn in diesem Geltungsbereich wieder vor. */
    public synchronized GlossaryEntry reject(AccessContext caller, String id, Long expectedRevision) {
        return review(caller, id, expectedRevision, ReviewStatus.REJECTED, "reject");
    }

    /** Hebt eine Ablehnung auf: ein KI-Vorschlag wird wieder Vorschlag, ein manueller Eintrag wieder bestätigt. */
    public synchronized GlossaryEntry restore(AccessContext caller, String id, Long expectedRevision) {
        GlossaryEntry current = writable(caller, id, expectedRevision);
        if (current.status() != ReviewStatus.REJECTED) {
            return shown(caller, current);
        }
        ReviewStatus restored = current.origin() == RelationOrigin.MANUAL ? ReviewStatus.CONFIRMED
                : ReviewStatus.PROPOSED;
        return shown(caller, save(current.reviewed(restored, caller.jobOwner(), clock.instant()), "restore",
                caller.jobOwner()));
    }

    /**
     * Entfernt einen Eintrag auf ausdrücklichen Wunsch: einen manuellen ganz, einen KI-Vorschlag als Ablehnung, damit
     * er nicht wiederkommt.
     *
     * @return der verbleibende, abgelehnte Eintrag oder leer, wenn er ganz entfernt ist
     */
    public synchronized Optional<GlossaryEntry> remove(AccessContext caller, String id, Long expectedRevision) {
        GlossaryEntry current = writable(caller, id, expectedRevision);
        if (current.origin() != RelationOrigin.MANUAL) {
            return Optional.of(review(caller, id, null, ReviewStatus.REJECTED, "reject"));
        }
        store.write(List.of(), List.of(id), List.of(new GlossaryStore.AuditEntry(id, "remove", caller.jobOwner(),
                clock.instant(), null, current.revision() + 1)));
        return Optional.empty();
    }

    /**
     * Gleicht die Vorschläge eines vollständigen KI-Laufs für genau einen Geltungsbereich in einem Schritt ab: Neue
     * Begriffe werden {@code PROPOSED}, ungeprüfte KI-Vorschläge bekommen neue Belege, ungeprüfte, die der Lauf nicht
     * mehr nennt, entfallen. Abgelehnte und manuelle Einträge bleiben unberührt, bestätigte bekommen keine neuen
     * Belege und werden ohne Nennung nur als {@code evidenceStale} markiert. Ein abgebrochener oder gescheiterter Lauf
     * ruft dies nicht auf, dann ändert sich nichts.
     *
     * @param actor      Kennung des Laufs für Protokoll und {@code createdBy}
     * @param generation Modell, Promptfassung und Zeitpunkt des Laufs
     * @param retained   Suchwerte, die der Lauf nicht beurteilt hat (Budget, kein lesbarer Text): ihre Einträge
     *                   bleiben, wie sie sind
     */
    public synchronized ProposalRun propose(String actor, RelationScope scope, String generation,
                                            List<Proposal> proposals, Set<String> retained) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(generation, "generation");
        Map<String, GlossaryEntry> put = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        List<GlossaryStore.AuditEntry> audit = new ArrayList<>();
        Set<String> named = new HashSet<>();
        Instant now = clock.instant();
        int added = 0;
        int updated = 0;
        int suppressed = 0;
        int withdrawn = 0;
        int stale = 0;
        for (Proposal proposal : proposals) {
            if (proposal.term() == null || !TermText.usable(proposal.term().strip())
                    || proposal.evidence().isEmpty() || proposal.evidence().size() > MAX_EVIDENCE) {
                suppressed++;
                continue;
            }
            String id = GlossaryEntry.idOf(proposal.term(), scope);
            if (!named.add(id)) {
                continue;
            }
            GlossaryEntry current = store.find(id).orElse(null);
            if (current == null) {
                put.put(id, new GlossaryEntry(id, proposal.term().strip(), TermText.normalize(proposal.term()), scope,
                        RelationOrigin.AI, ReviewStatus.PROPOSED, proposal.evidence(), false, null, generation, actor,
                        now, now, null, null, 1));
                audit.add(new GlossaryStore.AuditEntry(id, "propose", actor, now, ReviewStatus.PROPOSED, 1));
                added++;
            } else if (current.origin() == RelationOrigin.MANUAL || current.status() == ReviewStatus.REJECTED) {
                // Benutzerdaten haben Vorrang; eine Ablehnung gilt für jedes Modell.
                suppressed++;
            } else if (current.status() == ReviewStatus.CONFIRMED) {
                if (current.evidenceStale()) {
                    // Wieder belegt: die Markierung fällt, Belege und Entscheidung bleiben.
                    GlossaryEntry fresh = current.proposedAgain(current.evidence(), current.generation(), now);
                    put.put(id, fresh);
                    audit.add(new GlossaryStore.AuditEntry(id, "propose", actor, now, fresh.status(),
                            fresh.revision()));
                    updated++;
                }
            } else if (!current.evidence().equals(proposal.evidence())) {
                GlossaryEntry fresh = current.proposedAgain(proposal.evidence(), generation, now);
                put.put(id, fresh);
                audit.add(new GlossaryStore.AuditEntry(id, "propose", actor, now, fresh.status(), fresh.revision()));
                updated++;
            }
        }
        Set<String> kept = retained == null ? Set.of() : retained;
        for (GlossaryEntry entry : store.all()) {
            if (entry.origin() != RelationOrigin.AI || !entry.scope().equals(scope) || named.contains(entry.id())
                    || kept.contains(entry.normalized())) {
                continue;
            }
            if (entry.status() == ReviewStatus.PROPOSED) {
                removed.add(entry.id());
                audit.add(new GlossaryStore.AuditEntry(entry.id(), "withdraw", actor, now, null,
                        entry.revision() + 1));
                withdrawn++;
            } else if (entry.status() == ReviewStatus.CONFIRMED && !entry.evidenceStale()) {
                GlossaryEntry marked = entry.stale(now);
                put.put(entry.id(), marked);
                audit.add(new GlossaryStore.AuditEntry(entry.id(), "stale", actor, now, marked.status(),
                        marked.revision()));
                stale++;
            }
        }
        if (!put.isEmpty() || !removed.isEmpty()) {
            store.write(put.values(), removed, audit);
        }
        return new ProposalRun(added, updated, withdrawn, suppressed, stale);
    }

    /**
     * „Daten bereinigen“ einer Quelle, ohne Rechteprüfung: KI-Einträge mit Geltungsbereich in dieser Quelle entfallen,
     * manuelle bleiben (sie stammen vom Bearbeiter), alle übrigen verlieren ihre Belege aus dieser Quelle.
     *
     * @return Anzahl der entfernten Einträge
     */
    public synchronized int removeSource(String sourceId, String actor) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(actor, "actor");
        List<GlossaryEntry> put = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<GlossaryStore.AuditEntry> audit = new ArrayList<>();
        Instant now = clock.instant();
        for (GlossaryEntry entry : store.all()) {
            boolean scoped = sourceId.equals(entry.scope().sourceId());
            boolean evidenced = entry.evidence().stream().anyMatch(item -> item.sourceId().equals(sourceId));
            if (!scoped && !evidenced) {
                continue;
            }
            if (scoped && entry.origin() == RelationOrigin.AI) {
                removed.add(entry.id());
                audit.add(new GlossaryStore.AuditEntry(entry.id(), "purge", actor, now, null, entry.revision() + 1));
            } else {
                GlossaryEntry stripped = entry.withoutEvidenceOf(sourceId, now);
                put.add(stripped);
                audit.add(new GlossaryStore.AuditEntry(entry.id(), "purge", actor, now, stripped.status(),
                        stripped.revision()));
            }
        }
        if (!put.isEmpty() || !removed.isEmpty()) {
            store.write(put, removed, audit);
        }
        return removed.size();
    }

    /** Die Einträge eines Geltungsbereichs, ohne Rechteprüfung; nur für Läufe. */
    List<GlossaryEntry> inScope(RelationScope scope) {
        return store.all().stream().filter(entry -> entry.scope().equals(scope)).toList();
    }

    private GlossaryEntry review(AccessContext caller, String id, Long expectedRevision, ReviewStatus next,
                                 String action) {
        GlossaryEntry current = writable(caller, id, expectedRevision);
        if (current.status() == next) {
            return shown(caller, current);
        }
        return shown(caller, save(current.reviewed(next, caller.jobOwner(), clock.instant()), action,
                caller.jobOwner()));
    }

    /** Die Antwort an den Aufrufer: gespeichert wird alles, gezeigt nur Belege, die er lesen darf. */
    private GlossaryEntry shown(AccessContext caller, GlossaryEntry entry) {
        Visibility visibility = new Visibility(caller);
        return visibility.visible(entry).orElseGet(() -> visibility.withoutEvidence(entry));
    }

    private GlossaryEntry writable(AccessContext caller, String id, Long expectedRevision) {
        GlossaryEntry current = entry(caller, id);
        requireWrite(caller, current.scope());
        // Die gespeicherte Revision zählt, nicht die gefilterte Ansicht.
        GlossaryEntry stored = store.find(id).orElseThrow(() -> new UnknownGlossaryEntryException(id));
        if (expectedRevision != null && expectedRevision != stored.revision()) {
            throw new GlossaryConflictException(id, stored.revision());
        }
        return stored;
    }

    private GlossaryEntry save(GlossaryEntry entry, String action, String actor) {
        store.write(List.of(entry), List.of(), List.of(new GlossaryStore.AuditEntry(entry.id(), action, actor,
                clock.instant(), entry.status(), entry.revision())));
        return entry;
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
            throw new InvalidQueryException("Globale Glossareinträge brauchen mindestens eine Quelle");
        }
        sources.forEach(source -> access.require(caller, ResourceScope.source(source.id()), AccessKind.SYNC));
    }

    private static void validate(String term, String explanation, List<RelationEvidence> evidence) {
        if (term == null || !TermText.usable(term.strip())) {
            throw new InvalidQueryException("Begriffe müssen 1 bis 200 Zeichen ohne Steuerzeichen haben");
        }
        if (explanation != null && explanation.strip().length() > MAX_EXPLANATION_CHARS) {
            throw new InvalidQueryException("Erläuterung höchstens " + MAX_EXPLANATION_CHARS + " Zeichen");
        }
        if (evidence.size() > MAX_EVIDENCE) {
            throw new InvalidQueryException("Höchstens " + MAX_EVIDENCE + " Belege");
        }
    }

    /** Präfix des ganzen Suchwerts oder eines seiner Wörter, wie die Begriffssuche. */
    private static boolean matches(String normalized, String q) {
        if (normalized.startsWith(q)) {
            return true;
        }
        for (String word : normalized.split("[\\s\\-/]+")) {
            if (word.startsWith(q)) {
                return true;
            }
        }
        return false;
    }

    private static String sortKey(GlossaryEntry entry) {
        return entry.normalized() + '\u0000' + entry.id();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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

    /** Sichtbarkeit von Einträgen und Belegen für einen Aufrufer, je Seite und Geltungsbereich einmal geprüft. */
    private final class Visibility {

        private final AccessContext caller;
        private final Map<RelationScope, Boolean> scopes = new HashMap<>();
        private final Map<PageKey, Boolean> pages = new HashMap<>();

        Visibility(AccessContext caller) {
            this.caller = caller;
        }

        Optional<GlossaryEntry> visible(GlossaryEntry entry) {
            if (!scopes.computeIfAbsent(entry.scope(), this::scope)) {
                return Optional.empty();
            }
            List<RelationEvidence> evidence = entry.evidence().stream().filter(this::readable).toList();
            if (entry.origin() == RelationOrigin.AI && !entry.evidence().isEmpty() && evidence.isEmpty()) {
                return Optional.empty();
            }
            if (evidence.size() == entry.evidence().size()) {
                return Optional.of(entry);
            }
            return Optional.of(new GlossaryEntry(entry.id(), entry.term(), entry.normalized(), entry.scope(),
                    entry.origin(), entry.status(), evidence, entry.evidenceStale(), entry.explanation(),
                    entry.generation(), entry.createdBy(), entry.createdAt(), entry.updatedAt(), entry.reviewedBy(),
                    entry.reviewedAt(), entry.revision()));
        }

        GlossaryEntry withoutEvidence(GlossaryEntry entry) {
            return new GlossaryEntry(entry.id(), entry.term(), entry.normalized(), entry.scope(), entry.origin(),
                    entry.status(), List.of(), entry.evidenceStale(), entry.explanation(), entry.generation(),
                    entry.createdBy(), entry.createdAt(), entry.updatedAt(), entry.reviewedBy(), entry.reviewedAt(),
                    entry.revision());
        }

        private boolean scope(RelationScope scope) {
            if (scope.global()) {
                return caller != null;
            }
            if (catalog.find(scope.sourceId()).isEmpty()) {
                return false;
            }
            return access.permits(caller, scope.spaceKey() == null ? ResourceScope.source(scope.sourceId())
                    : ResourceScope.space(scope.sourceId(), scope.spaceKey()), AccessKind.METADATA);
        }

        private boolean readable(RelationEvidence evidence) {
            PageKey key = new PageKey(evidence.sourceId(), evidence.pageId());
            return pages.computeIfAbsent(key, page -> catalog.find(page.sourceId()).isPresent() && headers.apply(page)
                    .map(header -> access.canRead(caller, page.sourceId(), header.metadata().partitionKey(),
                            page.pageId()))
                    .orElse(false));
        }
    }
}
