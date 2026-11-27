package com.aresstack.writing4j.cluster;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;

import com.aresstack.writing4j.memory.IdSequence;
import com.aresstack.writing4j.memory.Lifecycle;
import com.aresstack.writing4j.memory.Passage;
import com.aresstack.writing4j.memory.TopicProposal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hierarchical AGGLOMERATIVE clustering over passage vectors with cosine distance and average linkage:
 * no fixed topic count, small isolated topics stay visible, and the merge tree naturally maps to chapter
 * hierarchies later. Deterministic: ties resolve by insertion order. The result are {@link TopicProposal}s
 * — titles/summaries come from representative passages (AI may rephrase them later, membership never
 * depends on that phrasing). Ordering: clusters by their first passage in input order, members in input order.
 */
public final class TopicClusterer {

    /** Passages joined at or below this cosine distance form one topic (the default of the topic analysis setting). */
    public static final double DEFAULT_TOPIC_DISTANCE = 0.55;
    /** Representative passages per topic. */
    public static final int DEFAULT_REPRESENTATIVES = 2;

    private final IdSequence ids;
    private final double mergeDistanceCutoff;
    private final int representativesPerTopic;

    public TopicClusterer(IdSequence ids) {
        this(ids, DEFAULT_TOPIC_DISTANCE, DEFAULT_REPRESENTATIVES);
    }

    public TopicClusterer(IdSequence ids, double mergeDistanceCutoff, int representativesPerTopic) {
        this.ids = ids;
        this.mergeDistanceCutoff = mergeDistanceCutoff;
        this.representativesPerTopic = representativesPerTopic;
    }

    public List<TopicProposal> cluster(List<Passage> passages,
                                       Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        return cluster(passages, vectors, ClusteringMonitor.NONE);
    }

    /**
     * PR 20: average linkage over a cached condensed distance matrix of the normalised vectors, updated with
     * Lance-Williams and walked with a nearest-neighbour chain. Average linkage is reducible, so the chain
     * makes the same merges as the former scan of all pairs per merge, in O(n²) time and memory instead of
     * cubic time. A cluster whose nearest neighbour lies beyond the cutoff can never merge again
     * (reducibility: a merged cluster is never closer than the nearer of its parts) and is retired. Ties
     * break by the lower index.
     */
    public List<TopicProposal> cluster(List<Passage> passages,
                                       Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                       ClusteringMonitor monitor) {
        ClusteringMonitor m = monitor == null ? ClusteringMonitor.NONE : monitor;
        float[][] unit = unitVectors(passages, vectors);
        return proposals(agglomerate(unit, m, null), passages, vectors);
    }

    /** Phase 3: the flat topics of {@link #cluster} together with the complete merge tree they were cut from. */
    public static final class Tree {
        private final List<TopicProposal> topics;
        private final TopicDendrogram dendrogram;

        Tree(List<TopicProposal> topics, TopicDendrogram dendrogram) {
            this.topics = java.util.Collections.unmodifiableList(new ArrayList<TopicProposal>(topics));
            this.dendrogram = dendrogram;
        }

        /** The flat topics at the merge cutoff, ordered and built exactly as {@link #cluster} builds them. */
        public List<TopicProposal> getTopics() {
            return topics;
        }

        public TopicDendrogram getDendrogram() {
            return dendrogram;
        }
    }

    /**
     * Phase 3: one run that keeps merging past the cutoff up to a single root, so the whole merge tree is
     * available (sub-topics below a topic, topic groups above it). The flat topics are the tree's cut at the
     * cutoff: average linkage is reducible, so a group whose nearest neighbour lies beyond the cutoff takes part
     * in no merge at or below it, and the cut equals what {@link #cluster} returns (parity-tested). Same cost
     * class as {@link #cluster}: O(n²) time and memory.
     */
    public Tree clusterTree(List<Passage> passages, Map<String, EmbeddingPort.EmbeddingVector> vectors,
                            ClusteringMonitor monitor) {
        return clusterTree(passages, vectors, monitor, TopicDendrogram.SplitRule.NONE);
    }

