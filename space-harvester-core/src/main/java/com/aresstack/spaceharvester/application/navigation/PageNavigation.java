package com.aresstack.spaceharvester.application.navigation;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.util.List;
import java.util.Objects;

/**
 * Anwendungsfall Live-Navigation (#177), wie {@code PageNavigationUseCase} im Enterprise AI Client: Kindseiten eines
 * Knotens in der Reihenfolge der Quelle mit {@code hasChildren}, die Lage einer Seite (Space mit Namen, Vorfahren) und
 * ihre Querverweise. Alles live, ohne Cache als Voraussetzung; der Cache nennt höchstens den Space eines
 * Verweisziels, sonst fragt der Anwendungsfall die Quelle danach.
 *
 * <p>Die Quelle antwortet mit ihren eigenen Zugangsdaten. Jeder Knoten geht daher einzeln durch den {@link AccessGuard}
 * ({@code metadata} auf die Seite); was der Aufrufer nicht sehen darf, fehlt ohne Spur, auch in Zählern. Für
 * Endnutzer wird ein bekanntes {@code hasChildren=true} zu „unbekannt“, weil darunter nur gesperrte Seiten liegen
 * könnten. Querverweise lesen den Inhalt der Seite und verlangen daher {@code content} auf sie.
 */
public class PageNavigation {

    private final SourceCatalog catalog;
    private final PageCacheReader cache;
    private final AccessGuard access;

    /** @param cache nur, um den Space von Verweiszielen zu kennen; {@code null} ohne Cache */
    public PageNavigation(SourceCatalog catalog, PageCacheReader cache, AccessGuard access) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.cache = cache;
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * Ein Stapel Kindseiten. Die Quelle wählt die Stapelgröße; ein Stapel kann nach dem Beschneiden leer sein und
     * trotzdem einen {@link Children#nextCursor()} haben.
     *
     * @param spaceKey     Space, dessen Wurzelseiten gemeint sind, wenn {@code parentPageId} {@code null} ist
     * @param parentPageId Seite, deren Kinder gemeint sind, oder {@code null}
     * @param cursor       {@code null} oder {@link Children#nextCursor()} desselben Knotens
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code metadata} auf Space
     *                                                                              bzw. Elternseite (verdeckt wie
     *                                                                              unbekannt)
     */
    public Children children(AccessContext caller, String sourceId, String spaceKey, String parentPageId,
                             String cursor) {
        HarvestSource source = source(sourceId);
        if (parentPageId == null) {
            access.require(caller, ResourceScope.space(sourceId, Objects.requireNonNull(spaceKey, "spaceKey")),
                    AccessKind.METADATA);
        }
        HarvestSource.ChildPages batch = source.children(spaceKey, parentPageId, cursor);
        if (parentPageId != null) {
            access.requireConcealed(caller, ResourceScope.page(sourceId, batch.partitionKey(), parentPageId),
                    AccessKind.METADATA);
        }
        return new Children(sourceId, batch.partitionKey(), parentPageId, visible(caller, sourceId, batch.pages()),
                batch.nextCursor());
    }

    /**
     * Wo eine Seite steht: Space mit Namen, Vorfahren von oben nach unten (Breadcrumbs) und die Seite selbst.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException verdeckt, ohne {@code metadata}
     */
    public Position position(AccessContext caller, String sourceId, String pageId) {
        HarvestSource.PagePosition position = source(sourceId).position(pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, position.partitionKey(), pageId),
                AccessKind.METADATA);
        return new Position(sourceId, position.partitionKey(), position.partitionName(),
                visible(caller, sourceId, position.ancestors()), node(caller, position.page()));
    }

