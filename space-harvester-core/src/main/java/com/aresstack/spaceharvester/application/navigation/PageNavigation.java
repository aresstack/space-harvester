package com.aresstack.spaceharvester.application.navigation;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.acquisition.FindingCatalog;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.InvalidNavigationCursorException;
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
    private final NavigationOrder navigationOrder;
    private final AccessGuard access;
    private final FindingCatalog findings;
    private final PageLinks links;
    /** Was der Benutzer ausgeschlossen hat, fehlt im Baum und in der Titelsuche (Angelo 26.11.2026). */
    private volatile com.aresstack.spaceharvester.application.sync.Exclusions exclusions =
            com.aresstack.spaceharvester.application.sync.Exclusions.NONE;

    /** Remember navigation paths independently of downloaded documents. */
    private final Map<PageKey, SeenPage> browsedPages = new java.util.concurrent.ConcurrentHashMap<>();

    private record SeenPage(PageNode page, String parentPageId) {
    }

    /** @param cache nur, um den Space von Verweiszielen zu kennen; {@code null} ohne Cache */
    public PageNavigation(SourceCatalog catalog, PageCacheReader cache, AccessGuard access) {
        this(catalog, cache, access, FindingCatalog.NONE);
    }

    /**
     * @param findings nimmt die Treffer der Titelsuche zum Katalogisieren als Kopfdaten (ADR 0016), ohne die Suche
     *                 aufzuhalten
     */
    public PageNavigation(SourceCatalog catalog, PageCacheReader cache, AccessGuard access, FindingCatalog findings) {
        this(catalog, cache, access, findings, null);
    }

    /**
     * @param links Verzeichnis der Verweise (Paket V1) für die Rückrichtung, wenn die Quelle sie nicht kennt;
     *              {@code null} ohne
     */
    public PageNavigation(SourceCatalog catalog, PageCacheReader cache, AccessGuard access, FindingCatalog findings,
                          PageLinks links) {
        this.links = links;
        this.findings = Objects.requireNonNull(findings, "findings");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.cache = cache;
        this.navigationOrder = new NavigationOrder(cache);
        this.access = Objects.requireNonNull(access, "access");
    }

    /** Ausgeschlossenes lässt die Navigation weg; ohne Aufruf ist nichts ausgeschlossen. */
    public void exclusions(com.aresstack.spaceharvester.application.sync.Exclusions exclusions) {
        this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
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
        return children(caller, sourceId, spaceKey, parentPageId, cursor, false);
    }

    /**
     * Wie {@link #children(AccessContext, String, String, String, String)}, mit {@code withExcluded} auch die
     * ausgeschlossenen Kinder: für die Übersicht in den Einstellungen, in der der Benutzer selbst ausschließt und
     * wieder einblendet. Rechte gelten unverändert.
     */
    public Children children(AccessContext caller, String sourceId, String spaceKey, String parentPageId,
                             String cursor, boolean withExcluded) {
        HarvestSource source = source(sourceId);
        // Ohne Freigabe auf die Quelle fragt der Anwendungsfall sie gar nicht erst; der Space einer Seite steht erst
        // nach dem Lesen fest, die Prüfung danach verdeckt eine Verweigerung wie eine unbekannte Seite.
        access.require(caller, parentPageId == null
                ? ResourceScope.space(sourceId, Objects.requireNonNull(spaceKey, "spaceKey"))
                : ResourceScope.source(sourceId), AccessKind.METADATA);
        NavigationOrder.Request request = navigationOrder.prepare(sourceId, spaceKey, parentPageId, cursor,
                source.navigationUsesGroups());
        HarvestSource.ChildPages batch = source.children(spaceKey, parentPageId, request.sourceCursor);
        if (parentPageId != null) {
            access.requireConcealed(caller, ResourceScope.page(sourceId, batch.partitionKey(), parentPageId),
                    AccessKind.METADATA);
        }
        String nextCursor = navigationOrder.complete(request, batch);
        List<PageNode> permitted = permitted(caller, sourceId, batch.pages());
        rememberBrowsed(source, sourceId, parentPageId, permitted);
        List<PageNode> shown = withExcluded ? permitted : withoutExcluded(sourceId, permitted);
        return new Children(sourceId, batch.partitionKey(), parentPageId, shown, nextCursor);
    }

    /**
     * Preserve parent links learned from folder browsing even when no document was downloaded.
     * This path is useful immediately after the content index has been cleared.
     */
    private void rememberBrowsed(HarvestSource source, String sourceId, String parentId, List<PageNode> pages) {
        if (!source.navigationRootIsPage()) {
            return;
        }
        for (PageNode page : pages) {
            browsedPages.put(new PageKey(sourceId, page.pageId()), new SeenPage(page, parentId));
        }
    }

    /**
     * Elternseite einer Seite, wie sie das Durchblättern zuletzt gezeigt hat, auch ohne geladenes Dokument;
     * {@code null}, wenn unbekannt oder eine Wurzel.
     */
    public String browsedParent(String sourceId, String pageId) {
        SeenPage seen = pageId == null ? null : browsedPages.get(new PageKey(sourceId, pageId));
        return seen == null ? null : seen.parentPageId();
    }

    /**
     * Kindseiten, wie sie der Cache kennt, ohne die Quelle zu fragen: für den Baum, der zuerst diesen Stand zeigt und
     * {@link #children} danach im Hintergrund holt (Angelo 17.11.2026). In der Reihenfolge, in der die Quelle die Kinder
     * zuletzt lieferte (von {@link #children} gemerkt), damit der Baum nach ihrer Antwort nicht umsortiert; Kinder ohne
     * bekannte Position folgen nach Titel. {@code hasChildren} ist {@code true}, wenn der Cache Kinder kennt, sonst
     * unbekannt. Rechte wie bei {@link #children}.
     *
     * <p>Paginiert wie die Live-Navigation (ADR 0040, Punkt 11): Es gibt keine Gesamtgrenze, alle lokal bekannten Kinder
     * sind über {@link Children#nextCursor()} erreichbar, auch wenn die Quelle nicht antwortet. Der Cursor trägt den
     * Sortierschlüssel des letzten gelieferten Kindes; ändert sich der Cache dazwischen, setzt der nächste Stapel
     * dahinter fort, ohne Kinder doppelt zu liefern oder zu überspringen, die vorher schon da waren.
     *
     * @param cursor {@code null} oder {@link Children#nextCursor()} eines vorigen Aufrufs für denselben Knoten
     * @param limit  Stapelgröße, mindestens 1; ein Stapel kann nach dem Beschneiden kleiner sein
     * @return leer ohne Cache oder wenn der Cache keine (weiteren) Kinder kennt
     * @throws InvalidNavigationCursorException wenn {@code cursor} nicht von hier stammt
     */
    public Children cachedChildren(AccessContext caller, String sourceId, String spaceKey, String parentPageId,
                                   String cursor, int limit) {
        if (limit < 1) {
            throw new InvalidQueryException("limit muss mindestens 1 sein");
        }
        HarvestSource source = source(sourceId);
        access.require(caller, parentPageId == null
                ? ResourceScope.space(sourceId, Objects.requireNonNull(spaceKey, "spaceKey"))
                : ResourceScope.source(sourceId), AccessKind.METADATA);
        CachedChildKey after = cursor == null ? null : CachedChildKey.decode(cursor);
        if (cache == null || source.navigationUsesGroups()) {
            return new Children(sourceId, spaceKey, parentPageId, List.of(), null);
        }
        List<com.aresstack.spaceharvester.application.sources.PageMetadata> pages = new java.util.ArrayList<>();
        try {
            String afterPageId = null;
            List<PageHeader> batch;
            do {
                batch = cache.children(sourceId, spaceKey, parentPageId, afterPageId, limit);
                batch.forEach(header -> pages.add(header.metadata()));
                afterPageId = batch.isEmpty() ? null : batch.getLast().metadata().pageId();
            } while (afterPageId != null);
        } catch (com.aresstack.spaceharvester.application.query.CacheUnavailableException e) {
            return new Children(sourceId, spaceKey, parentPageId, List.of(), null);
        }
        String partition = spaceKey != null ? spaceKey
                : pages.isEmpty() ? null : pages.getFirst().partitionKey();
        if (parentPageId != null && partition != null) {
            access.requireConcealed(caller, ResourceScope.page(sourceId, partition, parentPageId),
                    AccessKind.METADATA);
        }
        Map<String, Integer> positions = new HashMap<>();
        try {
            List<String> order = cache.childOrder(sourceId, spaceKey, parentPageId);
            for (int i = 0; i < order.size(); i++) {
                positions.putIfAbsent(order.get(i), i);
            }
        } catch (RuntimeException e) {
            // Ohne bekannte Reihenfolge ordnet der Titel.
        }
        List<CachedChildKey> ordered = pages.stream()
                .map(page -> new CachedChildKey(positions.getOrDefault(page.pageId(), Integer.MAX_VALUE),
                        page.title().toLowerCase(java.util.Locale.ROOT), page.pageId(), page))
                .filter(key -> after == null || key.compareTo(after) > 0)
                .sorted()
                .toList();
        List<CachedChildKey> shown = ordered.subList(0, Math.min(limit, ordered.size()));
        List<PageNode> nodes = shown.stream().map(key -> cachedChild(sourceId, key.page())).toList();
        String next = ordered.size() > shown.size() ? shown.getLast().encode() : null;
        return new Children(sourceId, partition, parentPageId, visible(caller, sourceId, nodes), next);
    }

    private PageNode cachedChild(String sourceId, com.aresstack.spaceharvester.application.sources.PageMetadata page) {
        PageNode.Kind kind = switch (page.attributes().getOrDefault(
                com.aresstack.spaceharvester.application.sources.PageMetadata.RESOURCE_KIND, "")) {
            case com.aresstack.spaceharvester.application.sources.PageMetadata.FOLDER_KIND -> PageNode.Kind.FOLDER;
            case com.aresstack.spaceharvester.application.sources.PageMetadata.FILE_KIND,
                 com.aresstack.spaceharvester.application.sources.PageMetadata.LINK_KIND -> PageNode.Kind.FILE;
            default -> PageNode.Kind.PAGE;
        };
        Boolean children = kind == PageNode.Kind.FILE ? Boolean.FALSE
                : cache.hasChildren(sourceId, page.pageId()) ? Boolean.TRUE : null;
        return new PageNode(page.pageId(), page.title(), page.partitionKey(), children, kind);
    }

    /** Sortierschlüssel eines Kindes aus dem Cache; als Cursor trägt er die Fortsetzung selbst (ADR 0040). */
    private record CachedChildKey(int position, String title, String pageId,
                                  com.aresstack.spaceharvester.application.sources.PageMetadata page)
            implements Comparable<CachedChildKey> {

        private static final String PREFIX = "cache:";
        private static final java.util.Comparator<CachedChildKey> ORDER = java.util.Comparator
                .comparingInt(CachedChildKey::position).thenComparing(CachedChildKey::title)
                .thenComparing(CachedChildKey::pageId);

        @Override
        public int compareTo(CachedChildKey other) {
            return ORDER.compare(this, other);
        }

        String encode() {
            String plain = position + "\0" + title + "\0" + pageId;
            return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static CachedChildKey decode(String cursor) {
            try {
                if (!cursor.startsWith(PREFIX)) {
                    throw new IllegalArgumentException("fremder Cursor");
                }
                String plain = new String(java.util.Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length())),
                        java.nio.charset.StandardCharsets.UTF_8);
                String[] parts = plain.split("\0", -1);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("unvollständiger Cursor");
                }
                return new CachedChildKey(Integer.parseInt(parts[0]), parts[1], parts[2], null);
            } catch (IllegalArgumentException e) {
                throw new InvalidNavigationCursorException();
            }
        }
    }

    /**
     * Wo eine Seite steht: Space mit Namen, Vorfahren von oben nach unten (Breadcrumbs) und die Seite selbst.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException verdeckt, ohne {@code metadata}
     */
    public Position position(AccessContext caller, String sourceId, String pageId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        HarvestSource source = source(sourceId);
        HarvestSource.PagePosition position = source.position(pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, position.partitionKey(), pageId),
                AccessKind.METADATA);
        List<PageNode> ancestors = position.ancestors();
        List<PageNode> browsedPath = browsedAncestors(source, sourceId, pageId);
        if (browsedPath.size() > ancestors.size()) {
            ancestors = browsedPath;
        }
        List<PageNode> storedPath = cachedAncestors(source, sourceId, pageId);
        if (storedPath.size() > ancestors.size()) {
            ancestors = storedPath;
        }
        return new Position(sourceId, position.partitionKey(), position.partitionName(),
                visible(caller, sourceId, ancestors), node(caller, position.page()));
    }

    /** Build a path from the hierarchy already browsed, without needing a downloaded page or database. */
    private List<PageNode> browsedAncestors(HarvestSource source, String sourceId, String pageId) {
        if (!source.navigationRootIsPage()) {
            return List.of();
        }
        List<PageNode> result = new java.util.ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        String current = pageId;
        while (visited.add(current) && visited.size() < 100) {
            SeenPage seen = browsedPages.get(new PageKey(sourceId, current));
            if (seen == null) {
                return List.of();
            }
            String parent = seen.parentPageId();
            if (parent == null) {
                java.util.Collections.reverse(result);
                return List.copyOf(result);
            }
            SeenPage ancestor = browsedPages.get(new PageKey(sourceId, parent));
            if (ancestor == null) {
                return List.of();
            }
            result.add(ancestor.page());
            current = parent;
        }
        return List.of();
    }

    /**
     * Restore BSCW folders from persisted metadata when WebDAV has no remembered parent chain.
     * BSCW object URLs contain independent numeric IDs, not a hierarchical path. The final
     * parent without its own parent is the technical root represented by the Space row.
     */
    private List<PageNode> cachedAncestors(HarvestSource source, String sourceId, String pageId) {
        if (cache == null || !source.navigationRootIsPage()) {
            return List.of();
        }
        List<PageNode> ancestors = new java.util.ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        String current = pageId;
        try {
            while (visited.add(current) && visited.size() < 100) {
                Optional<com.aresstack.spaceharvester.application.snapshot.PageHeader> currentHeader =
                        cache.header(new PageKey(sourceId, current));
                if (currentHeader.isEmpty()) {
                    return List.of();
                }
                String parentId = currentHeader.get().metadata().parentPageId();
                if (parentId == null || visited.contains(parentId)) {
                    break;
                }
                Optional<com.aresstack.spaceharvester.application.snapshot.PageHeader> parentHeader =
                        cache.header(new PageKey(sourceId, parentId));
                if (parentHeader.isEmpty()) {
                    return List.of();
                }
                com.aresstack.spaceharvester.application.sources.PageMetadata parent =
                        parentHeader.get().metadata();
                if (parent.parentPageId() == null) {
                    break;
                }
                ancestors.add(new PageNode(parent.pageId(), parent.title(), parent.partitionKey(),
                        Boolean.TRUE, PageNode.Kind.FOLDER));
                current = parentId;
            }
        } catch (com.aresstack.spaceharvester.application.query.CacheUnavailableException unavailable) {
            return List.of();
        }
        java.util.Collections.reverse(ancestors);
        return List.copyOf(ancestors);
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
            catalogFindings(caller, sourceId, found);
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
     * Querverweise einer Seite: ausgehend in Reihenfolge der Seite live aus der Quelle, eingehend aus der Quelle, wenn
     * sie sie vollständig kennt, sonst aus dem Verzeichnis der Verweise über die lokal gespeicherten Seiten (Paket V1,
     * mit {@link References#coverage()}). Ziele, die der Aufrufer nicht sehen darf, fehlen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException verdeckt, ohne {@code content}
     */
    public References references(AccessContext caller, String sourceId, String pageId) {
        HarvestSource source = source(sourceId);
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        String spaceKey = source.pageMetadata(pageId).partitionKey();
        access.requireConcealed(caller, ResourceScope.page(sourceId, spaceKey, pageId), AccessKind.CONTENT);
        HarvestSource.PageReferences references = source.references(pageId);
        List<PageNode> outgoing = visibleTargets(caller, source, references.outgoing());
        if (references.incoming() != null || links == null) {
            return new References(sourceId, pageId, outgoing,
                    references.incoming() == null ? null : visibleTargets(caller, source, references.incoming()));
        }
        PageLinks.Links local = links.links(caller, new PageKey(sourceId, pageId), spaceKey);
        return new References(sourceId, pageId, outgoing,
                visible(caller, sourceId, local.incoming().stream().map(this::cachedNode).toList()), local.coverage());
    }

    /**
     * Ein Verweis einer Unterseite (Fahrplan P3, „mit Unterseiten“).
     *
     * @param origin   die Unterseite, von der der Verweis stammt
     * @param outgoing {@code true}: die Unterseite verweist auf {@code target}; {@code false}: {@code target} verweist
     *                 auf sie
     * @param target   die andere Seite
     */
    public record SubpageReference(PageNode origin, boolean outgoing, PageNode target) {

        public SubpageReference {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Die Verweise der Unterseiten einer Seite in beide Richtungen, aus dem Verzeichnis der lokal gespeicherten Seiten
     * ({@link PageLinks#links(AccessContext, java.util.Collection)}), ohne die Quelle zu fragen: dynamisch beim
     * Anzeigen, nichts wird in die Elternseite kopiert (Fahrplan P3). Unterseiten und Ziele ohne Leserecht fehlen.
     * Reihenfolge: je Unterseite in der Reihenfolge von {@code pages}, erst ausgehend, dann eingehend.
     *
     * @param pages die Unterseiten, etwa {@code Holdings.descendants(...).pages()}
     */
    public List<SubpageReference> subpageReferences(AccessContext caller, PageSet pages) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(pages, "pages");
        if (links == null || pages.size() == 0) {
            return List.of();
        }
        List<PageKey> keys = new java.util.ArrayList<>();
        for (String sourceId : pages.sourceIds()) {
            for (String pageId : new java.util.TreeSet<>(pages.pagesOf(sourceId))) {
                keys.add(new PageKey(sourceId, pageId));
            }
        }
        List<SubpageReference> found = new java.util.ArrayList<>();
        for (Map.Entry<PageKey, PageLinks.Links> entry : links.links(caller, keys).entrySet()) {
            String sourceId = entry.getKey().sourceId();
            PageNode origin = cachedNode(entry.getKey());
            for (PageNode target : visible(caller, sourceId,
                    entry.getValue().outgoing().stream().map(this::cachedNode).toList())) {
                found.add(new SubpageReference(origin, true, target));
            }
            for (PageNode target : visible(caller, sourceId,
                    entry.getValue().incoming().stream().map(this::cachedNode).toList())) {
                found.add(new SubpageReference(origin, false, target));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Titel und Space von Seiten aus dem Cache, etwa für die Herkunft unter „Aus Unterseiten“ (Fahrplan P3); Seiten
     * ohne Leserecht fehlen, unbekannte bleiben ohne Titel.
     *
     * @return je sichtbarer Seite ihr Knoten, in der Reihenfolge der Eingabe
     */
    public Map<PageKey, PageNode> nodes(AccessContext caller, java.util.Collection<PageKey> keys) {
        Objects.requireNonNull(caller, "caller");
        Map<PageKey, PageNode> found = new java.util.LinkedHashMap<>();
        for (PageKey key : new java.util.LinkedHashSet<>(keys)) {
            PageNode node = withSpace(key.sourceId(), cachedNode(key));
            if (access.permits(caller, ResourceScope.page(key.sourceId(), node.partitionKey(), node.pageId()),
                    AccessKind.METADATA)) {
                found.put(key, node);
            }
        }
        return found;
    }

    /** Ein Knoten aus den Kopfdaten im Cache; Titel und Space fehlen, wenn der Cache die Seite nicht kennt. */
    private PageNode cachedNode(PageKey key) {
        if (cache != null) {
            try {
                Optional<PageHeader> header = cache.header(key);
                if (header.isEmpty()) {
                    header = cache.page(key).map(page -> page.header());
                }
                if (header.isPresent()) {
                    return new PageNode(key.pageId(), header.get().metadata().title(),
                            header.get().metadata().partitionKey(), null);
                }
            } catch (RuntimeException e) {
                // ohne Cache bleibt der Knoten ohne Titel
            }
        }
        return new PageNode(key.pageId(), null, null, null);
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

    /** Bietet die Treffer zum Katalogisieren an (ADR 0016); reiht nur ein und lässt die Suche nie scheitern. */
    private void catalogFindings(AccessContext caller, String sourceId, List<PageNode> found) {
        try {
            findings.offer(caller, sourceId, found.stream()
                    .map(node -> new FindingCatalog.Finding(node.pageId(), null)).toList());
        } catch (RuntimeException e) {
            // Nur Zähler und Quellen ins Protokoll; hier genügt, dass die Titelsuche weiterläuft.
        }
    }

    private static PageNode liveSpace(HarvestSource source, PageNode node) {
        try {
            return new PageNode(node.pageId(), node.title(), source.pageMetadata(node.pageId()).partitionKey(),
                    node.hasChildren(), node.kind());
        } catch (SourceAccessException e) {
            return null;
        }
    }

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }

    private List<PageNode> visible(AccessContext caller, String sourceId, List<PageNode> nodes) {
        return withoutExcluded(sourceId, permitted(caller, sourceId, nodes));
    }

    /** Ohne die Knoten unter einem Ausschluss; die Vorfahren kennt der Bestand oder das Durchblättern. */
    private List<PageNode> withoutExcluded(String sourceId, List<PageNode> nodes) {
        com.aresstack.spaceharvester.application.sync.Exclusions excluded = exclusions;
        if (nodes.isEmpty() || !excluded.any(sourceId)) {
            return nodes;
        }
        return nodes.stream().filter(node -> !excluded.excluded(sourceId, node.partitionKey(), node.pageId()))
                .toList();
    }

    /** Die Knoten, die der Aufrufer sehen darf, mit Space; ohne Rücksicht auf Ausschlüsse. */
    private List<PageNode> permitted(AccessContext caller, String sourceId, List<PageNode> nodes) {
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
                            node.hasChildren(), node.kind()))
                    .orElse(node);
        } catch (RuntimeException e) {
            return node;
        }
    }

    private static PageNode node(AccessContext caller, PageNode node) {
        if (caller.kind() == AccessContext.PrincipalKind.END_USER && Boolean.TRUE.equals(node.hasChildren())) {
            return new PageNode(node.pageId(), node.title(), node.partitionKey(), null, node.kind());
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
     * @param incoming eingehende Verweise oder {@code null}, wenn weder die Quelle noch das Verzeichnis sie kennt
     * @param coverage {@code null}, wenn {@code incoming} von der Quelle stammt (oder fehlt); sonst wie weit das
     *                 Verzeichnis der Verweise die Quelle kennt („aus N von M Seiten“)
     */
    public record References(String sourceId, String pageId, List<PageNode> outgoing, List<PageNode> incoming,
                             PageLinks.Coverage coverage) {

        public References {
            outgoing = List.copyOf(outgoing);
            incoming = incoming == null ? null : List.copyOf(incoming);
        }

        /** Rückrichtung von der Quelle selbst oder ohne. */
        public References(String sourceId, String pageId, List<PageNode> outgoing, List<PageNode> incoming) {
            this(sourceId, pageId, outgoing, incoming, null);
        }
    }
}
