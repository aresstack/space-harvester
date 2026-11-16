package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Optional;

/** Bildet beim Ersetzen eines Inhalts im Cache den Unterschied der Lesetexte (ADR 0030, Abschnitt 4). */
public interface ContentDiffer {

    /** @return der Unterschied, oder leer, wenn ein Format nicht lesbar ist */
    Optional<TextDiff> diff(PageSnapshot older, PageSnapshot newer);

    /** Ohne Lesetext: kein Unterschied. */
    ContentDiffer NONE = (older, newer) -> Optional.empty();
}
