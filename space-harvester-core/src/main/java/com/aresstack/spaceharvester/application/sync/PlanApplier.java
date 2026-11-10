package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.Set;
import java.util.TreeSet;

/**
 * Merkt die Änderungen eines {@link SyncPlan} in einer {@link StagedGeneration} vor.
 *
 * <ul>
 *   <li>{@link PageChange.Kind#NEW}, {@link PageChange.Kind#CONTENT_CHANGED}: Snapshot laden und speichern, wenn er
 *       zur Inventur passt (siehe unten).</li>
 *   <li>{@link PageChange.Kind#METADATA_CHANGED}, {@link PageChange.Kind#ATTRIBUTES_CHANGED}: nur die aktuellen
 *       Kopfdaten ersetzen, kein Inhaltsabruf.</li>
 *   <li>{@link PageChange.Kind#MISSING}: bei {@link RemovalCheck.Verdict#DELETED} entfernen, bei
 *       {@link RemovalCheck.Verdict#INACCESSIBLE} sperren ({@link StagedGeneration#withhold}), bei
 *       {@link RemovalCheck.Verdict#MOVED_OUT} nur die Kopfdaten aus der Einzelabfrage übernehmen (der Schlüssel
 *       enthält keinen Space, die Seite bleibt), sonst nichts.</li>
 *   <li>{@link PageChange.Kind#NOT_LOADED}: nur die Kopfdaten in den Katalog, kein Inhaltsabruf.</li>
 *   <li>{@link SyncPlan#purges()}: gesperrte Seiten nach Ablauf der Frist endgültig entfernen.</li>
 * </ul>
 *
 * <p>Ein geladener Snapshot muss Seite, Space und (wenn die Inventur sie kennt) Revision der Inventur tragen. Weicht
 * er ab, weil sich die Seite zwischen Listung und Abruf geändert hat (ältere, neuere oder verschobene Antwort), wird
 * er nicht gespeichert und die Seite gilt als fehlgeschlagen: Die Generation behält den bisherigen Stand als veraltet,
 * und der nächste Lauf gleicht sie erneut ab. So bestätigt eine Generation nie eine Revision, deren Payload nicht
 * gespeichert ist. Ein Quellfehler beim Laden macht ebenfalls nur die betroffene Seite fehlgeschlagen.
 */
public class PlanApplier {

    private final PageContentFetcher fetcher;

