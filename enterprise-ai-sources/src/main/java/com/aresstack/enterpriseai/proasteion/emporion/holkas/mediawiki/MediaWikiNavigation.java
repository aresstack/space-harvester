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
            return new NavigationChildren(Arrays.asList(article(info.mainPage(), info), folder(ALL, "Alle Seiten"),
                    folder(CATEGORIES, "Kategorien")), "");
        }
        String group = groupOf(parent.resource());
        if (group == null) {
            String title = WikiResourceIds.titleOf(siteKey, parent.resource().value());
            Integer namespace = title == null ? null : info.subpageNamespace(title);
            if (namespace == null) {
                requireFirstBatch(cursor);
                return new NavigationChildren(Collections.<NavigationNode>emptyList(), "");
            }
            return articles(inventory.subpages(title, namespace, cursor), info);
        }
        if (ALL.equals(group)) {
            return articles(inventory.pages(info.contentNamespaces(), cursor, MediaWikiInventory.BATCH_SIZE), info);
        }
        if (CATEGORIES.equals(group)) {
            MediaWikiInventory.Batch batch = inventory.categories(cursor);
            List<NavigationNode> nodes = new ArrayList<NavigationNode>();
            for (String name : batch.titles) {
                String normalized = WikiResourceIds.displayTitle(name);
                nodes.add(folder(CATEGORY + normalized, normalized));
            }
            return new NavigationChildren(nodes, batch.next);
        }
        List<String> path = Arrays.asList(group.substring(CATEGORY.length()).split("\\n", -1));
        String current = path.get(path.size() - 1);
        MediaWikiInventory.Batch batch = inventory.members(current, info.contentNamespaces(), cursor);
        List<NavigationNode> nodes = new ArrayList<NavigationNode>();
        for (String title : batch.titles) {
            String prefix = info.categoryPrefix() + ":";
            if (title.regionMatches(true, 0, prefix, 0, prefix.length())
                    || title.regionMatches(true, 0, "Category:", 0, "Category:".length())) {
                String name = WikiResourceIds.displayTitle(title.substring(title.indexOf(':') + 1));
                if (!path.contains(name)) {
                    nodes.add(folder(group + "\n" + name, name));
                }
            } else {
                nodes.add(article(title, info));
            }
        }
        return new NavigationChildren(nodes, batch.next);
    }

    NavigationNode allPages() {
        return folder(ALL, "Alle Seiten");
    }

    NavigationNode article(String title, MediaWikiSiteInfo info) {
        return NavigationNode.page(KnowledgeResourceId.of(WikiResourceIds.idFor(siteKey, title)),
                WikiResourceIds.displayTitle(title), info.subpageNamespace(title) == null
                        ? NavigationNode.Children.NO : NavigationNode.Children.UNKNOWN);
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
        return WikiResourceIds.displayTitle(group.substring(Math.max(CATEGORY.length(), group.lastIndexOf('\n') + 1)));
    }

    private NavigationNode folder(String group, String title) {
        return NavigationNode.folder(KnowledgeResourceId.of("wiki-nav:" + siteKey + "/" + WikiResourceIds.encode(group)),
                title, NavigationNode.Children.YES);
    }

    private NavigationChildren articles(MediaWikiInventory.Batch batch, MediaWikiSiteInfo info) {
        List<NavigationNode> nodes = new ArrayList<NavigationNode>();
        for (String title : batch.titles) {
            nodes.add(article(title, info));
        }
        return new NavigationChildren(nodes, batch.next);
    }

    private static void requireFirstBatch(String cursor) throws KnowledgeSourceException {
        if (cursor != null && !cursor.isEmpty()) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "Invalid MediaWiki cursor");
        }
    }
}
