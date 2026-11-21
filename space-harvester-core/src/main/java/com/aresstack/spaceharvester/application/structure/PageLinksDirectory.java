package com.aresstack.spaceharvester.application.structure;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * {@link LinkDirectory} über das Verzeichnis der Verweise (Paket V1, {@link PageLinks}). Ist die Ausgangsseite dort
 * nicht erfasst ({@link PageLinks.Links#outgoingKnown()} falsch, etwa weil ihre Quelle keinen Extraktor hat oder ihre
 * Payload fehlt), sind ihre ausgehenden Verweise unbekannt; eingehende Verweise anderer erfasster Seiten werden trotzdem
 * geliefert, als unvollständig. Fehlen auch sie, gelten die Verweise als unbekannt, nicht als leer. Vollständig ist die Antwort nur, wenn
 * {@link PageLinks.Links#complete()} gilt (Seite erfasst, alle bekannten Seiten der Quelle erfasst, erster Abgleich
 * beendet). Rechte prüft das Verzeichnis selbst.
 */
public final class PageLinksDirectory implements LinkDirectory {

    private final BiFunction<AccessContext, PageKey, PageLinks.Links> links;

    private PageLinksDirectory(BiFunction<AccessContext, PageKey, PageLinks.Links> links) {
        this.links = Objects.requireNonNull(links, "links");
    }

    /** Über das Verzeichnis der Verweise. */
    public static LinkDirectory of(PageLinks links) {
        Objects.requireNonNull(links, "links");
        return new PageLinksDirectory(links::links);
    }

    /** Über eine Abfrage mit der Antwort von {@link PageLinks#links(AccessContext, PageKey)}, etwa für Tests. */
    static LinkDirectory from(BiFunction<AccessContext, PageKey, PageLinks.Links> links) {
        return new PageLinksDirectory(links);
    }

    @Override
    public Optional<Links> links(AccessContext caller, PageKey page) {
        PageLinks.Links found = links.apply(caller, page);
        if (found == null) {
            return Optional.empty();
        }
        if (!found.outgoingKnown()) {
            // Seite selbst nicht erfasst: Ausgehendes unbekannt, eingehende Verweise anderer erfasster Seiten gelten
            // trotzdem; ohne beides bleibt es „unbekannt“.
            return found.incoming().isEmpty() ? Optional.empty()
                    : Optional.of(new Links(List.of(), found.incoming(), false));
        }
        return Optional.of(new Links(found.outgoing(), found.incoming(), found.complete()));
    }
}
