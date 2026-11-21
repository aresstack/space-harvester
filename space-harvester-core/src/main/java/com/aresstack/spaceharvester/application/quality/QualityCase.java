package com.aresstack.spaceharvester.application.quality;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Eine Testfrage.
 *
 * @param line     Zeile in der Datei ab 1
 * @param id       eindeutige Kennung, steht als einziges Merkmal der Frage in Bericht und Vergleich
 * @param question Wortlaut, wie man ihn dem Chat stellt; nie in Logs
 * @param scope    Bereiche, mindestens einer
 * @param expected erwartete Fundstellen; leer nur bei {@link CaseKind#KEINE}
 * @param kinds    Arten, mindestens eine
 */
public record QualityCase(int line, String id, String question, List<ScopeSpec> scope, List<PageRef> expected,
                          Set<CaseKind> kinds) {

    public QualityCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        scope = List.copyOf(scope);
        expected = List.copyOf(expected);
        kinds = Set.copyOf(kinds);
        if (scope.isEmpty() || kinds.isEmpty()) {
            throw new IllegalArgumentException("Bereich und Art fehlen");
        }
    }

    /** Ob die Antwort nirgends steht. */
    public boolean noAnswer() {
        return kinds.contains(CaseKind.KEINE);
    }
}
