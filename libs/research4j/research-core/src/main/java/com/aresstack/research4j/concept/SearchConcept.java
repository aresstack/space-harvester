package com.aresstack.research4j.concept;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The mind map (Konzept) of a research: a tree of nodes, each a direction to search in. It only steers the search of
 * Phase 3 and shows where more research is needed. It is never the structure of a book, never a knowledge model and
 * never a measure of acceptance; nothing outside Research4j sees it, and the basket it helps to fill carries no trace
 * of it. Immutable.
 */
public final class SearchConcept {

    /**
     * One node.
     *
     * @param id        stable id, unique in the concept
     * @param label     what the node stands for, as the user named it
     * @param parentId  the parent's id; empty for a root
     * @param baseQuery the node's deterministic base query; empty to use the label
     */
    public record Node(String id, String label, String parentId, String baseQuery) {
        public Node {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("a concept node needs an id");
            }
            id = id.trim();
            label = label == null ? "" : label.trim();
            if (label.isEmpty()) {
                throw new IllegalArgumentException("a concept node needs a label");
            }
            parentId = parentId == null ? "" : parentId.trim();
            baseQuery = baseQuery == null ? "" : baseQuery.trim();
        }

        public static Node root(String id, String label) {
            return new Node(id, label, "", "");
        }

        public static Node child(String id, String label, String parentId) {
            return new Node(id, label, parentId, "");
        }
    }

    private final List<Node> nodes;

    public SearchConcept(List<Node> nodes) {
        List<Node> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Node node : nodes == null ? List.<Node>of() : nodes) {
            if (node != null && ids.add(node.id())) {
                list.add(node);
            }
        }
        for (Node node : list) {
            if (!node.parentId().isEmpty() && !ids.contains(node.parentId())) {
                throw new IllegalArgumentException("unknown parent " + node.parentId() + " of node " + node.id());
            }
        }
        this.nodes = Collections.unmodifiableList(list);
    }

    /**
     * The concept of a research without a mind map: one node, the mission itself. The fence still bounds every
     * query and every source.
     */
    public static SearchConcept ofMission(String mission) {
        String label = mission == null ? "" : mission.replaceAll("\\s+", " ").trim();
        return new SearchConcept(List.of(Node.root("mission", label.isEmpty() ? "mission" : label)));
    }

    /** The nodes in the order the user gave them. */
    public List<Node> nodes() {
        return nodes;
    }

    public Node node(String id) {
        for (Node node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        throw new IllegalArgumentException("unknown concept node " + id);
    }

    /** The labels from the root down to the node. */
    public List<String> path(Node node) {
        Objects.requireNonNull(node, "node");
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Node at = node; at != null && seen.add(at.id()); at = at.parentId().isEmpty() ? null : node(at.parentId())) {
            path.add(0, at.label());
        }
        return Collections.unmodifiableList(path);
    }

    /** Sizes only. */
    @Override
    public String toString() {
        return "SearchConcept[" + nodes.size() + " nodes]";
    }
}
