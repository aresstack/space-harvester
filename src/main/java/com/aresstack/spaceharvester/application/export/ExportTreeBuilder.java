package com.aresstack.spaceharvester.application.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baut den ID-Baum eines Exports aus den geschriebenen Seiten. Eine Seite, deren Elternseite nicht im Export liegt,
 * wird Wurzel; ein Zyklus wird an der zuerst gelesenen Seite aufgebrochen. Beides steht als Warnung im Baum.
 *
 * <p>Bewusst klein gehalten; sobald der Tree-Builder aus Strang G (#27) gemergt ist, nutzt der Export ihn.
 */
final class ExportTreeBuilder {

    private final Map<String, String> parents = new LinkedHashMap<>();

    /** @return {@code false}, wenn die Seite schon hinzugefügt war */
    boolean add(String pageId, String parentPageId) {
        if (parents.containsKey(pageId)) {
            return false;
        }
        parents.put(pageId, parentPageId);
        return true;
    }

    ExportTree build() {
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> children = new HashMap<>();
        List<String> roots = new ArrayList<>();
        for (Map.Entry<String, String> page : parents.entrySet()) {
            String parent = page.getValue();
            if (parent == null) {
                roots.add(page.getKey());
            } else if (!parents.containsKey(parent)) {
                roots.add(page.getKey());
                warnings.add("Elternseite " + parent + " von Seite " + page.getKey() + " fehlt im Export");
            } else {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(page.getKey());
            }
        }
        Set<String> placed = new HashSet<>();
        List<ExportTree.Node> rootNodes = new ArrayList<>();
        for (String root : roots) {
            rootNodes.add(node(root, children, placed));
        }
        // Was jetzt noch fehlt, hängt nur an einem Zyklus.
        for (String pageId : parents.keySet()) {
            if (!placed.contains(pageId)) {
                warnings.add("Zyklus in der Elternbeziehung bei Seite " + pageId + "; als Wurzel exportiert");
                children.getOrDefault(parents.get(pageId), new ArrayList<>()).remove(pageId);
                rootNodes.add(node(pageId, children, placed));
            }
        }
        return new ExportTree(rootNodes, warnings);
    }

    private static ExportTree.Node node(String pageId, Map<String, List<String>> children, Set<String> placed) {
        placed.add(pageId);
        List<ExportTree.Node> nodes = new ArrayList<>();
        for (String child : children.getOrDefault(pageId, List.of())) {
            if (!placed.contains(child)) {
                nodes.add(node(child, children, placed));
            }
        }
        return new ExportTree.Node(pageId, nodes);
    }
}
