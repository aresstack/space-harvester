package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Optional;

/**
 * Liest aus einer gespeicherten Fassung, wer sie zuletzt bearbeitet hat (ADR 0031), soweit die Quelle es mit dem
 * Inhalt liefert. Personenbezug: Das Ergebnis steht nur in der Protokollzeile, nie in Logs.
 */
@FunctionalInterface
public interface PageEditors {

    /** @return der Bearbeiter, leer, wenn das Format ihn nicht trägt oder nicht lesbar ist; wirft nie */
    Optional<String> editor(PageSnapshot snapshot);

    /** Ohne Quelle, die Bearbeiter liefert. */
    PageEditors NONE = snapshot -> Optional.empty();
}
