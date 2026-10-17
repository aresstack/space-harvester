package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.tree.PageTree;
import java.util.Map;

/**
 * Der Baum eines Space aus genau einer Cache-Generation, nur aus sichtbaren Seiten.
 *
 * @param tree    ID-Baum
 * @param pages   Kopfdaten je {@code pageId} aller Knoten, für {@code include=metadata}
 */
public record SpaceTree(String sourceId, String spaceKey, CacheGeneration generation, PageTree tree,
                        Map<String, ListedPage> pages) {

    public SpaceTree {
        pages = Map.copyOf(pages);
    }
}