    /**
     * Querverweise einer Seite: ausgehend in Reihenfolge der Seite und, wenn die Quelle sie vollständig kennt,
     * eingehend. Ziele, die der Aufrufer nicht sehen darf, fehlen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException verdeckt, ohne {@code content}
     */
    public References references(AccessContext caller, String sourceId, String pageId) {
        HarvestSource source = source(sourceId);
        String spaceKey = source.pageMetadata(pageId).partitionKey();
        access.requireConcealed(caller, ResourceScope.page(sourceId, spaceKey, pageId), AccessKind.CONTENT);
        HarvestSource.PageReferences references = source.references(pageId);
        return new References(sourceId, pageId, visibleTargets(caller, source, references.outgoing()),
                references.incoming() == null ? null : visibleTargets(caller, source, references.incoming()));
    }

    /**
     * Wie {@link #visible}, aber Verweisziele ohne bekannten Space, die der Aufrufer nicht schon ohne Space sehen
     * darf, fragt der Anwendungsfall einzeln nach ihrem Space; ohne Antwort fehlen sie.
     */
    private List<PageNode> visibleTargets(AccessContext caller, HarvestSource source, List<PageNode> nodes) {
        String sourceId = source.id();
        return visible(caller, sourceId, nodes.stream().map(node -> withSpace(sourceId, node))
                .map(node -> node.partitionKey() != null || access.permits(caller,
                        ResourceScope.page(sourceId, null, node.pageId()), AccessKind.METADATA)
                        ? node : liveSpace(source, node))
                .filter(Objects::nonNull).toList());
    }

    private static PageNode liveSpace(HarvestSource source, PageNode node) {
        try {
            return new PageNode(node.pageId(), node.title(), source.pageMetadata(node.pageId()).partitionKey(),
                    node.hasChildren());
        } catch (SourceAccessException e) {
            return null;
        }
    }

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }

    private List<PageNode> visible(AccessContext caller, String sourceId, List<PageNode> nodes) {
        return nodes.stream().map(node -> withSpace(sourceId, node))
                .filter(node -> access.permits(caller, ResourceScope.page(sourceId, node.partitionKey(),
                        node.pageId()), AccessKind.METADATA))
                .map(node -> node(caller, node)).toList();
    }

    /** Verweisziele kennen ihren Space oft nicht; der Cache kennt ihn, sonst entscheidet die Seite allein. */
    private PageNode withSpace(String sourceId, PageNode node) {
        if (node.partitionKey() != null || cache == null) {
            return node;
        }
        try {
            return cache.page(new PageKey(sourceId, node.pageId()))
                    .map(page -> new PageNode(node.pageId(), node.title(), page.header().metadata().partitionKey(),
                            node.hasChildren()))
                    .orElse(node);
        } catch (RuntimeException e) {
            return node;
        }
    }

    private static PageNode node(AccessContext caller, PageNode node) {
        if (caller.kind() == AccessContext.PrincipalKind.END_USER && Boolean.TRUE.equals(node.hasChildren())) {
            return new PageNode(node.pageId(), node.title(), node.partitionKey(), null);
        }
        return node;
    }

    /**
     * Ein Stapel Kindseiten.
     *
     * @param parentPageId Elternseite oder {@code null} für die Wurzelseiten des Space
     * @param nextCursor   Cursor des nächsten Stapels, {@code null} wenn alle geliefert sind
     */
    public record Children(String sourceId, String spaceKey, String parentPageId, List<PageNode> pages,
                           String nextCursor) {

        public Children {
            pages = List.copyOf(pages);
        }
    }

    /**
     * Die Lage einer Seite.
     *
     * @param ancestors Vorfahren von oben nach unten, die der Aufrufer sehen darf
     */
    public record Position(String sourceId, String spaceKey, String spaceName, List<PageNode> ancestors,
                           PageNode page) {

        public Position {
            ancestors = List.copyOf(ancestors);
        }
    }

    /**
     * Querverweise einer Seite.
     *
     * @param incoming eingehende Verweise oder {@code null}, wenn die Quelle sie nicht vollständig kennt
     */
    public record References(String sourceId, String pageId, List<PageNode> outgoing, List<PageNode> incoming) {

        public References {
            outgoing = List.copyOf(outgoing);
            incoming = incoming == null ? null : List.copyOf(incoming);
        }
    }
}
