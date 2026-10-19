package com.aresstack.spaceharvester.application.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bildet einen {@link PageTree} aus flachen Einträgen.
 *
 * <ul>
 *   <li>Seiten ohne Elternseite sind Wurzeln; mehrere Wurzeln sind normal.</li>
 *   <li>Eine Seite, deren Elternseite nicht in der Eingabe ist, wird mit ihrem Teilbaum zur Wurzel
 *       ({@link PageTree.Kind#MISSING_PARENT}). Ihr Teilbaum geht nicht verloren.</li>
 *   <li>Ein Elternzyklus (auch eine Seite, die sich selbst als Elternseite angibt) wird an der Seite mit der kleinsten
 *       {@code pageId} des Zyklus aufgebrochen ({@link PageTree.Kind#CYCLE}).</li>
 * </ul>
 *
 * <p>Geschwister stehen wie in Confluence ({@code /child/page}, so liest sie der Enterprise AI Client): erst nach
 * {@link TreeEntry#position()}, Seiten ohne Position danach nach Titel, bei Gleichstand nach Seiten-ID. Ohne Position
 * und Titel bleibt es bei der Seiten-ID.
 *
 * <p>Iterativ statt rekursiv, damit auch sehr tiefe Ketten keinen Stack-Überlauf auslösen. Das Ergebnis hängt nur von
 * der Menge der Einträge ab, nicht von ihrer Reihenfolge.
 */
public final class PageTreeBuilder {

    /** Titel ohne Rücksicht auf Groß- und Kleinschreibung, bei Gleichheit exakt. */
    private static final Comparator<String> TITLES = String.CASE_INSENSITIVE_ORDER.thenComparing(
            Comparator.naturalOrder());

    private PageTreeBuilder() {
    }

    /**
     * @throws IllegalArgumentException wenn eine {@code pageId} mehrfach vorkommt
     */
    public static PageTree build(Collection<TreeEntry> entries) {
        Map<String, String> parents = new HashMap<>();
        Map<String, TreeEntry> byId = new HashMap<>();
        for (TreeEntry entry : entries) {
            if (parents.containsKey(entry.pageId())) {
                throw new IllegalArgumentException("pageId mehrfach im Baum: " + entry.pageId());
            }
            parents.put(entry.pageId(), entry.parentPageId());
            byId.put(entry.pageId(), entry);
        }

        Map<String, List<String>> children = new HashMap<>();
        TreeMap<String, PageTree.Kind> roots = new TreeMap<>();
        for (Map.Entry<String, String> entry : parents.entrySet()) {
            String pageId = entry.getKey();
            String parent = entry.getValue();
            if (parent == null) {
                roots.put(pageId, null);
            } else if (!parents.containsKey(parent)) {
                roots.put(pageId, PageTree.Kind.MISSING_PARENT);
            } else if (parent.equals(pageId)) {
                roots.put(pageId, PageTree.Kind.CYCLE);
            } else {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(pageId);
            }
        }
        Comparator<String> siblings = Comparator.comparing((String pageId) -> byId.get(pageId).position(),
                        Comparator.nullsLast(Comparator.<Long>naturalOrder()))
                .thenComparing(pageId -> byId.get(pageId).title(), Comparator.nullsLast(TITLES))
                .thenComparing(Comparator.naturalOrder());
        children.values().forEach(list -> list.sort(siblings));

        Set<String> visited = new HashSet<>();
        roots.keySet().forEach(root -> visit(root, children, visited));

        // Was jetzt noch fehlt, hängt an einem Zyklus. Je Zyklus die kleinste pageId ablösen und erneut besuchen.
        List<String> unvisited = new ArrayList<>();
        for (String pageId : parents.keySet()) {
            if (!visited.contains(pageId)) {
                unvisited.add(pageId);
            }
        }
        Collections.sort(unvisited);
        for (String start : unvisited) {
            if (visited.contains(start)) {
                continue;
            }
            String cut = smallestInCycle(start, parents);
            children.get(parents.get(cut)).remove(cut);
            roots.put(cut, PageTree.Kind.CYCLE);
            visit(cut, children, visited);
        }

        List<PageTree.Anomaly> anomalies = new ArrayList<>();
        roots.forEach((pageId, kind) -> {
            if (kind != null) {
                anomalies.add(new PageTree.Anomaly(pageId, kind));
            }
        });
        int[] depth = {0};
        List<PageTree.Node> rootNodes = roots.keySet().stream()
                .map(root -> assemble(root, children, depth))
                .toList();
        return new PageTree(rootNodes, anomalies, parents.size(), depth[0]);
    }

    private static void visit(String root, Map<String, List<String>> children, Set<String> visited) {
        Deque<String> open = new ArrayDeque<>();
        open.push(root);
        while (!open.isEmpty()) {
            String pageId = open.pop();
            if (visited.add(pageId)) {
                children.getOrDefault(pageId, List.of()).forEach(open::push);
            }
        }
    }

    /** Folgt den Elternverweisen bis in den Zyklus und liefert dessen kleinste pageId. */
    private static String smallestInCycle(String start, Map<String, String> parents) {
        Set<String> seen = new HashSet<>();
        String current = start;
        while (seen.add(current)) {
            current = parents.get(current);
        }
        // current liegt jetzt sicher im Zyklus.
        String smallest = current;
        for (String next = parents.get(current); !next.equals(current); next = parents.get(next)) {
            if (next.compareTo(smallest) < 0) {
                smallest = next;
            }
        }
        return smallest;
    }

    /** Baut den Teilbaum von unten nach oben, ohne Rekursion. */
    private static PageTree.Node assemble(String root, Map<String, List<String>> children, int[] maxDepth) {
        List<String> order = new ArrayList<>();
        Map<String, Integer> depths = new HashMap<>();
        Deque<String> open = new ArrayDeque<>();
        open.push(root);
        depths.put(root, 1);
        while (!open.isEmpty()) {
            String pageId = open.pop();
            order.add(pageId);
            int depth = depths.get(pageId);
            maxDepth[0] = Math.max(maxDepth[0], depth);
            for (String child : children.getOrDefault(pageId, List.of())) {
                depths.put(child, depth + 1);
                open.push(child);
            }
        }
        Map<String, PageTree.Node> nodes = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String pageId = order.get(i);
            List<PageTree.Node> kids = children.getOrDefault(pageId, List.of()).stream().map(nodes::remove).toList();
            nodes.put(pageId, new PageTree.Node(pageId, kids));
        }
        return nodes.get(root);
    }
}
