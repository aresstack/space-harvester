package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Vergleicht eine Inventur mit der zuletzt bestätigten Generation. Rein funktional, ohne Quelle und ohne Speicher.
 *
 * <p>Regeln: Eine andere Version verlangt den Inhalt neu. Ist die Version auf einer Seite unbekannt, entscheidet der
 * {@code metadataHash}, und jede Abweichung verlangt den Inhalt (lieber einmal zu viel laden als einen veralteten
 * Inhalt als aktuell führen). Bei gleicher Version genügt bei abweichendem {@code metadataHash} ein Metadaten-, bei
 * abweichendem {@code attributesHash} ein Attributabgleich. Fehlende Seiten werden nur in einer bestätigten Inventur
 * zu {@link PageChange.Kind#MISSING}.
 */
public final class SyncPlanner {

    private SyncPlanner() {
    }

    /**
     * @param baseline zuletzt bestätigte Generation desselben Space oder {@code null} beim ersten Lauf
     * @throws IllegalArgumentException wenn die Generation zu einer anderen Quelle oder einem anderen Space gehört
     */
    public static SyncPlan plan(SyncBaseline baseline, Inventory inventory) {
        return plan(baseline, inventory, Map.of(), SyncMode.FULL);
    }

    /** Wie {@link #plan(SyncBaseline, Inventory, Map, SyncMode)} mit {@link SyncMode#loadingUnknown(boolean)}. */
    public static SyncPlan plan(SyncBaseline baseline, Inventory inventory, Map<String, BaselineEntry> cached,
                                boolean loadUnknown) {
        return plan(baseline, inventory, cached, SyncMode.loadingUnknown(loadUnknown));
    }

    /**
     * Abgleich ohne Erstbefüllung (ADR 0014, Punkt 2): Bekannt ist, was die Generation führt, und darüber, was der Cache
     * kennt ({@code cached}, frischer als die Generation). Außer bei {@link SyncMode#FULL} wird eine gelistete, aber
     * unbekannte Seite {@link PageChange.Kind#NOT_LOADED} statt {@link PageChange.Kind#NEW}; bei
     * {@link SyncMode#CATALOG_ONLY} wird eine neue Version {@link PageChange.Kind#OUTDATED} statt
     * {@link PageChange.Kind#CONTENT_CHANGED}.
     *
     * @param cached Stand des Cache je {@code pageId} ({@link SyncBaselineStore#knownPages})
     */
    public static SyncPlan plan(SyncBaseline baseline, Inventory inventory, Map<String, BaselineEntry> cached,
                                SyncMode mode) {
        if (baseline != null && !(baseline.sourceId().equals(inventory.sourceId())
                && baseline.spaceKey().equals(inventory.spaceKey()))) {
            throw new IllegalArgumentException("Generation und Inventur gehören zu verschiedenen Spaces");
        }
        Map<String, BaselineEntry> previous = new HashMap<>(baseline == null ? Map.of() : baseline.pages());
        previous.putAll(cached);
        Map<String, PageMetadataListing.Entry> current = inventory.byPageId();
        List<PageChange> changes = new ArrayList<>();
        current.forEach((pageId, entry) -> {
            changes.add(within(mode, classify(previous.get(pageId), entry)));
        });
        PageChange.Kind absent = inventory.confirmed() ? PageChange.Kind.MISSING : PageChange.Kind.NOT_SEEN;
        previous.forEach((pageId, entry) -> {
            if (!current.containsKey(pageId)) {
                changes.add(new PageChange(pageId, absent, entry, null));
            }
        });
        if (baseline != null && inventory.confirmed()) {
            // Nur im Katalog geführte Seiten (NOT_LOADED) haben keinen Stand, verschwinden aber genauso.
            for (String pageId : baseline.missingPageIds()) {
                if (!current.containsKey(pageId) && !previous.containsKey(pageId)) {
                    changes.add(new PageChange(pageId, PageChange.Kind.MISSING, null, null));
                }
            }
        }
        changes.sort(Comparator.comparing(PageChange::pageId));
        return new SyncPlan(inventory, baseline, changes, Map.of());
    }

    /** Was von einer Einordnung in dieser Betriebsart übrig bleibt. */
    static PageChange within(SyncMode mode, PageChange change) {
        if (change.kind() == PageChange.Kind.NEW && !mode.loadsUnknown()) {
            return new PageChange(change.pageId(), PageChange.Kind.NOT_LOADED, null, change.current());
        }
        if (change.kind() == PageChange.Kind.CONTENT_CHANGED && !mode.refreshesContent()) {
            return new PageChange(change.pageId(), PageChange.Kind.OUTDATED, change.previous(), change.current());
        }
        return change;
    }

    /**
     * Einordnung einer einzelnen Seite, auch für eine explizite Aktualitätsprüfung ({@code ?refresh=true}) mit frisch
     * gelesenen Einzelmetadaten.
     *
     * @param previous Stand in der bestätigten Generation oder {@code null}
     */
    public static PageChange classify(BaselineEntry previous, PageMetadataListing.Entry current) {
        Objects.requireNonNull(current, "current");
        String pageId = current.page().pageId();
        if (previous == null) {
            return new PageChange(pageId, PageChange.Kind.NEW, null, current);
        }
        if (!previous.pageId().equals(pageId)) {
            throw new IllegalArgumentException("Stand und Inventur betreffen verschiedene Seiten");
        }
        boolean metadataChanged = !previous.metadataHash().equals(current.metadataHash());
        String version = current.page().version();
        boolean versionKnown = previous.version() != null && version != null;
        PageChange.Kind kind;
        if (versionKnown ? !previous.version().equals(version) : metadataChanged) {
            kind = PageChange.Kind.CONTENT_CHANGED;
        } else if (metadataChanged) {
            kind = PageChange.Kind.METADATA_CHANGED;
        } else if (!previous.attributesHash().equals(current.attributesHash())) {
            kind = PageChange.Kind.ATTRIBUTES_CHANGED;
        } else {
            kind = PageChange.Kind.UNCHANGED;
        }
        return new PageChange(pageId, kind, previous, current);
    }
}
