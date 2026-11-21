package com.aresstack.spaceharvester.application.links;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.util.List;

/**
 * Quellenspezifischer Port: liest die Verweisziele einer Seite aus ihrer nativen Payload, ohne Netz und ohne die
 * Quelle zu fragen. Umgesetzt in {@code source..} (etwa Confluence: {@code body.storage}). Eine Quelle ohne Extraktor
 * hat kein Verzeichnis: Ihre Seiten gelten als nicht erfasst, geraten wird nichts.
 */
public interface PageLinkExtractor {

    /**
     * Ob dieser Extraktor Seiten dieser Art liest, allein nach den Kopfdaten (etwa der Ressourcen-ID der Quelle); so
     * lädt der Abgleich keine Payload, die niemand lesen kann.
     */
    boolean supports(PageMetadata metadata);

    /**
     * Die Verweisziele in Reihenfolge der Seite, jedes einmal, ohne die Seite selbst, ohne Anhänge und Blogbeiträge.
     *
     * @throws IllegalArgumentException wenn die Payload nicht lesbar ist oder ein anderes Format hat; die Seite gilt
     *                                  dann als nicht erfasst
     */
    List<LinkTarget> targets(PageSnapshot snapshot);
}
