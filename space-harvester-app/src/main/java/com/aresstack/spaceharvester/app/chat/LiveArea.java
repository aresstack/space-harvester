package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Der gewählte Bereich über die gemeinsame Navigation aller Quellen ({@link PageNavigation}, dieselbe wie der Baum
 * links), für die Wurzeln, die der Katalog im Cache nicht kennt.
 *
 * <p>Bis 26.11.2026 kannte der Chat seinen Bereich nur aus dem Katalog ({@code Holdings.resolve}, ADR 0029). Ein Knoten,
 * den der Katalog nicht führt, ergab einen leeren Bereich: Ein SharePoint-Ordner etwa kommt nur über die Navigation in
 * den Baum (gruppenbasierte Quellen schreiben dort nichts in den Cache, ADR 0040 Punkt 3), ebenso jeder noch nie
 * abgeglichene Space einer anderen Quelle. Die Suche im Bereich meldete dem Modell dann „Es ist kein Bereich gewählt.“,
 * die Liste „keine Seiten“, und das Modell antwortete, ihm lägen keine Quellen vor (Angelo 26.11.2026). Für solche
 * Wurzeln gilt jetzt, was der Baum zeigt: die Kinder je Knoten über {@link Navigation#children} und die Zugehörigkeit
 * einer Seite über ihre Vorfahren ({@link Navigation#position}). Beides ist für jede Quelle mit Navigation gleich;
 * nichts hier kennt eine Quellenart.
 *
 * <p>Wurzeln, die der Katalog abdeckt, bleiben beim Katalog wie bisher; diese Klasse fragt für sie nichts.
 */
final class LiveArea {

    /** Die Navigation der App ({@link Workspace#children}, {@link Workspace#position}); für Tests austauschbar. */
    interface Navigation {
        PageNavigation.Children children(String sourceId, String spaceKey, String parentPageId, String cursor);

        PageNavigation.Position position(String sourceId, String pageId);
    }

    static Navigation of(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        return new Navigation() {
            @Override
            public PageNavigation.Children children(String sourceId, String spaceKey, String parentPageId,
                                                    String cursor) {
                return workspace.children(sourceId, spaceKey, parentPageId, cursor);
            }

            @Override
            public PageNavigation.Position position(String sourceId, String pageId) {
                return workspace.position(sourceId, pageId);
            }
        };
    }

    /** Ohne Wurzeln außerhalb des Katalogs. */
    static final LiveArea NONE = new LiveArea(null, List.of());

    private final Navigation navigation;
    private final List<Selection.Root> roots;
    /** Seiten, die sicher unter einer Wurzel liegen, mit ihrem Space (Partition). */
    private final Map<PageKey, String> inside = new ConcurrentHashMap<>();
    /** Seiten, die sicher nicht dazugehören (oder deren Lage sich nicht klären ließ). */
    private final Set<PageKey> outside = ConcurrentHashMap.newKeySet();
    /** Ordner und Gruppen: Sie haben keinen eigenen Text, nur Inhalt. */
    private final Set<PageKey> folders = ConcurrentHashMap.newKeySet();

    /**
     * @param roots nur die Wurzeln, die der Katalog nicht abdeckt ({@link #uncovered})
     */
    LiveArea(Navigation navigation, List<Selection.Root> roots) {
        this.roots = List.copyOf(roots);
        this.navigation = this.roots.isEmpty() ? null : Objects.requireNonNull(navigation, "navigation");
        for (Selection.Root root : this.roots) {
            if (root.pageId() != null) {
                inside.put(new PageKey(root.sourceId(), root.pageId()), root.spaceKey());
            }
        }
    }

    /**
     * Die Wurzeln der Auswahl, die der Katalog nicht abdeckt: eine Seite, die er nicht führt, ein Space oder eine
     * Quelle, von der er keine Seite kennt. Ohne aufgelösten Bereich ({@code scope} {@code null}) alle.
     */
    static List<Selection.Root> uncovered(PageSelection scope, List<Selection.Root> roots) {
        if (roots == null || roots.isEmpty()) {
            return List.of();
        }
        List<Selection.Root> uncovered = new ArrayList<>();
        for (Selection.Root root : roots) {
            if (scope == null) {
                uncovered.add(root);
            } else if (root.pageId() != null) {
                if (!scope.contains(root.sourceId(), root.pageId())) {
                    uncovered.add(root);
                }
            } else if (root.spaceKey() != null) {
                boolean known = false;
                for (SpaceSummary space : scope.spaces()) {
                    known |= space.sourceId().equals(root.sourceId()) && space.spaceKey().equals(root.spaceKey())
                            && space.counts().pages() > 0;
                }
                if (!known) {
                    uncovered.add(root);
                }
            } else if (scope.pagesOf(root.sourceId()).isEmpty()) {
                uncovered.add(root);
            }
        }
        return List.copyOf(uncovered);
    }

    boolean isEmpty() {
        return roots.isEmpty();
    }

    List<Selection.Root> roots() {
        return roots;
    }

    /**
     * Ob die Seite unter einer der Wurzeln liegt: die Wurzel selbst, was eine Liste schon unter ihr zeigte, sonst nach
     * ihren Vorfahren laut Quelle. Eine ganze Quelle als Wurzel nimmt jede ihrer Seiten. Lässt sich die Lage nicht
     * klären (Fehler der Quelle, kein Recht), gehört die Seite nicht dazu.
     */
    boolean contains(PageKey key) {
        if (key == null || roots.isEmpty()) {
            return false;
        }
        if (inside.containsKey(key)) {
            return true;
        }
        if (outside.contains(key)) {
            return false;
        }
        boolean sameSource = false;
        for (Selection.Root root : roots) {
            if (root.sourceId().equals(key.sourceId())) {
                sameSource = true;
                if (root.spaceKey() == null) {
                    return true;
                }
            }
        }
        if (!sameSource) {
            return false;
        }
        PageNavigation.Position position;
        try {
            position = navigation.position(key.sourceId(), key.pageId());
        } catch (RuntimeException unknown) {
            outside.add(key);
            return false;
        }
        for (Selection.Root root : roots) {
            if (!root.sourceId().equals(key.sourceId())) {
                continue;
            }
            boolean below = root.pageId() == null ? root.spaceKey().equals(position.spaceKey())
                    : position.ancestors().stream().anyMatch(node -> node.pageId().equals(root.pageId()));
            if (below) {
                inside.put(key, position.spaceKey() != null ? position.spaceKey() : root.spaceKey());
                if (position.page() != null && isFolder(position.page())) {
                    folders.add(key);
                }
                return true;
            }
        }
        outside.add(key);
        return false;
    }

    /** Der Space einer Seite, die {@link #contains} schon bestätigt hat; sonst {@code null}. */
    String spaceOf(PageKey key) {
        return key == null ? null : inside.get(key);
    }

    /** Ob die Seite ein Ordner (oder eine Gruppe) ist, soweit Liste oder Lage es gezeigt haben. */
    boolean isFolder(PageKey key) {
        return folders.contains(key);
    }

    /**
     * Ein Knoten der Liste.
     *
     * @param children {@code true}, wenn er laut Quelle Kinder hat, {@code false} ohne, {@code null} unbekannt
     */
    record Node(PageKey key, String title, boolean folder, Boolean children) {
    }

    /**
     * Die Kinder eines Knotens, ganz (allen Fortsetzungen der Quelle gefolgt, ADR 0040 Punkt 8); ohne {@code parent}
     * die obersten Knoten jeder Wurzel: die Kinder der gewählten Seite bzw. die Startseiten des gewählten Space. Eine
     * ganze Quelle als Wurzel hat hier keine Startseiten (sie liefert der Katalog oder die Suche). Jeder gelistete
     * Knoten gilt danach als im Bereich.
     *
     * @param parent ein Knoten, den {@link #contains} bestätigt, oder {@code null}
     * @throws RuntimeException wenn die Quelle die Liste ablehnt
     */
    List<Node> children(PageKey parent, BooleanSupplier cancelled) {
        List<Node> nodes = new ArrayList<>();
        if (parent != null) {
            if (!contains(parent)) {
                return List.of();
            }
            collect(parent.sourceId(), inside.get(parent), parent.pageId(), nodes, cancelled);
            return nodes;
        }
        for (Selection.Root root : roots) {
            if (root.spaceKey() != null) {
                collect(root.sourceId(), root.spaceKey(), root.pageId(), nodes, cancelled);
            }
        }
        return nodes;
    }

    private void collect(String sourceId, String spaceKey, String parentPageId, List<Node> into,
                         BooleanSupplier cancelled) {
        String cursor = null;
        Set<String> seen = new java.util.HashSet<>();
        do {
            PageNavigation.Children batch = navigation.children(sourceId, spaceKey, parentPageId, cursor);
            String partition = batch.spaceKey() != null ? batch.spaceKey() : spaceKey;
            for (PageNode page : batch.pages()) {
                if (!seen.add(page.pageId())) {
                    continue;
                }
                PageKey key = new PageKey(sourceId, page.pageId());
                String space = page.partitionKey() != null ? page.partitionKey() : partition;
                if (space != null) {
                    inside.put(key, space);
                    outside.remove(key);
                }
                boolean folder = isFolder(page);
                if (folder) {
                    folders.add(key);
                }
                into.add(new Node(key, page.title(), folder, folder ? Boolean.TRUE : page.hasChildren()));
            }
            String next = batch.nextCursor();
            cursor = next == null || next.isEmpty() || next.equals(cursor) ? null : next;
        } while (cursor != null && !cancelled.getAsBoolean());
    }

    private static boolean isFolder(PageNode page) {
        return page.kind() == PageNode.Kind.FOLDER || page.kind() == PageNode.Kind.GROUP;
    }
}
