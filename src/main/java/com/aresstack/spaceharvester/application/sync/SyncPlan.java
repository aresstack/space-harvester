package com.aresstack.spaceharvester.application.sync;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Abgleich einer Inventur mit der zuletzt bestätigten Generation: was je Seite zu tun ist und, nach getaner Arbeit,
 * welche Generation daraus wird.
 *
 * @param inventory die Inventur
 * @param baseline  die bisherige Generation oder {@code null} beim ersten Lauf
 * @param changes   eine Änderung je Seite aus Inventur und Generation, nach {@code pageId} sortiert
 * @param removals  Ergebnis von {@link RemovalCheck} je fehlender Seite; leer, solange nicht geprüft
 */
public record SyncPlan(Inventory inventory, SyncBaseline baseline, List<PageChange> changes,
                       Map<String, RemovalCheck.Result> removals) {

    public SyncPlan {
        Objects.requireNonNull(inventory, "inventory");
        changes = List.copyOf(changes);
        removals = Map.copyOf(removals);
    }

    public SyncPlan withRemovals(Map<String, RemovalCheck.Result> checked) {
        return new SyncPlan(inventory, baseline, changes, checked);
    }

    public List<PageChange> changes(PageChange.Kind first, PageChange.Kind... more) {
        Set<PageChange.Kind> kinds = EnumSet.of(first, more);
        return changes.stream().filter(change -> kinds.contains(change.kind())).toList();
    }

    /** Seiten, deren nativer Inhalt geladen werden muss. */
    public List<PageChange> contentToFetch() {
        return changes.stream().filter(PageChange::requiresContent).toList();
    }

    /**
     * Ob Quelle und bisherige Generation in allen Fingerprints übereinstimmen; nur bei bestätigter Inventur und ohne
     * veraltete Seiten.
     */
    public boolean upToDate() {
        Optional<String> fingerprint = inventory.spaceFingerprint();
        return baseline != null && baseline.stalePageIds().isEmpty() && fingerprint.isPresent()
                && fingerprint.get().equals(baseline.spaceFingerprint());
    }

    /**
     * Warum aus diesem Plan keine Generation werden kann (Inventur abgebrochen oder widersprüchlich, eine fehlende Seite
     * ist doch im Space), oder leer, wenn sie bestätigt werden kann.
     */
    public Optional<String> unconfirmable() {
        if (!inventory.confirmed()) {
            return Optional.of("Inventur " + inventory.status() + ": " + inventory.problem());
        }
        return removals.values().stream()
                .filter(removal -> removal.verdict() == RemovalCheck.Verdict.STILL_PRESENT)
                .map(removal -> "Seite " + removal.pageId() + " fehlt in der Inventur, ist laut Quelle aber im Space")
                .findFirst();
    }

    /**
     * Die nächste Generation, nachdem die Änderungen angewendet wurden.
     *
     * <p>Seiten aus der Inventur übernehmen ihren neuen Stand. Ist ihr Abgleich fehlgeschlagen, behalten sie den
     * bisherigen (und gelten als veraltet) oder fehlen weiter, wenn sie neu waren; so gleicht der nächste Lauf sie
     * erneut ab. Fehlende Seiten fallen nur bei {@link RemovalCheck.Result#removes()} weg, sonst bleiben sie veraltet
     * erhalten.
     *
     * @param confirmedAt   Zeitpunkt der Bestätigung
     * @param failedPageIds Seiten, deren Inhalts-, Metadaten- oder Attributabgleich fehlgeschlagen ist
     * @throws UnconfirmableSyncException wenn die Inventur nicht bestätigt ist oder eine fehlende Seite doch im Space
     *                                    liegt
     * @throws IllegalArgumentException   für eine fehlgeschlagene Seite, die der Plan nicht kennt
     */
    public SyncBaseline nextBaseline(Instant confirmedAt, Set<String> failedPageIds) {
        Optional<String> unconfirmable = unconfirmable();
        if (unconfirmable.isPresent()) {
            throw new UnconfirmableSyncException(unconfirmable.get());
        }
        Set<String> failed = new HashSet<>(failedPageIds);
        SortedMap<String, BaselineEntry> pages = new TreeMap<>();
        SortedSet<String> stale = new TreeSet<>();
        SortedSet<String> missing = new TreeSet<>();
        for (PageChange change : changes) {
            boolean keepPrevious;
            if (change.current() != null) {
                keepPrevious = failed.remove(change.pageId());
                if (!keepPrevious) {
                    pages.put(change.pageId(), BaselineEntry.of(change.current()));
                }
            } else {
                RemovalCheck.Result removal = removals.get(change.pageId());
                keepPrevious = removal == null || !removal.removes();
            }
            if (keepPrevious && change.previous() != null) {
                pages.put(change.pageId(), change.previous());
                stale.add(change.pageId());
            } else if (keepPrevious && change.current() != null) {
                missing.add(change.pageId());
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalArgumentException("Seiten nicht im Plan: " + new TreeSet<>(failed));
        }
        long generation = baseline == null ? 1 : baseline.generation() + 1;
        return new SyncBaseline(inventory.sourceId(), inventory.spaceKey(), generation, confirmedAt, pages, stale,
                missing);
    }
}
