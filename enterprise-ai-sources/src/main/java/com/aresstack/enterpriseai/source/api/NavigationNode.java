package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/**
 * A node of the page hierarchy of a source ({@link PageNavigationSource}): the root of a partition (Confluence: a space)
 * or a page. {@link #key()} is unique within the source and stable; the source reads it back in
 * {@link PageNavigationSource#children}. A page carries its {@link #resource()}; a root carries the {@link #startPoint()}
 * under which {@link KnowledgeSourcePort#discover} lists its resources (as {@link SourcePartition#startPoint()}).
 * Immutable.
 */
public final class NavigationNode {

    /** Whether the node has children, as far as the source knows without loading them. */
    public enum Children {
        YES,
        NO,
        UNKNOWN
    }

    private final String key;
    private final String title;
    private final KnowledgeResourceId resource;
    private final String startPoint;
    private final Children children;

    private NavigationNode(String key, String title, KnowledgeResourceId resource, String startPoint,
                           Children children) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        this.key = key.trim();
        this.title = title == null || title.trim().isEmpty() ? this.key : title.trim();
        this.resource = resource;
        this.startPoint = startPoint == null ? "" : startPoint.trim();
        this.children = children == null ? Children.UNKNOWN : children;
    }

    /** Create the root of a partition, e.g. {@code root("space:DEV", "Development", "space:DEV", YES)}. */
    public static NavigationNode root(String key, String title, String startPoint, Children children) {
        return new NavigationNode(key, title, null, startPoint, children);
    }

    /** Create a page node; its key is the resource id. */
    public static NavigationNode page(KnowledgeResourceId resource, String title, Children children) {
        if (resource == null) {
            throw new IllegalArgumentException("resource must not be null");
        }
        return new NavigationNode(resource.value(), title, resource, "", children);
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    /** Return the page, {@code null} for a root. */
    public KnowledgeResourceId resource() {
        return resource;
    }

    public boolean isRoot() {
        return resource == null;
    }

    /** Return the start point of a root; empty for pages or if the source has none. */
    public String startPoint() {
        return startPoint;
    }

    public Children children() {
        return children;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof NavigationNode && key.equals(((NavigationNode) other).key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "NavigationNode[" + key + ", " + title + "]";
    }
}
