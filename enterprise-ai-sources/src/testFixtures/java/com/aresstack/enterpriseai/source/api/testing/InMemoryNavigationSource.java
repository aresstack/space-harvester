package com.aresstack.enterpriseai.source.api.testing;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake source with a page hierarchy ({@link PageNavigationSource}, issue #97): spaces as roots, pages under a space or a
 * page, children in batches of {@code batchSize}, outgoing links per page, optionally incoming ones. Pages are addressed
 * by a local key; ids have the form {@code nav:<sourceId>/<key>}. Every call is logged in {@link #calls()}.
 */
public final class InMemoryNavigationSource implements KnowledgeSourcePort, PageNavigationSource {

    private final SourceAddress sourceId;
    private final int batchSize;
    private final Map<String, NavigationNode> nodes = new LinkedHashMap<String, NavigationNode>();
    private final Map<String, String> parents = new LinkedHashMap<String, String>();
    private final Map<String, List<String>> children = new LinkedHashMap<String, List<String>>();
    private final Map<String, List<String>> links = new LinkedHashMap<String, List<String>>();
    private final Map<String, Kind> failures = new LinkedHashMap<String, Kind>();
    private final List<String> calls = new ArrayList<String>();
    private boolean incoming;
    private boolean listsPartitions;

    public InMemoryNavigationSource(String sourceId, int batchSize) {
        this.sourceId = SourceAddress.of(sourceId);
        this.batchSize = batchSize;
    }

    /** Add a space (root) with key {@code space:<key>}. */
    public synchronized InMemoryNavigationSource space(String key, String title) {
        NavigationNode root = NavigationNode.root("space:" + key, title, "space:" + key, NavigationNode.Children.YES);
        nodes.put(root.key(), root);
        children.put(root.key(), new ArrayList<String>());
        return this;
    }

    /** Add page {@code key} under {@code parent} (a page key or {@code space:<key>}). */
    public synchronized InMemoryNavigationSource page(String parent, String key, String title) {
        NavigationNode page = NavigationNode.page(idOf(key), title, NavigationNode.Children.UNKNOWN);
        nodes.put(page.key(), page);
        String parentKey = nodes.containsKey("space:" + parent) ? "space:" + parent
                : parent.startsWith("space:") ? parent : idOf(parent).value();
        parents.put(page.key(), parentKey);
        children.put(page.key(), new ArrayList<String>());
        children.get(parentKey).add(page.key());
        return this;
    }

    /** Let page {@code from} link to the pages {@code to}. */
    public synchronized InMemoryNavigationSource link(String from, String... to) {
        List<String> targets = new ArrayList<String>();
        for (String key : to) {
            targets.add(idOf(key).value());
        }
        links.put(idOf(from).value(), targets);
        return this;
    }

    /** Report incoming links (all pages linking to a page) as complete. */
    public synchronized InMemoryNavigationSource withIncoming() {
        incoming = true;
        return this;
    }

    /** List the spaces as partitions ({@link #partitions()}) with start point {@code space:<key>}. */
    public synchronized InMemoryNavigationSource withPartitions() {
        listsPartitions = true;
        return this;
    }

    /** Let every call for page {@code key} fail with {@code kind}. */
    public synchronized InMemoryNavigationSource failWith(String key, Kind kind) {
        failures.put(idOf(key).value(), kind);
        return this;
    }

    public KnowledgeResourceId idOf(String key) {
        return KnowledgeResourceId.of("nav", sourceId.value() + "/" + key);
    }

    public synchronized List<String> calls() {
        return new ArrayList<String>(calls);
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) {
        return Collections.emptyList();
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        NavigationNode node = existing(resourceId);
        return KnowledgeDocument.of(KnowledgeResource.builder(resourceId, sourceId).title(node.title()).build(),
                "# " + node.title());
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
        return Collections.emptyList();
    }

    @Override
    public synchronized List<SourcePartition> partitions() {
        List<SourcePartition> partitions = new ArrayList<SourcePartition>();
        if (!listsPartitions) {
            return partitions;
        }
        calls.add("partitions");
        for (NavigationNode node : nodes.values()) {
            if (node.isRoot()) {
                partitions.add(new SourcePartition(node.key().substring("space:".length()), node.title(), null,
                        node.startPoint()));
            }
        }
        return partitions;
    }

    @Override
    public synchronized PagePosition position(KnowledgeResourceId page) throws KnowledgeSourceException {
        calls.add("position " + page.value());
        NavigationNode node = existing(page);
        List<NavigationNode> ancestors = new ArrayList<NavigationNode>();
        String parent = parents.get(node.key());
        while (parent != null && !parent.startsWith("space:")) {
            ancestors.add(0, withChildren(parent));
            parent = parents.get(parent);
        }
        return new PagePosition(nodes.get(parent), ancestors, withChildren(node.key()));
    }

    @Override
    public synchronized NavigationChildren children(NavigationNode parent, String cursor)
            throws KnowledgeSourceException {
        calls.add("children " + parent.key() + " " + cursor);
        if (!parent.isRoot()) {
            existing(parent.resource());
        }
        List<String> all = children.get(parent.key());
        if (all == null) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, parent.key());
        }
        int from = cursor == null || cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
        List<NavigationNode> batch = new ArrayList<NavigationNode>();
        for (int i = from; i < all.size() && batch.size() < batchSize; i++) {
            batch.add(withChildren(all.get(i)));
        }
        int next = from + batch.size();
        return new NavigationChildren(batch, next < all.size() ? String.valueOf(next) : "");
    }

    @Override
    public synchronized CrossReferences crossReferences(KnowledgeResourceId page) throws KnowledgeSourceException {
        calls.add("crossReferences " + page.value());
        existing(page);
        List<SourceLink> outgoing = new ArrayList<SourceLink>();
        for (String target : links.containsKey(page.value()) ? links.get(page.value())
                : Collections.<String>emptyList()) {
            outgoing.add(new SourceLink(KnowledgeResourceId.of(target), nodes.get(target).title()));
        }
        if (!incoming) {
            return CrossReferences.outgoingOnly(outgoing);
        }
        List<SourceLink> in = new ArrayList<SourceLink>();
        for (Map.Entry<String, List<String>> entry : links.entrySet()) {
            if (entry.getValue().contains(page.value())) {
                in.add(new SourceLink(KnowledgeResourceId.of(entry.getKey()), nodes.get(entry.getKey()).title()));
            }
        }
        return CrossReferences.of(outgoing, in);
    }

    private NavigationNode existing(KnowledgeResourceId id) throws KnowledgeSourceException {
        Kind failure = failures.get(id.value());
        if (failure != null) {
            throw new KnowledgeSourceException(failure, "failure for " + id.value());
        }
        NavigationNode node = nodes.get(id.value());
        if (node == null) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, id.value());
        }
        return node;
    }

    private NavigationNode withChildren(String key) {
        NavigationNode node = nodes.get(key);
        if (node.isRoot()) {
            return node;
        }
        return NavigationNode.page(node.resource(), node.title(), children.get(key).isEmpty()
                ? NavigationNode.Children.NO : NavigationNode.Children.YES);
    }
}
