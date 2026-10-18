package com.aresstack.spaceharvester.application.navigation;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    /** Größte Trefferzahl einer Titelsuche. */
    public static final int MAX_TITLE_LIMIT = 100;
    /** So tief fragt die Titelsuche die Quelle höchstens, wenn gesperrte Seiten Treffer verdrängen. */
    static final int MAX_TITLE_DEPTH = 1000;

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
        // Ohne Freigabe auf die Quelle fragt der Anwendungsfall sie gar nicht erst; der Space einer Seite steht erst
        // nach dem Lesen fest, die Prüfung danach verdeckt eine Verweigerung wie eine unbekannte Seite.
        access.require(caller, parentPageId == null
                ? ResourceScope.space(sourceId, Objects.requireNonNull(spaceKey, "spaceKey"))
                : ResourceScope.source(sourceId), AccessKind.METADATA);
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
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        HarvestSource.PagePosition position = source(sourceId).position(pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, position.partitionKey(), pageId),
                AccessKind.METADATA);
        return new Position(sourceId, position.partitionKey(), position.partitionName(),
                visible(caller, sourceId, position.ancestors()), node(caller, position.page()));
    }

    /**
     * Seiten einer Quelle über ihren Titel, auch nie abgeglichene: Treffer der Quelle ({@link HarvestSource#findByTitle})
     * in ihrer Reihenfolge, ohne Katalog, ohne Umsortieren und ohne Laden. Jeder Treffer trägt seinen Space; kennt ihn
     * die Quelle nicht, nennt ihn der Cache, sonst liest die Navigation die Kopfdaten der Seite. Vorfahren je Treffer
     * liefert {@link #position}. Jeder Treffer geht wie die Kinder durch den {@link AccessGuard} ({@code metadata} auf
     * die Seite); verdrängen gesperrte Seiten sichtbare, fragt die Navigation die Quelle tiefer, höchstens
     * {@value #MAX_TITLE_DEPTH} Treffer. {@link TitleMatches#truncated()} zählt nur sichtbare Seiten: es gibt mehr als
     * {@code limit} davon. Quellen ohne {@link com.aresstack.spaceharvester.application.sources.SourceCapability#TITLE_SEARCH}
     * finden nichts.
     *
     * @param spaceKeys nur diese Spaces, leer für alle
     * @param title     Suchtext, ohne Rücksicht auf Groß- und Kleinschreibung im Titel enthalten
     * @param limit     1 bis {@value #MAX_TITLE_LIMIT}
     * @throws InvalidQueryException ohne Suchtext oder mit ungültigem {@code limit}
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code metadata} auf die
     *                                                                              Quelle
     */
    public TitleMatches findByTitle(AccessContext caller, String sourceId, Set<String> spaceKeys, String title,
                                    int limit) {
        String term = title == null ? "" : title.trim();
        if (term.isEmpty()) {
            throw new InvalidQueryException("title fehlt");
        }
        if (limit < 1 || limit > MAX_TITLE_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_TITLE_LIMIT + " liegen");
        }
        HarvestSource source = source(sourceId);
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        SearchSpaces spaces = new SearchSpaces(spaceKeys, Set.of());
        // Jede Seite fragt höchstens einmal nach ihrem Space, auch wenn die Suche tiefer geht.
        Map<String, Optional<PageNode>> located = new HashMap<>();
        int wanted = limit + 1;
        while (true) {
            List<PageNode> found = source.findByTitle(term, spaces, wanted);
            List<PageNode> visible = visible(caller, sourceId, found.stream()
                    .map(node -> located.computeIfAbsent(node.pageId(), id -> Optional.ofNullable(
                            located(source, node))))
                    .flatMap(Optional::stream).filter(node -> spaces.permits(node.partitionKey())).toList());
            if (visible.size() > limit) {
                return new TitleMatches(sourceId, visible.subList(0, limit), true);
            }
            if (found.size() < wanted || wanted >= MAX_TITLE_DEPTH) {
                return new TitleMatches(sourceId, visible, false);
            }
            wanted = Math.min(wanted * 2, MAX_TITLE_DEPTH);
        }
    }

    /**
     * Querverweise einer Seite: ausgehend in Reihenfolge der Seite und, wenn die Quelle sie vollständig kennt,
     * eingehend. Ziele, die der Aufrufer nicht sehen darf, fehlen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException verdeckt, ohne {@code content}
     */
    public References references(AccessContext caller, String sourceId, String pageId) {
        HarvestSource source = source(sourceId);
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
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

    private PageNode located(HarvestSource source, PageNode node) {
        PageNode known = withSpace(source.id(), node);
        return known.partitionKey() != null ? known : liveSpace(source, known);
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
     * Treffer einer Titelsuche in der Reihenfolge der Quelle.
     *
     * @param truncated ob es mehr sichtbare Treffer gibt, als geliefert sind
     */
    public record TitleMatches(String sourceId, List<PageNode> pages, boolean truncated) {

        public TitleMatches {
            pages = List.copyOf(pages);
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
