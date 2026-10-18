package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import java.util.Objects;

/**
 * Was sich an einer Seite gegenüber der bestätigten Generation geändert hat und was deshalb zu tun ist.
 *
 * @param pageId   ID der Seite
 * @param kind     Art der Änderung
 * @param previous Stand in der bestätigten Generation oder {@code null} für eine neue Seite
 * @param current  Stand laut Inventur oder {@code null}, wenn die Seite dort fehlt
 */
public record PageChange(String pageId, Kind kind, BaselineEntry previous, PageMetadataListing.Entry current) {

    public enum Kind {
        /** Nicht in der Generation: Inhalt laden. */
        NEW,
        /** Neue Version (oder Version unbekannt und {@code metadataHash} anders): Inhalt neu laden. */
        CONTENT_CHANGED,
        /** Gleiche Version, aber {@code metadataHash} anders (umbenannt, verschoben): nur Metadaten aktualisieren. */
        METADATA_CHANGED,
        /** Nur {@code attributesHash} anders (z. B. Labels): nur Attribute/Index aktualisieren, kein Inhaltsabruf. */
        ATTRIBUTES_CHANGED,
        UNCHANGED,
        /** Fehlt in einer bestätigten Inventur: Kandidat zum Entfernen, erst nach {@link RemovalCheck}. */
        MISSING,
        /** Fehlt in einer abgebrochenen oder widersprüchlichen Inventur: unbekannt, bleibt unverändert. */
        NOT_SEEN,
        /**
         * Gelistet, aber weder in der Generation noch im Cache, und der Abgleich lädt Unbekanntes nicht (ADR 0014,
         * Punkt 2): kein Inhaltsabruf, nur die Kopfdaten kommen in den Katalog; die Generation führt die Seite als
         * fehlend.
         */
        NOT_LOADED
    }

    public PageChange {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(kind, "kind");
    }

    /** Ob der native Inhalt neu geladen werden muss. */
    public boolean requiresContent() {
        return kind == Kind.NEW || kind == Kind.CONTENT_CHANGED;
    }

    /** Ob die Seite in einen anderen Space oder unter eine andere Elternseite gewandert ist. */
    public boolean moved() {
        return previous != null && current != null
                && (!previous.spaceKey().equals(current.page().partitionKey())
                || !Objects.equals(previous.parentPageId(), current.page().parentPageId()));
    }

    /** Ob sich der {@code attributesHash} geändert hat, auch zusammen mit Version oder Metadaten. */
    public boolean attributesChanged() {
        return previous != null && current != null && !previous.attributesHash().equals(current.attributesHash());
    }
}
