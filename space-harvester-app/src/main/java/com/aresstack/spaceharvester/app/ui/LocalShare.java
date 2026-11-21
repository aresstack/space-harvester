package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wie viel des Suchbereichs lokal liegt (Paket K6): Die volle Suche setzt heruntergeladene Seiten voraus, also sagt
 * die Statuszeile der Suche „N von M Seiten des Bereichs sind lokal gespeichert“. Zahlen nur aus dem Bestand des Core
 * ({@code Holdings.resolve}), nie eine Anfrage an die Quelle.
 */
final class LocalShare {

    /** Hinweis am Text in der Statuszeile. */
    static final String TIP = "Volltext, Begriffe und Analysen gibt es nur für lokal gespeicherte Seiten; die übrigen "
            + "findet nur die Suche der Quelle, soweit sie eine hat. Laden über „Herunterladen“ im Baum.";

    private LocalShare() {
    }

    /**
     * Der Bereich der nächsten Suche als Auswahl im Core: je gewählter Knoten unter den durchsuchten Quellen ein Space
     * oder die ganze Quelle; ohne Auswahl im Explorer alle durchsuchten Quellen. Leer, wenn nichts durchsucht wird.
     */
    static Selection selection(Collection<String> searched, List<Explorer.Scope> scopes) {
        List<Selection.Root> roots = new ArrayList<>();
        Set<String> wholeSources = new LinkedHashSet<>();
        boolean any = false;
        for (Explorer.Scope scope : scopes) {
            if (scope.sourceId() == null || !searched.contains(scope.sourceId())) {
                continue;
            }
            any = true;
            if (scope.spaceKey() == null) {
                wholeSources.add(scope.sourceId());
            }
        }
        if (!any) {
            searched.forEach(id -> roots.add(Selection.Root.source(id)));
            return new Selection(roots);
        }
        wholeSources.forEach(id -> roots.add(Selection.Root.source(id)));
        for (Explorer.Scope scope : scopes) {
            if (scope.sourceId() != null && scope.spaceKey() != null && searched.contains(scope.sourceId())
                    && !wholeSources.contains(scope.sourceId())) {
                Selection.Root root = Selection.Root.space(scope.sourceId(), scope.spaceKey());
                if (!roots.contains(root)) {
                    roots.add(root);
                }
            }
        }
        return new Selection(roots);
    }

    /** Der Text für die Statuszeile; leer ohne Seiten im Bereich. */
    static String line(PageSelection pages) {
        String line = line(pages.counts().stored(), pages.counts().pages());
        // Ohne bestätigte Inventur kennt der Bestand nur einzeln geladene Seiten; „M“ ist dann nur eine Untergrenze.
        return line.isEmpty() || pages.complete() ? line : line + " · ohne Inventur, es kann mehr Seiten geben";
    }

    /** Der Text für die Statuszeile aus lokal gespeicherten von allen bekannten Seiten; leer ohne Seiten. */
    static String line(int stored, int pages) {
        if (pages <= 0) {
            return "";
        }
        NumberFormat number = NumberFormat.getIntegerInstance(Locale.GERMANY);
        return number.format(Math.min(stored, pages)) + " von " + number.format(pages)
                + (pages == 1 ? " Seite des Bereichs ist lokal gespeichert"
                : " Seiten des Bereichs sind lokal gespeichert");
    }
}
