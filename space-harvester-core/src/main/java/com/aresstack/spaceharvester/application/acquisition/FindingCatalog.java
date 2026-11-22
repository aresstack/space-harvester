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
     * Wie {@link #offer}, nennt dazu die angenommenen Seiten (Abnahme #1082): Was nicht angenommen ist, etwa weil die
     * Warteschlange voll ist, bietet der Anbietende später wieder an. Ohne eigene Umsetzung gilt alles als angenommen.
     */
    default java.util.Set<String> accept(AccessContext finder, String sourceId, List<Finding> findings) {
        offer(finder, sourceId, findings);
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        if (findings != null) {
            findings.forEach(finding -> {
                if (finding != null) {
                    all.add(finding.pageId());
                }
            });
        }
        return all;
    }

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
