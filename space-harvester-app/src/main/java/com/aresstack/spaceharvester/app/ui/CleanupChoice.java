package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs.CleanupPreview;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rückfrage und Auswahl für „Ohne Erwerbsgrund bereinigen“ aus den Vorschauen je Bereich (ADR 0037, Punkt 3): Anzahl
 * und Umfang vorab, Ressourcen mit unbekanntem Grund nur auf ausdrückliche Wahl (Angelo 26.11.2026).
 */
record CleanupChoice(List<CleanupPreview> previews) {

    CleanupChoice {
        previews = List.copyOf(previews);
    }

    static CleanupChoice of(List<CleanupPreview> previews) {
        return new CleanupChoice(previews);
    }

    /** Ressourcen, die nachweislich keinen Erwerbsgrund mehr haben. */
    int withoutReason() {
        return previews.stream().mapToInt(preview -> preview.withoutReason().size()).sum();
    }

    /** Ressourcen mit unbekanntem Grund, vor dem Merken der Gründe erworben. */
    int unknown() {
        return previews.stream().mapToInt(preview -> preview.unknown().size()).sum();
    }

    /** Bereiche, deren Inventur nicht vollständig war; dort wird nichts entfernt. */
    int incomplete() {
        return (int) previews.stream().filter(preview -> preview.problem() != null).count();
    }

    /** Die Rückfrage mit Anzahl und Umfang; enthält „wirklich“. */
    String text(String source) {
        StringBuilder text = new StringBuilder();
        if (withoutReason() > 0) {
            text.append("Wirklich ").append(withoutReason()).append(withoutReason() == 1 ? " Ressource" : " Ressourcen")
                    .append(" ohne Erwerbsgrund aus dem lokalen Bestand der Quelle ").append(source)
                    .append(" entfernen?");
        } else {
            text.append("Keine Ressource der Quelle ").append(source)
                    .append(" ist nachweislich ohne Erwerbsgrund. Wirklich bereinigen?");
        }
        StringBuilder scope = new StringBuilder();
        for (CleanupPreview preview : previews) {
            int count = preview.withoutReason().size() + preview.unknown().size();
            if (count > 0) {
                scope.append(scope.length() == 0 ? "" : ", ").append(preview.spaceKey()).append(" (")
                        .append(preview.withoutReason().size());
                if (!preview.unknown().isEmpty()) {
                    scope.append(" + ").append(preview.unknown().size()).append(" unbekannt");
                }
                scope.append(')');
            }
        }
        text.append(" Umfang: ").append(scope).append('.');
        if (unknown() > 0) {
            text.append(' ').append(unknown()).append(unknown() == 1 ? " Ressource hat" : " Ressourcen haben")
                    .append(" einen unbekannten Grund, weil sie vor dem Merken der Gründe erworben wurden; sie werden "
                            + "nur entfernt, wenn das Feld unten angekreuzt ist.");
        }
        if (incomplete() > 0) {
            text.append(' ').append(incomplete()).append(incomplete() == 1 ? " Bereich bleibt" : " Bereiche bleiben")
                    .append(" unangetastet, weil die Inventur dort nicht vollständig war.");
        }
        text.append(" Die Originale in der Quelle bleiben unberührt.");
        return text.toString();
    }

    /** Je Bereich die zu entfernenden Ressourcen; mit {@code includeUnknown} auch die mit unbekanntem Grund. */
    Map<String, Set<String>> chosen(boolean includeUnknown) {
        Map<String, Set<String>> chosen = new LinkedHashMap<>();
        for (CleanupPreview preview : previews) {
            Set<String> ids = new TreeSet<>(preview.withoutReason());
            if (includeUnknown) {
                ids.addAll(preview.unknown());
            }
            if (!ids.isEmpty()) {
                chosen.put(preview.spaceKey(), Set.copyOf(ids));
            }
        }
        return chosen;
    }
}
