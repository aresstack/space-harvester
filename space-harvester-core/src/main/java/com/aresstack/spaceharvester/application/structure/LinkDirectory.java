package com.aresstack.spaceharvester.application.structure;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Optional;

/**
 * Verweise einer lokal gespeicherten Seite in beide Richtungen. Belegt vom Verzeichnis der Verweise (Paket V1); bis
 * dahin gilt {@link #NONE}.
 */
@FunctionalInterface
public interface LinkDirectory {

    /** Kein Verzeichnis: Verweise sind unbekannt, nicht leer. */
    LinkDirectory NONE = (caller, page) -> Optional.empty();

    /**
     * @return leer, wenn das Verzeichnis die Seite nicht kennt
     */
    Optional<Links> links(AccessContext caller, PageKey page);

    /**
     * Verweise einer Seite.
     *
     * @param outgoing Ziele, auf die die Seite verweist
     * @param incoming Seiten, die auf sie verweisen
     * @param complete ob das Verzeichnis für diese Seite vollständig ist
     */
    record Links(List<PageKey> outgoing, List<PageKey> incoming, boolean complete) {

        public Links {
            outgoing = List.copyOf(outgoing);
            incoming = List.copyOf(incoming);
        }
    }
}
