package com.aresstack.writing4j.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3: the complete merge tree of one {@link TopicClusterer} run (average linkage, cosine distance), from the
 * single passages up to one root. The flat topics of the clusterer are one cut of this tree (at its merge cutoff);
 * other cuts give the near-duplicate groups below a topic, the sub-topics inside it and the topic groups above
 * it. Average linkage is reducible, so the merge heights grow monotonically along every path to the root and
 * every cut is a proper partition whose groups nest inside the groups of any higher cut.
 * <p>
 * Immutable. Leaves are the passages in the clusterer's input order; merges are ordered by height, ties by the
 * order the clusterer made them.
 */
public final class TopicDendrogram {

    /** One merge: two groups (by their member passage indexes) joined at a cosine distance. */
    public static final class Merge {
        private final int left;
        private final int right;
        private final double height;

        Merge(int left, int right, double height) {
            this.left = left;
            this.right = right;
            this.height = height;
        }

        /** A member index of the first group (the index the clusterer kept). */
        public int getLeft() {
            return left;
        }

        /** A member index of the second group. */
        public int getRight() {
            return right;
        }

        /** The average-linkage cosine distance between the two groups. */
        public double getHeight() {
            return height;
        }
    }

    private final List<String> passageIds;
    private final Map<String, Integer> indexOf;
    private final List<Merge> merges;
    private final double topicCutoff;

    TopicDendrogram(List<String> passageIds, List<Merge> merges, double topicCutoff) {
        this.passageIds = Collections.unmodifiableList(new ArrayList<String>(passageIds));
        Map<String, Integer> index = new HashMap<String, Integer>();
        for (int i = 0; i < passageIds.size(); i++) {
            index.put(passageIds.get(i), i);
        }
        this.indexOf = Collections.unmodifiableMap(index);
        List<Merge> sorted = new ArrayList<Merge>(merges);
        // Stable: equal heights keep the clusterer's order.
        Collections.sort(sorted, new Comparator<Merge>() {
            public int compare(Merge a, Merge b) {
                return Double.compare(a.height, b.height);
            }
        });
        this.merges = Collections.unmodifiableList(sorted);
        this.topicCutoff = topicCutoff;
    }

    /** The passages (the leaves) in the clusterer's input order. */
    public List<String> getPassageIds() {
        return passageIds;
    }

    /** All merges by height; {@code passages - 1} of them for a non-empty corpus. */
    public List<Merge> getMerges() {
        return merges;
    }

    /** The cutoff the clusterer's flat topics were cut at. */
    public double getTopicCutoff() {
        return topicCutoff;
    }

    public boolean contains(String passageId) {
        return indexOf.containsKey(passageId);
    }

    /**
     * The partition after every merge up to {@code distance} (inclusive): groups of passage ids, members in input
     * order, groups ordered by their first member.
     */
    public List<List<String>> cut(double distance) {
        UnionFind groups = new UnionFind(passageIds.size());
        for (Merge merge : merges) {
            if (merge.height > distance) {
                break;
            }
            groups.union(merge.left, merge.right);
        }
        return groups.partition(passageIds);
    }

    /** Decides whether a group of a cut falls apart into the two groups it was merged from. */
    public interface SplitRule {
        /** No group is split: the plain cut. */
        SplitRule NONE = new SplitRule() {
            public boolean split(TopicDendrogram tree, List<String> members, double height) {
                return false;
            }
        };

        /**
         * @param tree    this tree (for further cuts, e.g. the near duplicates)
         * @param members the group's passages in input order
         * @param height  the distance its two parts were joined at
         */
        boolean split(TopicDendrogram tree, List<String> members, double height);
    }

