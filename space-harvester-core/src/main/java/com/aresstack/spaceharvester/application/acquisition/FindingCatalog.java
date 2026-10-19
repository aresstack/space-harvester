package com.aresstack.spaceharvester.application.acquisition;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.util.List;
import java.util.Objects;

/**
 * Nimmt die Funde der quelleneigenen Suche entgegen (Schlagwortsuche, damit auch die semantische Vorbereitung, und
 * Titelsuche), damit Seiten, die noch nicht im Katalog stehen, als Kopfdaten hineinkommen (ADR 0016). Umgesetzt von
 * {@link FindingCataloging}. Ein Aufruf darf die Suche weder verlangsamen noch scheitern lassen: Er reiht höchstens ein
 * und kehrt sofort zurück.
 */
public interface FindingCatalog {

    /** Katalogisiert nichts, etwa ohne Cache. */
    FindingCatalog NONE = (finder, sourceId, findings) -> {
    };

    /**
     * Bietet die Funde einer Quelle an. Nur Funde eines technischen Aufrufers ohne Benutzeranmeldung zählen; die eines
     * Endnutzers fallen still weg.
     *
     * @param finder   wer gesucht hat
     * @param sourceId Quelle der Funde
     */
    void offer(AccessContext finder, String sourceId, List<Finding> findings);

    /**
     * Ein Fund, so wie die Suche ihn liefert.
     *
     * @param pageId  ID der Seite in der Quelle
     * @param version Version laut Suche oder {@code null}, wenn die Suche keine nennt
     */
    record Finding(String pageId, String version) {

        public Finding {
            Objects.requireNonNull(pageId, "pageId");
            version = version == null || version.isEmpty() ? null : version;
        }
    }
}