    public PlanApplier(PageContentFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * Vorab, vor {@link SyncBaselineStore#begin} (ADR 0025): lädt alle {@link PageChange.Kind#NEW}-Seiten und legt sie
     * mit {@code keep} sofort ab, mit {@link SyncListener#checkpoint()} vor jedem Abruf. Ein Abbruch behält, was schon
     * abgelegt ist; der nächste Lauf lädt es nicht erneut. Was {@code keep} nicht annimmt, bleibt zum Vormerken in
     * {@link Prefetched#held()}.
     */
    public Prefetched loadNew(SyncPlan plan, SyncBaselineStore store, SyncListener listener) {
        String sourceId = plan.inventory().sourceId();
        Set<String> handled = new HashSet<>();
        Set<String> failed = new TreeSet<>();
        List<PageSnapshot> held = new ArrayList<>();
        for (PageChange change : plan.changes()) {
            if (change.kind() != PageChange.Kind.NEW) {
                continue;
            }
            listener.checkpoint();
            PageKey key = new PageKey(sourceId, change.pageId());
            // Vor dem Abruf: was ein anderer Lauf währenddessen ablegt, darf diese Antwort nicht überschreiben.
            String before = store.loadState(key);
            PageSnapshot snapshot = matching(key, change.current().page());
            handled.add(change.pageId());
            boolean superseded = false;
            if (snapshot == null) {
                failed.add(change.pageId());
            } else {
                switch (store.keepLoaded(snapshot, before)) {
                    case STORED -> { }
                    case DEFERRED -> held.add(snapshot);
                    case SUPERSEDED -> {
                        failed.add(change.pageId());
                        superseded = true;
                    }
                }
            }
            listener.pageDone(change, snapshot != null && !superseded, snapshot == null || superseded);
        }
        return new Prefetched(handled, failed, held);
    }

    /**
     * Ergebnis von {@link #loadNew}.
     *
     * @param handled alle bearbeiteten neuen Seiten
     * @param failed  davon gescheitert
     * @param held    geladen, aber nicht abgelegt; werden in der Generation vorgemerkt
     */
    public record Prefetched(Set<String> handled, Set<String> failed, List<PageSnapshot> held) {

        public static final Prefetched NONE = new Prefetched(Set.of(), Set.of(), List.of());

        public Prefetched {
            handled = Set.copyOf(handled);
            failed = Set.copyOf(failed);
            held = List.copyOf(held);
        }
    }

    /** @return die Seiten, deren Abgleich fehlgeschlagen ist, für {@link SyncPlan#nextBaseline} */
    public Set<String> apply(SyncPlan plan, StagedGeneration target) {
        return apply(plan, target, SyncListener.NONE);
    }

    /**
     * Wie {@link #apply(SyncPlan, StagedGeneration)}, mit Rückmeldung je Seite und {@link SyncListener#checkpoint()}
     * vor jedem Inhaltsabruf.
     */
    public Set<String> apply(SyncPlan plan, StagedGeneration target, SyncListener listener) {
        return apply(plan, target, listener, Prefetched.NONE);
    }

    /** Wie {@link #apply(SyncPlan, StagedGeneration, SyncListener)} nach {@link #loadNew}: die neuen Seiten sind erledigt. */
    public Set<String> apply(SyncPlan plan, StagedGeneration target, SyncListener listener, Prefetched prefetched) {
        String sourceId = plan.inventory().sourceId();
        Instant observedAt = plan.inventory().finishedAt();
        Set<String> failed = new TreeSet<>(prefetched.failed());
        prefetched.held().forEach(target::save);
        for (PageChange change : plan.changes()) {
            if (prefetched.handled().contains(change.pageId())) {
                continue;
            }
            PageKey key = new PageKey(sourceId, change.pageId());
            boolean written = true;
            switch (change.kind()) {
                case NEW, CONTENT_CHANGED -> {
                    listener.checkpoint();
                    if (!fetch(key, change.current().page(), target)) {
                        failed.add(change.pageId());
                        written = false;
                    }
                }
                case METADATA_CHANGED, ATTRIBUTES_CHANGED ->
                        target.saveHeader(header(key, change.current(), observedAt));
                case MISSING -> {
                    RemovalCheck.Result removal = plan.removals().get(change.pageId());
                    if (removal != null && removal.verdict() == RemovalCheck.Verdict.DELETED) {
                        target.remove(key);
                    } else if (removal != null && removal.verdict() == RemovalCheck.Verdict.INACCESSIBLE) {
                        target.withhold(key);
                    } else if (removal != null && removal.verdict() == RemovalCheck.Verdict.MOVED_OUT) {
                        target.saveHeader(header(key, removal.current(), observedAt));
                    } else {
                        written = false;
                    }
                }
                case NOT_LOADED -> {
                    // Metadatenkatalog ohne Inhalt (ADR 0014): Kopfdaten samt Eltern-ID, kein Abruf.
                    target.saveHeader(header(key, change.current(), observedAt));
                    written = false;
                }
                case OUTDATED -> {
                    // Katalog ohne Inhaltsabruf: aktuelle Kopfdaten, die abgelegte Payload bleibt als veraltet stehen.
                    target.saveHeader(header(key, change.current(), observedAt));
                    written = false;
                }
                case UNCHANGED, NOT_SEEN -> written = false;
            }
            listener.pageDone(change, written, failed.contains(change.pageId()));
        }
        for (String pageId : plan.purges()) {
            target.remove(new PageKey(sourceId, pageId));
        }
        return failed;
    }

    private boolean fetch(PageKey key, PageMetadata expected, StagedGeneration target) {
        PageSnapshot snapshot = matching(key, expected);
        if (snapshot != null) {
            target.save(snapshot);
        }
        return snapshot != null;
    }

    /** Der geladene Snapshot, wenn er zur Inventur passt; sonst {@code null}. */
    private PageSnapshot matching(PageKey key, PageMetadata expected) {
        PageSnapshot snapshot;
        try {
            snapshot = fetcher.fetch(key.sourceId(), key.pageId());
        } catch (SourceAccessException e) {
            return null;
        }
        boolean matches = snapshot.key().equals(key)
                && expected.partitionKey().equals(snapshot.spaceKey())
                && (expected.version() == null || expected.version().equals(snapshot.revision()));
        return matches ? snapshot : null;
    }

    private static PageHeader header(PageKey key, PageMetadataListing.Entry entry, Instant observedAt) {
        return new PageHeader(key, entry.page(), entry.metadataHash(), entry.attributesHash(), observedAt);
    }
}
