package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.application.holdings.Selection;

import java.util.List;
import java.util.Objects;

/**
 * Der Knotenlieferant der App für eine Sammlung im Chat (ADR 0034 §5): Ist ein Favoriten-Ordner der Bereich, nennt
 * {@code seiten_auflisten} ohne Knoten seine Favoriten und seine Unterordner als virtuelle Knoten {@code ordner/<id>}.
 * Der Core-Typ des Bereichs bleibt die aufgelöste {@code PageSelection}; jede gelistete Seite wird zusätzlich gegen sie
 * geprüft wie {@code allows()}.
 */
public interface CollectionNodes {

    /**
     * Der Inhalt eines Ordners des Bereichs.
     *
     * @param folderId ein Unterordner oder {@code null} für den gewählten Ordner selbst
     * @return {@code null}, wenn der Ordner nicht (mehr) zum Bereich gehört; dann antwortet das Werkzeug wie bei einer
     *         fremden Seite
     */
    Content content(String folderId);

    /** Ein Unterordner als virtueller Knoten. */
    record Folder(String id, String name) {

        public Folder {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Was direkt in einem Ordner liegt.
     *
     * @param folders seine Unterordner, soweit sie zum Bereich gehören
     * @param roots   je direkter Favorit seine Wurzel (Seite samt Unterseiten, Space oder Quelle, ADR 0034 §1)
     */
    record Content(List<Folder> folders, List<Selection.Root> roots) {

        public Content {
            folders = List.copyOf(folders);
            roots = List.copyOf(roots);
        }
    }
}
