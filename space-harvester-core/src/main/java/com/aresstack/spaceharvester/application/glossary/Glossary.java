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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

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
    /** Beobachter jeder Änderung ({@link #subscribe}). */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

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
     * @param categories nur Einträge mit einer dieser Einordnungen (ADR 0032, FL5), oder {@code null} für alle; ein
     *                   Eintrag ohne Einordnung zählt als {@code UNKLAR}. Der Reiter fragt ohne {@code TECHNISCH},
     *                   der Filter „Technik“ nur {@code TECHNISCH}.
     */
    public record Search(String q, ReviewStatus status, RelationOrigin origin, String sourceId, String spaceKey,
                         String startsWith, int limit, String cursor, Set<GlossaryDerivation.Category> categories) {

        public Search {
            categories = categories == null ? null : Set.copyOf(categories);
        }

        /** Suche über alle Einordnungen (wie vor ADR 0032, FL5). */
        public Search(String q, ReviewStatus status, RelationOrigin origin, String sourceId, String spaceKey,
                      String startsWith, int limit, String cursor) {
            this(q, status, origin, sourceId, spaceKey, startsWith, limit, cursor, null);
        }
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

    /**
     * Ein Vorschlag eines Laufs: Begriff mit den Belegen, die das Modell oder die Regel sah.
     *
     * @param classification Einordnung des Laufs oder {@code null}; Urheber {@code RULE} oder {@code AI}
     * @param definition     Beleg einer Begriffsbestimmung oder {@code null}
     */
    public record Proposal(String term, List<RelationEvidence> evidence, GlossaryEntry.Classification classification,
                           RelationEvidence definition) {

        public Proposal {
            evidence = List.copyOf(evidence);
            if (classification != null && classification.by() == GlossaryEntry.ClassifiedBy.MANUAL) {
                throw new IllegalArgumentException("Ein Lauf ordnet nie als Mensch ein");
            }
        }

        /** Ein Vorschlag ohne Einordnung (wie vor ADR 0032, FL5). */
        public Proposal(String term, List<RelationEvidence> evidence) {
            this(term, evidence, null, null);
        }
    }

    /** Ergebnis eines Abgleichs. */
    public record ProposalRun(int added, int updated, int withdrawn, int suppressed, int stale) {

        /** Nichts geändert. */
        public static final ProposalRun NONE = new ProposalRun(0, 0, 0, 0, 0);
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
        Visibility visibility = new Visibility(caller, search.sourceId());
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
                    || search.categories() != null && !search.categories().contains(categoryOf(entry))
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

    /**
     * Die bestätigten Fachbegriffe (normalisiert), deren Geltungsbereich {@code scope} berührt (K3): Status
     * {@link ReviewStatus#CONFIRMED}, ohne Einordnung {@code TECHNISCH}. Für Läufe ohne Aufrufer wie die
     * Synonymerkennung; liefert nur Schlüssel, die der Lauf im Begriffsindex ohnehin sieht.
     */
    public Set<String> confirmedTerms(RelationScope scope) {
        Objects.requireNonNull(scope, "scope");
        Set<String> found = new HashSet<>();
        for (GlossaryEntry entry : store.all()) {
            if (confirmedIn(entry, scope)) {
                found.add(entry.normalized());
            }
        }
        return found;
    }

    /**
     * Wie {@link #confirmedTerms(RelationScope)}, nur die Fachbegriffe, die der Aufrufer sehen darf (K5a: Suchmethode
     * „Synonyme der Fachbegriffe“): Quellen im Bereich und mit Recht, Belege wie in {@link #entries}. Sichtbar, im
     * Bereich und bestätigt muss DERSELBE Eintrag sein.
     */
    public Set<String> confirmedTerms(AccessContext caller, RelationScope scope) {
        Objects.requireNonNull(scope, "scope");
        Visibility visibility = new Visibility(caller, scope.sourceId());
        Set<String> found = new HashSet<>();
        for (GlossaryEntry entry : store.all()) {
            if (confirmedIn(entry, scope) && visibility.visible(entry).isPresent()) {
                found.add(entry.normalized());
            }
        }
        return found;
    }

    /** Bestätigt, nicht {@code TECHNISCH} und im Bereich {@code scope}. */
    private boolean confirmedIn(GlossaryEntry entry, RelationScope scope) {
        if (entry.status() != ReviewStatus.CONFIRMED || categoryOf(entry) == GlossaryDerivation.Category.TECHNISCH) {
            return false;
        }
        RelationScope own = entry.scope();
        return own.global() || scope.global()
                || own.sourceId().equals(scope.sourceId()) && (own.spaceKey() == null || scope.spaceKey() == null
                || own.spaceKey().equals(scope.spaceKey()));
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

    /** Ergebnis einer Übernahme ({@link #adopt}). */
    public record Adoption(int added, int updated, int kept) {
    }

    /**
     * Übernimmt ein geprüftes Glossar in das Fachglossar (Angelo 23.11.2026), ohne Aufrufer, beim Start: je Begriff
     * ein Eintrag im globalen Geltungsbereich, {@code MANUAL} und {@code CONFIRMED}, Einordnung {@code FACHLICH} durch
     * den Menschen, Erläuterung im Wortlaut der Quelle, Urheber {@link ApprovedGlossary#actor()}. Idempotent.
     *
     * <p>Entscheidungen der Bearbeiter gehen vor: Ein abgelehnter Eintrag bleibt abgelehnt, ein entfernter kommt nicht
     * wieder (sein Protokoll nennt das Entfernen), eine eigene Erläuterung bleibt. Ein vorhandener Vorschlag desselben
     * Begriffs wird bestätigt und behält seine Herkunft.
     */
    public synchronized Adoption adopt(ApprovedGlossary approved) {
        Objects.requireNonNull(approved, "approved");
        String actor = approved.actor();
        Instant now = clock.instant();
        List<GlossaryEntry> put = new ArrayList<>();
        List<GlossaryStore.AuditEntry> audit = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int kept = 0;
        List<String> missing = approved.entries().stream()
                .map(item -> GlossaryEntry.idOf(item.term(), RelationScope.GLOBAL))
                .filter(id -> store.find(id).isEmpty()).toList();
        Set<String> removed = store.logged(missing, "remove");
        for (ApprovedGlossary.Entry item : approved.entries()) {
            String id = GlossaryEntry.idOf(item.term(), RelationScope.GLOBAL);
            Optional<GlossaryEntry> existing = store.find(id);
            if (existing.isEmpty()) {
                if (removed.contains(id)) {
                    kept++; // vom Bearbeiter entfernt
                    continue;
                }
                put.add(new GlossaryEntry(id, item.term(), TermText.normalize(item.term()), RelationScope.GLOBAL,
                        RelationOrigin.MANUAL, ReviewStatus.CONFIRMED, List.of(), false, item.explanation(), null,
                        actor, now, now, actor, now, 1, APPROVED, null, Set.of()));
                audit.add(new GlossaryStore.AuditEntry(id, "create", actor, now, ReviewStatus.CONFIRMED, 1));
                added++;
                continue;
            }
            GlossaryEntry current = existing.get();
            if (current.status() == ReviewStatus.REJECTED) {
                kept++;
                continue;
            }
            GlossaryEntry next = current;
            if (next.explanation() == null && item.explanation() != null) {
                next = next.explained(item.explanation(), now);
            }
            if (next.status() != ReviewStatus.CONFIRMED) {
                next = next.reviewed(ReviewStatus.CONFIRMED, actor, now);
            }
            if (next.classification() == null || next.classification().by() != GlossaryEntry.ClassifiedBy.MANUAL) {
                next = next.classified(APPROVED, next.definitionProposal(), now);
            }
            if (next == current) {
                kept++;
                continue;
            }
            put.add(next);
            audit.add(new GlossaryStore.AuditEntry(id, "confirm", actor, now, next.status(), next.revision()));
            updated++;
        }
        if (!put.isEmpty()) {
            write(put, List.of(), audit);
        }
        return new Adoption(added, updated, kept);
    }

    /** Einordnung übernommener Einträge: fachlich, vom Menschen geprüft. */
    private static final GlossaryEntry.Classification APPROVED = new GlossaryEntry.Classification(
            GlossaryDerivation.Category.FACHLICH, GlossaryEntry.ClassifiedBy.MANUAL, "APPROVED_GLOSSARY");

    /** Ob es den Begriff im globalen Geltungsbereich von Hand angelegt und bestätigt gibt. */
    private boolean confirmedGlobally(String term) {
        return store.find(GlossaryEntry.idOf(term, RelationScope.GLOBAL))
                .filter(entry -> entry.origin() == RelationOrigin.MANUAL && entry.status() == ReviewStatus.CONFIRMED)
                .isPresent();
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
        write(List.of(), List.of(id), List.of(new GlossaryStore.AuditEntry(id, "remove", caller.jobOwner(),
                clock.instant(), null, current.revision() + 1)));
        return Optional.empty();
    }

    /**
     * Gleicht die Vorschläge eines vollständigen KI-Laufs für genau einen Geltungsbereich in einem Schritt ab: Neue
     * Begriffe werden {@code PROPOSED}, ungeprüfte KI-Vorschläge bekommen neue Belege, ungeprüfte, die der Lauf nicht
     * mehr nennt, entfallen. Abgelehnte und manuelle Einträge bleiben unberührt, bestätigte bekommen keine neuen
     * Belege und werden ohne Nennung nur als {@code evidenceStale} markiert. Ein abgebrochener oder gescheiterter Lauf
     * ruft dies nicht auf: dann entfällt nichts, nur seine Teilergebnisse ({@link #proposeSome}) bleiben.
     *
     * @param actor      Kennung des Laufs für Protokoll und {@code createdBy}
     * @param generation Modell, Promptfassung und Zeitpunkt des Laufs
     * @param retained   Suchwerte, die der Lauf nicht beurteilt hat (Budget, kein lesbarer Text): ihre Einträge
     *                   bleiben, wie sie sind
     */
    public synchronized ProposalRun propose(String actor, RelationScope scope, String generation,
                                            List<Proposal> proposals, Set<String> retained) {
        return merge(RelationOrigin.AI, actor, scope, generation, proposals, retained, true);
    }

    /**
     * Wie {@link #propose(String, RelationScope, String, List, Set)} für einen Lauf der Herkunft {@code origin}
     * ({@link RelationOrigin#AI} oder {@link RelationOrigin#RULE}, ADR 0032, FL5): neue Einträge bekommen diese
     * Herkunft, der Abgleich am Ende betrifft nur ungeprüfte Vorschläge derselben Herkunft.
     */
    public synchronized ProposalRun propose(RelationOrigin origin, String actor, RelationScope scope,
                                            String generation, List<Proposal> proposals, Set<String> retained) {
        return merge(origin, actor, scope, generation, proposals, retained, true);
    }

    /**
     * Teilergebnis eines laufenden KI-Laufs: legt neue Vorschläge sofort an und gibt ungeprüften neue Belege, nach
     * denselben Schutzregeln wie {@link #propose}; entfernt und markiert aber nichts, das bleibt dem Abgleich am Ende
     * des Laufs. Bricht der Lauf ab, bleibt, was schon vorgeschlagen ist.
     */
    public synchronized ProposalRun proposeSome(String actor, RelationScope scope, String generation,
                                                List<Proposal> proposals) {
        return merge(RelationOrigin.AI, actor, scope, generation, proposals, Set.of(), false);
    }

    /** Teilergebnis eines Laufs der Herkunft {@code origin} (siehe {@link #propose(RelationOrigin, String, RelationScope, String, List, Set)}). */
    public synchronized ProposalRun proposeSome(RelationOrigin origin, String actor, RelationScope scope,
                                                String generation, List<Proposal> proposals) {
        return merge(origin, actor, scope, generation, proposals, Set.of(), false);
    }

    /**
     * Ergebnis eines Laufs über eine Seite samt Unterseiten (D4.2): legt neue Vorschläge an und ERGÄNZT bestehende
     * nur. Belege von Seiten außerhalb bleiben (die des Teilbaums ersetzen nur die derselben Seite), die Unterstützung
     * kommt dazu, eine vorhandene Einordnung und Erläuterung bleiben. Entfernt und markiert nie etwas; Schutzregeln wie
     * {@link #propose}. Belege einer Teilbaumseite, die den Begriff nicht mehr enthält, bleiben deshalb bis zum nächsten
     * Lauf über den ganzen Space.
     */
    public synchronized ProposalRun supplement(RelationOrigin origin, String actor, RelationScope scope,
                                               String generation, List<Proposal> proposals) {
        return merge(origin, actor, scope, generation, proposals, Set.of(), false, true);
    }

    /**
     * Der Mensch setzt die Einordnung (ADR 0032, FL5); danach ändert kein Lauf sie mehr. Fehler wie {@link #confirm}.
     */
    public synchronized GlossaryEntry categorize(AccessContext caller, String id, GlossaryDerivation.Category category,
                                                 Long expectedRevision) {
        Objects.requireNonNull(category, "category");
        GlossaryEntry current = writable(caller, id, expectedRevision);
        GlossaryEntry.Classification next = new GlossaryEntry.Classification(category,
                GlossaryEntry.ClassifiedBy.MANUAL, null);
        if (next.equals(current.classification())) {
            return shown(caller, current);
        }
        return shown(caller, save(current.classified(next, current.definitionProposal(), clock.instant()),
                "categorize", caller.jobOwner()));
    }

    /**
     * Meldet jede Änderung des Glossars (anlegen, prüfen, Teilergebnisse und Abgleich eines Laufs, Bereinigen), damit
     * eine Ansicht neu lädt, sobald es Neues gibt. Der Beobachter läuft im schreibenden Thread und muss schnell sein.
     *
     * @return meldet den Beobachter wieder ab
     */
    public Runnable subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void write(java.util.Collection<GlossaryEntry> put, List<String> removed,
                       List<GlossaryStore.AuditEntry> audit) {
        store.write(put, removed, audit);
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // Ein fehlerhafter Beobachter hält das Glossar nicht auf.
            }
        }
    }

    private ProposalRun merge(RelationOrigin origin, String actor, RelationScope scope, String generation,
                              List<Proposal> proposals, Set<String> retained, boolean reconcile) {
        return merge(origin, actor, scope, generation, proposals, retained, reconcile, false);
    }

    private ProposalRun merge(RelationOrigin origin, String actor, RelationScope scope, String generation,
                              List<Proposal> proposals, Set<String> retained, boolean reconcile,
                              boolean supplement) {
        if (origin == RelationOrigin.MANUAL) {
            throw new IllegalArgumentException("Ein Lauf ist nie manuell");
        }
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
            if (current == null && !scope.global() && confirmedGlobally(proposal.term())) {
                // Ein global von Hand bestätigter Begriff (etwa aus einem geprüften Glossar) hat Vorrang: kein
                // zweiter, ungeprüfter Eintrag desselben Begriffs in einer Quelle oder einem Space.
                suppressed++;
                continue;
            }
            if (current == null) {
                put.put(id, new GlossaryEntry(id, proposal.term().strip(), TermText.normalize(proposal.term()), scope,
                        origin, ReviewStatus.PROPOSED, proposal.evidence(), false, null, generation, actor,
                        now, now, null, null, 1, proposal.classification(), proposal.definition(),
                        Set.of(origin)));
                audit.add(new GlossaryStore.AuditEntry(id, "propose", actor, now, ReviewStatus.PROPOSED, 1));
                added++;
                continue;
            }
            if (current.origin() == RelationOrigin.MANUAL || current.status() == ReviewStatus.REJECTED) {
                // Benutzerdaten haben Vorrang; eine Ablehnung gilt für jedes Modell und jede Regel.
                suppressed++;
                continue;
            }
            List<RelationEvidence> evidence = current.evidence();
            String run = current.generation();
            List<RelationEvidence> offered = supplement ? supplemented(evidence, proposal.evidence())
                    : proposal.evidence();
            if (current.status() == ReviewStatus.PROPOSED && !evidence.equals(offered)) {
                // Bestätigte Einträge behalten ihre Belege; ungeprüfte zeigen die des letzten Laufs.
                evidence = offered;
                run = generation;
            }
            Set<RelationOrigin> support = EnumSet.of(origin);
            support.addAll(current.supportedBy());
            GlossaryEntry.Classification classification = supplement && current.classification() != null
                    ? current.classification()
                    : nextClassification(current.classification(), proposal.classification());
            RelationEvidence definition = current.status() == ReviewStatus.PROPOSED && proposal.definition() != null
                    && !(supplement && current.definitionProposal() != null)
                    ? proposal.definition() : current.definitionProposal();
            if (current.evidenceStale() || !evidence.equals(current.evidence())
                    || !support.equals(current.supportedBy())
                    || !Objects.equals(classification, current.classification())
                    || !Objects.equals(definition, current.definitionProposal())) {
                // Wieder belegt: eine Markierung „Beleg veraltet“ fällt, die Entscheidung bleibt.
                GlossaryEntry fresh = current.merged(evidence, run, support, classification, definition, now);
                put.put(id, fresh);
                audit.add(new GlossaryStore.AuditEntry(id, "propose", actor, now, fresh.status(), fresh.revision()));
                updated++;
            }
        }
        Set<String> kept = retained == null ? Set.of() : retained;
        for (GlossaryEntry entry : reconcile ? store.all() : List.<GlossaryEntry>of()) {
            if (entry.origin() == RelationOrigin.MANUAL || entry.status() == ReviewStatus.REJECTED
                    || !entry.supportedBy().contains(origin)
                    || !entry.scope().equals(scope) || named.contains(entry.id())
                    || kept.contains(entry.normalized())) {
                continue;
            }
            Set<RelationOrigin> rest = entry.supportWithout(origin);
            if (!rest.isEmpty()) {
                // Ein anderer Lauf stützt den Begriff weiter: nur dieser Lauf trägt sich aus.
                GlossaryEntry less = entry.supported(rest, now);
                put.put(entry.id(), less);
                audit.add(new GlossaryStore.AuditEntry(entry.id(), "unsupport", actor, now, less.status(),
                        less.revision()));
            } else if (entry.status() == ReviewStatus.PROPOSED) {
                removed.add(entry.id());
                audit.add(new GlossaryStore.AuditEntry(entry.id(), "withdraw", actor, now, null,
                        entry.revision() + 1));
                withdrawn++;
            } else {
                GlossaryEntry marked = entry.evidenceStale() ? entry.supported(rest, now)
                        : entry.stale(now).supported(rest, now);
                put.put(entry.id(), marked);
                audit.add(new GlossaryStore.AuditEntry(entry.id(), "stale", actor, now, marked.status(),
                        marked.revision()));
                if (!entry.evidenceStale()) {
                    stale++;
                }
            }
        }
        if (!put.isEmpty() || !removed.isEmpty()) {
            write(put.values(), removed, audit);
        }
        return new ProposalRun(added, updated, withdrawn, suppressed, stale);
    }

    /**
     * Belege eines Teilbaumlaufs zu den bisherigen (D4.2): bisherige zuerst, die einer Seite des Laufs durch dessen
     * ersetzt, neue Seiten dahinter, höchstens {@link #MAX_EVIDENCE}.
     */
    private static List<RelationEvidence> supplemented(List<RelationEvidence> current, List<RelationEvidence> found) {
        Map<String, List<RelationEvidence>> byPage = new LinkedHashMap<>();
        for (RelationEvidence evidence : found) {
            byPage.computeIfAbsent(evidence.sourceId() + '\u0000' + evidence.pageId(), key -> new ArrayList<>())
                    .add(evidence);
        }
        List<RelationEvidence> all = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (RelationEvidence evidence : current) {
            String page = evidence.sourceId() + '\u0000' + evidence.pageId();
            List<RelationEvidence> fresh = byPage.get(page);
            if (fresh == null) {
                all.add(evidence);
            } else if (placed.add(page)) {
                all.addAll(fresh);
            }
        }
        byPage.forEach((page, fresh) -> {
            if (placed.add(page)) {
                all.addAll(fresh);
            }
        });
        return all.size() > MAX_EVIDENCE ? List.copyOf(all.subList(0, MAX_EVIDENCE)) : List.copyOf(all);
    }

    /**
     * Einordnung nach einem Lauf, unabhängig von der Reihenfolge der Läufe (ADR 0032, FL5c): der Mensch am Eintrag
     * geht immer vor, dann eine bestimmte Regel (Technik, Rolle), dann die KI, dann {@code UNKLAR} der Regel. Eine
     * bestimmte Regel ersetzt also die KI (eine neue Wortliste wirkt), {@code UNKLAR} der Regel ersetzt sie nie. Ohne
     * Einordnung im Vorschlag bleibt die bisherige.
     */
    static GlossaryEntry.Classification nextClassification(GlossaryEntry.Classification current,
                                                           GlossaryEntry.Classification proposed) {
        if (proposed == null || current != null && current.by() == GlossaryEntry.ClassifiedBy.MANUAL) {
            return current;
        }
        if (current == null || current.by() == proposed.by()) {
            return proposed;
        }
        return rank(proposed) >= rank(current) ? proposed : current;
    }

    /** Rang einer Einordnung ohne Mensch: bestimmte Regel 3, KI 2, {@code UNKLAR} der Regel 1. */
    private static int rank(GlossaryEntry.Classification classification) {
        return switch (classification.by()) {
            case MANUAL -> 4;
            case RULE -> classification.category() == GlossaryDerivation.Category.UNKLAR ? 1 : 3;
            case AI -> 2;
        };
    }

    /** Einordnung eines Eintrags für Filter; ohne Einordnung {@code UNKLAR}. */
    static GlossaryDerivation.Category categoryOf(GlossaryEntry entry) {
        return entry.classification() == null ? GlossaryDerivation.Category.UNKLAR
                : entry.classification().category();
    }

    /**
     * „Daten bereinigen“ einer Quelle, ohne Rechteprüfung: KI- und Regel-Einträge mit Geltungsbereich in dieser Quelle
     * entfallen, manuelle bleiben (sie stammen vom Bearbeiter), alle übrigen verlieren ihre Belege aus dieser Quelle.
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
            if (scoped && entry.origin() != RelationOrigin.MANUAL) {
                // Vorschläge eines Laufs (KI und Regel) gehören zur Quelle und fallen mit ihr.
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
            write(put, removed, audit);
        }
        return removed.size();
    }

    /**
     * Einordnung durch das Modell (ADR 0032, FL5c), ohne Rechteprüfung, nur für Läufe: setzt sie an ungeprüften
     * Vorschlägen des Geltungsbereichs, soweit der Vorrang es erlaubt (Mensch vor KI vor Regel; die KI ersetzt eine
     * Regel nur bei {@code UNKLAR}). Belege, Stützung und Status bleiben.
     *
     * @param classified je Suchwert die Einordnung mit {@link GlossaryEntry.ClassifiedBy#AI} und den Seiten der Belege
     * @return Anzahl geänderter Einträge
     */
    public synchronized int classify(String actor, RelationScope scope,
                                     Map<String, GlossaryEntry.Classification> classified) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(scope, "scope");
        List<GlossaryEntry> put = new ArrayList<>();
        List<GlossaryStore.AuditEntry> audit = new ArrayList<>();
        Instant now = clock.instant();
        for (GlossaryEntry entry : inScope(scope)) {
            GlossaryEntry.Classification proposed = classified.get(entry.normalized());
            if (proposed == null || entry.status() != ReviewStatus.PROPOSED
                    || entry.origin() == RelationOrigin.MANUAL) {
                continue;
            }
            if (proposed.by() != GlossaryEntry.ClassifiedBy.AI) {
                throw new IllegalArgumentException("Nur Einordnungen der KI");
            }
            GlossaryEntry.Classification next = nextClassification(entry.classification(), proposed);
            if (Objects.equals(next, entry.classification())) {
                continue;
            }
            GlossaryEntry fresh = entry.classified(next, entry.definitionProposal(), now);
            put.add(fresh);
            audit.add(new GlossaryStore.AuditEntry(fresh.id(), "classify", actor, now, fresh.status(),
                    fresh.revision()));
        }
        if (!put.isEmpty()) {
            write(put, List.of(), audit);
        }
        return put.size();
    }

    /**
     * Entzug der KI-Freigabe (ADR 0028, je Beleg die Seite): Eine Einordnung der KI, von deren Belegseiten eine nicht
     * mehr freigegeben ist, fällt auf den Regelstand zurück ({@code UNKLAR} der Regel, wenn die Regel den Eintrag
     * stützt, sonst keine); Mensch und Regel bleiben. Der nächste Lauf fragt mit den übrigen Belegen neu.
     *
     * @param pages    Seiten, deren Freigabe sich geändert hat
     * @param released ob eine Seite jetzt freigegeben ist
     * @return Anzahl zurückgesetzter Einordnungen
     */
    public synchronized int withdrawAi(java.util.Collection<PageKey> pages, Predicate<PageKey> released) {
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(released, "released");
        Set<PageKey> changed = new HashSet<>(pages);
        List<GlossaryEntry> put = new ArrayList<>();
        List<GlossaryStore.AuditEntry> audit = new ArrayList<>();
        Instant now = clock.instant();
        for (GlossaryEntry entry : store.all()) {
            GlossaryEntry.Classification current = entry.classification();
            if (current == null || current.by() != GlossaryEntry.ClassifiedBy.AI || current.pages().stream()
                    .noneMatch(page -> changed.contains(page) && !released.test(page))) {
                continue;
            }
            GlossaryEntry.Classification rule = entry.supportedBy().contains(RelationOrigin.RULE)
                    ? new GlossaryEntry.Classification(GlossaryDerivation.Category.UNKLAR,
                    GlossaryEntry.ClassifiedBy.RULE, null) : null;
            GlossaryEntry fresh = entry.classified(rule, entry.definitionProposal(), now);
            put.add(fresh);
            audit.add(new GlossaryStore.AuditEntry(fresh.id(), "unclassify", "ai-release", now, fresh.status(),
                    fresh.revision()));
        }
        if (!put.isEmpty()) {
            write(put, List.of(), audit);
        }
        return put.size();
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
        write(List.of(entry), List.of(), List.of(new GlossaryStore.AuditEntry(entry.id(), action, actor,
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
    static boolean matches(String normalized, String q) {
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

    static String sortKey(GlossaryEntry entry) {
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
        private final java.util.function.Predicate<String> included;
        private final Map<RelationScope, Boolean> scopes = new HashMap<>();
        private final Map<PageKey, Boolean> pages = new HashMap<>();

        Visibility(AccessContext caller) {
            this.caller = caller;
            this.included = id -> true;
        }

        Visibility(AccessContext caller, String explicitSource) {
            this.caller = caller;
            this.included = id -> id.equals(explicitSource) || catalog.includeInGlobalSearch(id);
        }

        Optional<GlossaryEntry> visible(GlossaryEntry entry) {
            if (!scopes.computeIfAbsent(entry.scope(), this::scope)) {
                return Optional.empty();
            }
            List<RelationEvidence> evidence = entry.evidence().stream().filter(this::readable).toList();
            // Einträge eines Laufs (KI und Regel) ohne lesbaren Beleg verraten nichts und bleiben verborgen, auch wenn
            // sie gar keinen Beleg mehr haben.
            if (entry.origin() != RelationOrigin.MANUAL && evidence.isEmpty()) {
                return Optional.empty();
            }
            RelationEvidence definition = entry.definitionProposal() != null && readable(entry.definitionProposal())
                    ? entry.definitionProposal() : null;
            if (evidence.size() == entry.evidence().size()
                    && Objects.equals(definition, entry.definitionProposal())) {
                return Optional.of(entry);
            }
            return Optional.of(entry.showing(evidence, definition));
        }

        GlossaryEntry withoutEvidence(GlossaryEntry entry) {
            return entry.showing(List.of(), null);
        }

        private boolean scope(RelationScope scope) {
            if (scope.global()) {
                return caller != null;
            }
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
}