    /**
     * The same run with a split rule over the cut ({@link TopicDendrogram#cut(double, TopicDendrogram.SplitRule)}):
     * the topic analysis splits a topic that spans independent concept areas along the tree.
     */
    public Tree clusterTree(List<Passage> passages, Map<String, EmbeddingPort.EmbeddingVector> vectors,
                            ClusteringMonitor monitor, TopicDendrogram.SplitRule rule) {
        ClusteringMonitor m = monitor == null ? ClusteringMonitor.NONE : monitor;
        float[][] unit = unitVectors(passages, vectors);
        List<TopicDendrogram.Merge> merges = new ArrayList<TopicDendrogram.Merge>();
        agglomerate(unit, m, merges);
        List<String> ids = new ArrayList<String>();
        for (Passage passage : passages) {
            ids.add(passage.getPassageId());
        }
        TopicDendrogram dendrogram = new TopicDendrogram(ids, merges, mergeDistanceCutoff);
        Map<String, Integer> index = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < ids.size(); i++) {
            index.put(ids.get(i), i);
        }
        List<List<Integer>> members = new ArrayList<List<Integer>>();
        for (List<String> group : dendrogram.cut(mergeDistanceCutoff, rule)) {
            List<Integer> indexes = new ArrayList<Integer>();
            for (String id : group) {
                indexes.add(index.get(id));
            }
            members.add(indexes);
        }
        return new Tree(proposals(members, passages, vectors), dendrogram);
    }

    private float[][] unitVectors(List<Passage> passages, Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        int n = passages.size();
        float[][] unit = new float[n][];
        String fingerprint = null;
        int dimension = -1;
        for (int i = 0; i < n; i++) {
            EmbeddingPort.EmbeddingVector vector = vectors.get(passages.get(i).getPassageId());
            if (vector == null) {
                throw new IllegalArgumentException("no vector for passage " + passages.get(i).getPassageId());
            }
            if (fingerprint == null) {
                fingerprint = vector.getModelFingerprint();
                dimension = vector.getDimension();
            } else if (!fingerprint.equals(vector.getModelFingerprint()) || dimension != vector.getDimension()) {
                throw new IllegalArgumentException("vectors of different embedding models/dimensions must "
                        + "never be compared: " + fingerprint + "/" + dimension + " vs "
                        + vector.getModelFingerprint() + "/" + vector.getDimension());
            }
            unit[i] = normalised(vector.getValues());
        }
        return unit;
    }

    private List<TopicProposal> proposals(List<List<Integer>> members, List<Passage> passages,
                                          Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (List<Integer> cluster : members) {
            List<Passage> clusterPassages = new ArrayList<Passage>();
            for (Integer index : cluster) {
                clusterPassages.add(passages.get(index));
            }
            proposals.add(toProposal(clusterPassages, vectors));
        }
        return proposals;
    }

    /**
     * Clusters as lists of input indexes, members ascending, clusters ordered by their lowest member. With
     * {@code tree} non-null no cluster is retired at the cutoff: the run merges up to one root and records every
     * merge; the returned clusters are then the single root and the caller cuts the tree itself.
     */
    private List<List<Integer>> agglomerate(float[][] unit, ClusteringMonitor monitor,
                                            List<TopicDendrogram.Merge> tree) {
        final int n = unit.length;
        List<List<Integer>> result = new ArrayList<List<Integer>>();
        if (n == 0) {
            return result;
        }
        float[] distance = new float[(int) Math.min(Integer.MAX_VALUE - 8L, (long) n * (n - 1) / 2)];
        if ((long) n * (n - 1) / 2 > distance.length) {
            throw new IllegalArgumentException("corpus too large for one similarity matrix: " + n);
        }
        long total = (long) n + n;
        for (int i = 0; i < n; i++) {
            if (monitor.isCancelled()) {
                throw new ClusteringMonitor.ClusteringCancelledException();
            }
            float[] x = unit[i];
            int base = rowBase(i, n);
            for (int j = i + 1; j < n; j++) {
                float[] y = unit[j];
                double dot = 0;
                for (int k = 0; k < x.length; k++) {
                    dot += x[k] * y[k];
                }
                distance[base + j] = (float) (1.0 - dot);
            }
            monitor.progress("Comparing passages", i + 1, total);
        }
        int[] size = new int[n];
        boolean[] active = new boolean[n];
        List<List<Integer>> clusterMembers = new ArrayList<List<Integer>>(n);
        for (int i = 0; i < n; i++) {
            size[i] = 1;
            active[i] = true;
            List<Integer> single = new ArrayList<Integer>();
            single.add(i);
            clusterMembers.add(single);
        }
        int activeCount = n;
        int[] chain = new int[n];
        int chainLength = 0;
        long steps = 0;
        while (activeCount > 1) {
            if (monitor.isCancelled()) {
                throw new ClusteringMonitor.ClusteringCancelledException();
            }
            if (chainLength == 0) {
                for (int i = 0; i < n; i++) {
                    if (active[i]) {
                        chain[chainLength++] = i;
                        break;
                    }
                }
            }
            int a = chain[chainLength - 1];
            int previous = chainLength >= 2 ? chain[chainLength - 2] : -1;
            // Nearest active neighbour of a; ties prefer the chain predecessor (termination), else the lower index.
            int best = -1;
            float bestDistance = Float.MAX_VALUE;
            if (previous >= 0) {
                best = previous;
                bestDistance = distance[index(a, previous, n)];
            }
            for (int j = 0; j < n; j++) {
                if (!active[j] || j == a) {
                    continue;
                }
                float d = distance[index(a, j, n)];
                if (d < bestDistance || (d == bestDistance && j != previous && best != previous && j < best)) {
                    bestDistance = d;
                    best = j;
                }
            }
            if (tree == null && bestDistance > mergeDistanceCutoff) {
                // a can never merge again: retire it; the chain below it stays valid.
                active[a] = false;
                activeCount--;
                chainLength--;
                result.add(clusterMembers.get(a));
                continue;
            }
            if (best == previous) {
                chainLength -= 2;
                int keep = Math.min(a, best);
                int drop = Math.max(a, best);
                int sizeKeep = size[keep];
                int sizeDrop = size[drop];
                for (int k = 0; k < n; k++) {
                    if (!active[k] || k == keep || k == drop) {
                        continue;
                    }
                    int ik = index(keep, k, n);
                    distance[ik] = (float) ((sizeKeep * (double) distance[ik]
                            + sizeDrop * (double) distance[index(drop, k, n)]) / (sizeKeep + sizeDrop));
                }
                if (tree != null) {
                    tree.add(new TopicDendrogram.Merge(keep, drop, bestDistance));
                }
                size[keep] = sizeKeep + sizeDrop;
                clusterMembers.get(keep).addAll(clusterMembers.get(drop));
                clusterMembers.set(drop, null);
                active[drop] = false;
                activeCount--;
                steps++;
                monitor.progress("Merging topics", n + steps, total);
            } else {
                chain[chainLength++] = best;
            }
        }
        for (int i = 0; i < n; i++) {
            if (active[i]) {
                result.add(clusterMembers.get(i));
            }
        }
        for (List<Integer> cluster : result) {
            java.util.Collections.sort(cluster);
        }
        java.util.Collections.sort(result, new java.util.Comparator<List<Integer>>() {
            public int compare(List<Integer> x, List<Integer> y) {
                return Integer.compare(x.get(0), y.get(0));
            }
        });
        monitor.progress("Merging topics", total, total);
        return result;
    }

    private static int rowBase(int i, int n) {
        // offset of (i, j) is rowBase(i) + j for j > i
        return (int) ((long) i * n - (long) i * (i + 1) / 2 - i - 1);
    }

    private static int index(int i, int j, int n) {
        return i < j ? rowBase(i, n) + j : rowBase(j, n) + i;
    }

    private static float[] normalised(float[] values) {
        double norm = 0;
        for (float v : values) {
            norm += v * v;
        }
        float[] out = new float[values.length];
        if (norm == 0) {
            return out; // cosine 0 to everything, as VectorMath.cosine treats a zero vector
        }
        double scale = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) (values[i] * scale);
        }
        return out;
    }

    private TopicProposal toProposal(List<Passage> cluster,
                                     Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        List<EmbeddingPort.EmbeddingVector> memberVectors =
                new ArrayList<EmbeddingPort.EmbeddingVector>();
        List<String> memberIds = new ArrayList<String>();
        for (Passage passage : cluster) {
            memberIds.add(passage.getPassageId());
            memberVectors.add(vectors.get(passage.getPassageId()));
        }
        EmbeddingPort.EmbeddingVector centroid = VectorMath.mean(memberVectors);
        // Representatives: members closest to the centroid, ties by insertion order.
        List<Passage> byCentroid = new ArrayList<Passage>(cluster);
        java.util.Collections.sort(byCentroid, new java.util.Comparator<Passage>() {
            public int compare(Passage a, Passage b) {
                double da = 1.0 - VectorMath.cosine(vectors.get(a.getPassageId()), centroid);
                double db = 1.0 - VectorMath.cosine(vectors.get(b.getPassageId()), centroid);
                return Double.compare(da, db);
            }
        });
        List<String> representatives = new ArrayList<String>();
        for (int i = 0; i < Math.min(representativesPerTopic, byCentroid.size()); i++) {
            representatives.add(byCentroid.get(i).getPassageId());
        }
        double intra = 0;
        for (EmbeddingPort.EmbeddingVector vector : memberVectors) {
            intra += VectorMath.cosine(vector, centroid);
        }
        double confidence = intra / memberVectors.size();
        String title = suggestedTitle(byCentroid.get(0).getText());
        return new TopicProposal(ids.next("topic-proposal"), memberIds, representatives, title,
                byCentroid.get(0).getText(), confidence, Lifecycle.PROPOSED);
    }

    /**
     * Deterministic evidence-bound title: the representative's first sentence, trimmed. Shared with the topic
     * analysis, which titles sub-topics the same way. Soft hyphens and line breaks are removed and a long sentence
     * is cut at a word boundary (live retest of #243: "FAQ\nHäufig gestellte Fragen" and soft hyphens in titles).
     */
    public static String suggestedTitle(String representativeText) {
        String text = representativeText == null ? "" : representativeText.replace("\u00AD", "")
                .replaceAll("\\s+", " ").trim();
        int end = text.indexOf('.');
        String head = end > 0 ? text.substring(0, end) : text;
        if (head.length() <= MAX_SUGGESTED_TITLE_CHARS) {
            return head;
        }
        int cut = head.lastIndexOf(' ', MAX_SUGGESTED_TITLE_CHARS);
        return head.substring(0, cut > MAX_SUGGESTED_TITLE_CHARS / 2 ? cut : MAX_SUGGESTED_TITLE_CHARS);
    }

    /** Longest suggested title, the label contract's title length. */
    static final int MAX_SUGGESTED_TITLE_CHARS = 80;
}