    /**
     * The cut at {@code distance}, and then every group the rule asks to split replaced by the two groups it was
     * merged from, again for those, until the rule keeps every group or it is a single passage. Splitting walks down
     * the merge tree only, so every group is still a node of this tree and lies inside one group of the plain cut.
     * With {@link SplitRule#NONE} this is {@link #cut(double)}. Groups ordered by their first member, members in
     * input order.
     */
    public List<List<String>> cut(double distance, SplitRule rule) {
        int n = passageIds.size();
        if (n == 0 || rule == null || rule == SplitRule.NONE) {
            return cut(distance);
        }
        int nodes = n + merges.size();
        int[] left = new int[nodes];
        int[] right = new int[nodes];
        int[] first = new int[nodes];
        double[] height = new double[nodes];
        boolean[] hasParent = new boolean[nodes];
        Arrays.fill(left, -1);
        Arrays.fill(right, -1);
        for (int i = 0; i < n; i++) {
            first[i] = i;
        }
        UnionFind groups = new UnionFind(n);
        int[] nodeOfRoot = new int[n];
        for (int i = 0; i < n; i++) {
            nodeOfRoot[i] = i;
        }
        int next = n;
        for (Merge merge : merges) {
            int a = groups.find(merge.left);
            int b = groups.find(merge.right);
            if (a == b) {
                continue;
            }
            int node = next++;
            left[node] = nodeOfRoot[a];
            right[node] = nodeOfRoot[b];
            hasParent[left[node]] = true;
            hasParent[right[node]] = true;
            first[node] = Math.min(first[left[node]], first[right[node]]);
            height[node] = merge.height;
            nodeOfRoot[groups.union(a, b)] = node;
        }
        // The plain cut, top down: a node joined above the distance falls apart into its two parts.
        List<Integer> open = new ArrayList<Integer>();
        for (int node = 0; node < next; node++) {
            if (!hasParent[node]) {
                open.add(node);
            }
        }
        List<Integer> cut = new ArrayList<Integer>();
        while (!open.isEmpty()) {
            int node = open.remove(open.size() - 1);
            if (left[node] >= 0 && height[node] > distance) {
                open.add(left[node]);
                open.add(right[node]);
            } else {
                cut.add(node);
            }
        }
        // Then the rule: a group it splits falls apart into its two parts, which are judged again.
        final int[] firstOf = first;
        List<Integer> result = new ArrayList<Integer>();
        while (!cut.isEmpty()) {
            int node = cut.remove(cut.size() - 1);
            if (left[node] >= 0 && rule.split(this, membersOf(node, left, right), height[node])) {
                cut.add(left[node]);
                cut.add(right[node]);
            } else {
                result.add(node);
            }
        }
        Collections.sort(result, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return Integer.compare(firstOf[a], firstOf[b]);
            }
        });
        List<List<String>> partition = new ArrayList<List<String>>();
        for (Integer node : result) {
            partition.add(membersOf(node, left, right));
        }
        return partition;
    }

    /** The passages below one node of the tree, in input order. */
    private List<String> membersOf(int node, int[] left, int[] right) {
        List<Integer> leaves = new ArrayList<Integer>();
        List<Integer> stack = new ArrayList<Integer>();
        stack.add(node);
        while (!stack.isEmpty()) {
            int current = stack.remove(stack.size() - 1);
            if (left[current] < 0) {
                leaves.add(current);
            } else {
                stack.add(left[current]);
                stack.add(right[current]);
            }
        }
        Collections.sort(leaves);
        List<String> members = new ArrayList<String>();
        for (Integer leaf : leaves) {
            members.add(passageIds.get(leaf));
        }
        return members;
    }

    /**
     * The cut at {@code distance} restricted to {@code members}: the groups that lie inside {@code members}, for
     * a member set that is itself a union of groups of a higher cut (a topic and its sub-topics). Members the tree
     * does not know are ignored.
     */
    public List<List<String>> cutWithin(Collection<String> members, double distance) {
        Set<String> inside = new HashSet<String>(members);
        List<List<String>> result = new ArrayList<List<String>>();
        for (List<String> group : cut(distance)) {
            List<String> kept = new ArrayList<String>();
            for (String id : group) {
                if (inside.contains(id)) {
                    kept.add(id);
                }
            }
            if (!kept.isEmpty()) {
                result.add(kept);
            }
        }
        return result;
    }

    /**
     * The cophenetic distance between every pair of the given disjoint groups: the height of the merge that first
     * joins them. Pairs that never join (an empty group) are absent. Keys are {@code "i:j"} with {@code i < j}.
     */
    public Map<String, Double> joinHeights(List<? extends Collection<String>> groups) {
        int n = passageIds.size();
        int[] groupOf = new int[n];
        Arrays.fill(groupOf, -1);
        for (int g = 0; g < groups.size(); g++) {
            for (String id : groups.get(g)) {
                Integer index = indexOf.get(id);
                if (index != null) {
                    groupOf[index] = g;
                }
            }
        }
        UnionFind tree = new UnionFind(n);
        Map<Integer, Set<Integer>> groupsOfRoot = new HashMap<Integer, Set<Integer>>();
        for (int i = 0; i < n; i++) {
            if (groupOf[i] >= 0) {
                Set<Integer> set = groupsOfRoot.get(i);
                if (set == null) {
                    set = new HashSet<Integer>();
                    groupsOfRoot.put(i, set);
                }
                set.add(groupOf[i]);
            }
        }
        Map<String, Double> heights = new HashMap<String, Double>();
        for (Merge merge : merges) {
            int a = tree.find(merge.left);
            int b = tree.find(merge.right);
            if (a == b) {
                continue;
            }
            Set<Integer> left = groupsOfRoot.remove(a);
            Set<Integer> right = groupsOfRoot.remove(b);
            if (left != null && right != null) {
                for (Integer x : left) {
                    for (Integer y : right) {
                        if (!x.equals(y)) {
                            String key = Math.min(x, y) + ":" + Math.max(x, y);
                            if (!heights.containsKey(key)) {
                                heights.put(key, merge.height);
                            }
                        }
                    }
                }
            }
            int root = tree.union(a, b);
            Set<Integer> joined = new HashSet<Integer>();
            if (left != null) {
                joined.addAll(left);
            }
            if (right != null) {
                joined.addAll(right);
            }
            if (!joined.isEmpty()) {
                groupsOfRoot.put(root, joined);
            }
        }
        return heights;
    }

    /** Union-find over passage indexes; the root of a set is its lowest index. */
    private static final class UnionFind {
        private final int[] parent;

        UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
        }

        int find(int x) {
            int root = x;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[x] != root) {
                int next = parent[x];
                parent[x] = root;
                x = next;
            }
            return root;
        }

        int union(int a, int b) {
            int x = find(a);
            int y = find(b);
            int root = Math.min(x, y);
            parent[Math.max(x, y)] = root;
            return root;
        }

        List<List<String>> partition(List<String> ids) {
            Map<Integer, List<String>> byRoot = new java.util.TreeMap<Integer, List<String>>();
            for (int i = 0; i < ids.size(); i++) {
                int root = find(i);
                List<String> group = byRoot.get(root);
                if (group == null) {
                    group = new ArrayList<String>();
                    byRoot.put(root, group);
                }
                group.add(ids.get(i));
            }
            // The root is the lowest index, so TreeMap order is "ordered by first member".
            return new ArrayList<List<String>>(byRoot.values());
        }
    }
}
