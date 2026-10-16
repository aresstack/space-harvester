package com.aresstack.enterpriseai.source.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Where a page sits in the hierarchy of its source ({@link PageNavigationSource#position}); immutable. */
public final class PagePosition {

    private final NavigationNode root;
    private final List<NavigationNode> ancestors;
    private final NavigationNode page;

    /**
     * @param root      the root of the partition the page belongs to (Confluence: its space)
     * @param ancestors the pages between root and page, from the top down; empty for a top-level page
     * @param page      the page itself
     */
    public PagePosition(NavigationNode root, List<NavigationNode> ancestors, NavigationNode page) {
        if (root == null || page == null) {
            throw new IllegalArgumentException("root and page must not be null");
        }
        this.root = root;
        this.ancestors = ancestors == null ? Collections.<NavigationNode>emptyList()
                : Collections.unmodifiableList(new ArrayList<NavigationNode>(ancestors));
        this.page = page;
    }

    public NavigationNode root() {
        return root;
    }

    public List<NavigationNode> ancestors() {
        return ancestors;
    }

    public NavigationNode page() {
        return page;
    }

    /** Return root, ancestors and page from the top down. */
    public List<NavigationNode> path() {
        List<NavigationNode> path = new ArrayList<NavigationNode>(ancestors.size() + 2);
        path.add(root);
        path.addAll(ancestors);
        path.add(page);
        return Collections.unmodifiableList(path);
    }
}
