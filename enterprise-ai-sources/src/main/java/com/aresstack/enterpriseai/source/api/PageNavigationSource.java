package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

/**
 * Optional capability of a source: the page hierarchy around a page and its content cross references, for the
 * navigation view (compass) of the drawer (issue #97). Structural edges (parent, children) and content links stay apart:
 * the tree comes from {@link #position} and {@link #children}, the links from {@link #crossReferences}; neither uses
 * {@link KnowledgeSourcePort#discoverLinks}, which mixes both for indexing.
 *
 * <p>Callers check with {@code instanceof}. A source that knows no hierarchy for a resource answers {@link #position}
 * with {@code UNSUPPORTED}; it may still deliver cross references. All calls block and may hit the network.
 */
public interface PageNavigationSource {

    /**
     * Locate the page in its hierarchy: the root (Confluence: the space of the page), the ancestors from the top down
     * and the page itself.
     *
     * @throws KnowledgeSourceException {@code NOT_FOUND} if the page does not exist (or is not visible),
     *                                  {@code UNSUPPORTED} if the source has no hierarchy for this resource
     */
    PagePosition position(KnowledgeResourceId page) throws KnowledgeSourceException;

    /**
     * Return one batch of the direct children of {@code parent} in the order of the source; the source chooses the batch
     * size. Pass {@code ""} for the first batch and {@link NavigationChildren#nextCursor()} for the following ones until
     * it is empty: every child is reachable, there is no overall limit.
     *
     * @param parent a node this source delivered (root or page)
     * @param cursor {@code ""} or a cursor this source delivered for the same parent
     */
    NavigationChildren children(NavigationNode parent, String cursor) throws KnowledgeSourceException;

    /**
     * Return the references of the page to other pages of this source (outgoing, deduplicated, without anchors of the
     * page itself and without attachments) and, only if the source can determine them completely, the incoming ones.
     */
    CrossReferences crossReferences(KnowledgeResourceId page) throws KnowledgeSourceException;
}
