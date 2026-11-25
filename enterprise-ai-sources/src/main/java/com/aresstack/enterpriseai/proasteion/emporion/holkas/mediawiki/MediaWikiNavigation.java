package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Navigate inventory and category views without turning article links into a hierarchy. */
final class MediaWikiNavigation {
    private static final String ALL = "all";
    private static final String CATEGORIES = "categories";
    private static final String CATEGORY = "category:";
    private final String siteKey;
    private final MediaWikiInventory inventory;

    MediaWikiNavigation(String siteKey, MediaWikiInventory inventory) {
        this.siteKey = siteKey;
        this.inventory = inventory;
    }

    NavigationNode root(String title) {
        return NavigationNode.root("space:" + siteKey, title, "", NavigationNode.Children.YES);
    }

    NavigationChildren children(NavigationNode parent, String cursor, MediaWikiSiteInfo info)
            throws KnowledgeSourceException {
        if (parent.isRoot()) {
            requireFirstBatch(cursor);
            return new NavigationChildren(Arrays.asList(article(info.mainPage()), folder(ALL, "Alle Seiten"),
                    folder(CATEGORIES, "Kategorien")), "");
        }
        String group = groupOf(parent.resource());
        if (group == null) {
            requireFirstBatch(cursor);
            return new NavigationChildren(Collections.<NavigationNode>emptyList(), "");
        }
        if (ALL.equals(group)) {
            return articles(inventory.pages(info.contentNamespaces(), cursor, MediaWikiInventory.BATCH_SIZE));
        }
        if (CATEGORIES.equals(group)) {
            MediaWikiInventory.Batch batch = inventory.categories(cursor);
            List<NavigationNode> nodes = new ArrayList<NavigationNode>();
            for (String name : batch.titles) {
                nodes.add(folder(CATEGORY + name, WikiResourceIds.displayTitle(name)));
            }
            return new NavigationChildren(nodes, batch.next);
        }
        return articles(inventory.members(group.substring(CATEGORY.length()), info.contentNamespaces(), cursor));
    }

    NavigationNode allPages() {
        return folder(ALL, "Alle Seiten");
    }

    NavigationNode article(String title) {
        return NavigationNode.page(KnowledgeResourceId.of(WikiResourceIds.idFor(siteKey, title)),
                WikiResourceIds.displayTitle(title), NavigationNode.Children.NO);
    }

    String groupOf(KnowledgeResourceId id) {
        String prefix = "wiki-nav:" + siteKey + "/";
        if (id == null || !id.value().startsWith(prefix)) {
            return null;
        }
        String group = WikiResourceIds.decode(id.value().substring(prefix.length()));
        if (ALL.equals(group) || CATEGORIES.equals(group)
                || group != null && group.startsWith(CATEGORY) && group.length() > CATEGORY.length()) {
            return group;
        }
        return null;
    }

    String groupTitle(String group) {
        if (ALL.equals(group)) {
            return "Alle Seiten";
        }
        if (CATEGORIES.equals(group)) {
            return "Kategorien";
        }
        return WikiResourceIds.displayTitle(group.substring(CATEGORY.length()));
    }

    private NavigationNode folder(String group, String title) {
        return NavigationNode.folder(KnowledgeResourceId.of("wiki-nav:" + siteKey + "/" + WikiResourceIds.encode(group)),
                title, NavigationNode.Children.YES);
    }

    private NavigationChildren articles(MediaWikiInventory.Batch batch) {
        List<NavigationNode> nodes = new ArrayList<NavigationNode>();
        for (String title : batch.titles) {
            nodes.add(article(title));
        }
        return new NavigationChildren(nodes, batch.next);
    }

    private static void requireFirstBatch(String cursor) throws KnowledgeSourceException {
        if (cursor != null && !cursor.isEmpty()) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "Invalid MediaWiki cursor");
        }
    }
}
