package com.aresstack.spaceharvester.deck.editor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port für den lokalen Verlauf des gemeinsamen Editors, nachgebaut nach {@code LocalHistoryService} von MainframeMate:
 * je Ablage und Adresse die Versionen „geöffnet“ und „vor Speichern“, neueste zuerst. Der Vergleich im Editor zeigt
 * sie gegen den aktuellen Text.
 */
public interface VersionHistory {

    /** Eine aufbewahrte Version. */
    record Version(String id, Instant at, int length, String label) {
    }

    /** Merkt sich {@code text}; gleicht er der neuesten Version, entsteht keine neue. */
    void record(String store, String location, String text, String label);

    /** Alle Versionen, neueste zuerst. */
    List<Version> versions(String store, String location);

    Optional<String> content(String store, String location, String id);

    /** Kein Verlauf (Tests, Ablagen ohne Ort). */
    VersionHistory NONE = new VersionHistory() {
        @Override
        public void record(String store, String location, String text, String label) {
        }

        @Override
        public List<Version> versions(String store, String location) {
            return List.of();
        }

        @Override
        public Optional<String> content(String store, String location, String id) {
            return Optional.empty();
        }
    };
}
