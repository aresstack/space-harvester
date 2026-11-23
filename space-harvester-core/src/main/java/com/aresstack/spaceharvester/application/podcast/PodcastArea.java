package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import java.util.Objects;

/**
 * Der Bereich, dessen Zusammenfassung ein Podcast erzählt (ADR 0034, Abschnitt 7): heute die Ebene eines Updates
 * (Quelle, Update, Space), später auch eine Auswahl wie ein Favoriten-Ordner. Der Schlüssel des Bereichs geht in den
 * Schlüssel des Podcasts ein; ein neuer Podcast desselben Bereichs ersetzt den vorigen. Für Updates ist er so gebildet,
 * dass die Podcast-Schlüssel wie bisher heißen.
 *
 * @param key      der Bereichsschlüssel, stabil über Neustarts
 * @param sourceId die Quelle, wenn der Bereich zu genau einer gehört, sonst {@code null}
 * @param updateId das Update bei einer Update-Ebene, sonst {@code null}
 * @param spaceKey der Space der Ebene, {@code null} für die ganze Quelle oder eine Auswahl
 */
public record PodcastArea(String key, String sourceId, String updateId, String spaceKey) {

    public PodcastArea {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Leerer Bereichsschlüssel");
        }
    }

    /** Die Ebene eines Updates: die ganze Quelle ({@code spaceKey} {@code null}) oder ein Space. */
    public static PodcastArea update(String sourceId, String updateId, String spaceKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        return new PodcastArea(sourceId + '\n' + updateId + '\n' + (spaceKey == null ? "" : spaceKey), sourceId,
                updateId, spaceKey);
    }

    /** Die Ebene einer Update-Zusammenfassung. */
    public static PodcastArea of(UpdateSummary summary) {
        return update(summary.sourceId(), summary.updateId(), summary.spaceKey());
    }
}
