package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection.Root;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bestand je Wurzel einer Auswahl links (ADR 0034 §3): Anmeldung beim Bestand und Auflösung zu Seiten an einer Stelle,
 * für Knoten, „Wissensquellen“ und Sammlungen gleich. Eine Sammlung (Favoriten-Ordner) wird nachsichtig aufgelöst
 * ({@code Holdings.resolveLenient}): Ein veralteter oder nicht sichtbarer Favorit fällt heraus, statt die ganze Auswahl
 * zu brechen. Knoten und „Wissensquellen“ bleiben streng wie bisher. Auflösen nie auf dem EDT.
 */
public final class SelectionHoldings {

    private SelectionHoldings() {
    }

    /**
     * Meldet {@code listener} beim Bestand jeder Wurzel an: je Quelle samt Space, eine ganze Quelle einmal für alle
     * ihre Spaces ({@link #areas(AppRuntime, ExportPanel.Selection)}).
     *
     * @return die Anmeldungen, vom Aufrufer zu schließen; leer ohne Laufzeit oder Auswahl
     */
    public static List<Subscription> watch(AppRuntime runtime, ExportPanel.Selection selection,
                                           HoldingsListener listener) {
        return watch(runtime, areas(runtime, selection), listener);
    }

    /**
     * Meldet {@code listener} bei jedem Bereich an; eine Quelle, die es in der Laufzeit nicht mehr gibt, wird still
     * übergangen.
     *
     * @param areas Bereiche aus {@link #areas(AppRuntime, ExportPanel.Selection)}
     * @return die Anmeldungen, vom Aufrufer zu schließen; leer ohne Laufzeit
     */
    public static List<Subscription> watch(AppRuntime runtime, List<Root> areas, HoldingsListener listener) {
        Objects.requireNonNull(listener, "listener");
        List<Subscription> subscriptions = new ArrayList<>();
        if (runtime == null) {
            return subscriptions;
        }
        for (Root area : areas) {
            try {
                subscriptions.add(runtime.workspace().subscribeHoldings(area.sourceId(), area.spaceKey(), listener));
            } catch (RuntimeException gone) {
                // Die Quelle gibt es in dieser Laufzeit nicht mehr; der Baum räumt die Auswahl gleich weg.
            }
        }
        return subscriptions;
    }

    /**
     * Die Bereiche, bei denen sich eine Auswahl anmeldet, als stabile Liste (gleiche Liste = keine neue Anmeldung
     * nötig): je Wurzel Quelle samt Space ({@code spaceKey} {@code null} = ganze Quelle), „Wissensquellen“ jede Quelle,
     * eine Sammlung je Favorit; ohne Doppelte, ein Space unter einer ganzen Quelle fällt weg.
     *
     * @return leer ohne Laufzeit oder Auswahl
     */
    public static List<Root> areas(AppRuntime runtime, ExportPanel.Selection selection) {
        return runtime == null || selection == null ? List.of() : areas(selection.roots(runtime));
    }

    /** Wie {@link #areas(AppRuntime, ExportPanel.Selection)} für schon bekannte Wurzeln. */
    static List<Root> areas(List<Root> roots) {
        Set<String> wholeSources = new LinkedHashSet<>();
        roots.stream().filter(root -> root.spaceKey() == null).forEach(root -> wholeSources.add(root.sourceId()));
        Set<Root> areas = new LinkedHashSet<>();
        for (Root root : roots) {
            if (root.spaceKey() == null) {
                areas.add(Root.source(root.sourceId()));
            } else if (!wholeSources.contains(root.sourceId())) {
                areas.add(Root.space(root.sourceId(), root.spaceKey()));
            }
        }
        return List.copyOf(areas);
    }

    /**
     * Die Auswahl zu Seiten aufgelöst, mit den übersprungenen Wurzeln: eine Sammlung nachsichtig (etwa für „n Favoriten
     * nicht verfügbar“), sonst streng wie {@code resolveSelection} und ohne übersprungene Wurzeln.
     */
    public static Holdings.Lenient lenient(AppRuntime runtime, ExportPanel.Selection selection) {
        com.aresstack.spaceharvester.application.holdings.Selection core = selection.toCore(runtime);
        return selection instanceof ExportPanel.Selection.Collection
                ? runtime.workspace().resolveLenient(core)
                : new Holdings.Lenient(runtime.workspace().resolveSelection(core), List.of());
    }

    /** Die Auswahl zu Seiten aufgelöst ({@link #lenient}, ohne die übersprungenen Wurzeln). */
    public static PageSelection resolve(AppRuntime runtime, ExportPanel.Selection selection) {
        return lenient(runtime, selection).selection();
    }

    /** Die Seiten der Auswahl als Grenze; {@code null} bei „Wissensquellen“ (keine Grenze). */
    public static PageSet pages(AppRuntime runtime, ExportPanel.Selection selection) {
        return selection.global() ? null : resolve(runtime, selection).pages();
    }

    /**
     * Die Auswahl für den Core, ohne die Wurzeln einer Sammlung, die der Aufrufer nicht sehen kann: So lösen Dienste,
     * die streng auflösen (etwa das Fachglossar), denselben Bestand auf wie {@link #resolve}.
     */
    public static com.aresstack.spaceharvester.application.holdings.Selection core(AppRuntime runtime,
                                                                                  ExportPanel.Selection selection) {
        com.aresstack.spaceharvester.application.holdings.Selection core = selection.toCore(runtime);
        if (!(selection instanceof ExportPanel.Selection.Collection)) {
            return core;
        }
        List<Root> skipped = runtime.workspace().resolveLenient(core).skippedRoots();
        return skipped.isEmpty() ? core : new com.aresstack.spaceharvester.application.holdings.Selection(
                core.roots().stream().filter(root -> !skipped.contains(root)).toList());
    }
}
