package com.aresstack.spaceharvester.application.changelog;

import java.util.List;

/**
 * Eine Seite roher Zeilen, neueste zuerst.
 *
 * @param entries die Zeilen dieser Seite
 * @param total   alle Treffer, die der Aufrufer sehen darf
 */
public record ChangeEntries(List<ChangeEntry> entries, int total, int offset, int limit) {

    public ChangeEntries {
        entries = List.copyOf(entries);
    }
}
