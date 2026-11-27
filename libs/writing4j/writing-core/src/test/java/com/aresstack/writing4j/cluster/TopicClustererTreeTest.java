package com.aresstack.writing4j.cluster;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;
import com.aresstack.writing4j.memory.IdSequence;
import com.aresstack.writing4j.memory.Passage;
import com.aresstack.writing4j.memory.TopicProposal;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Phase 3: the complete merge tree of one clustering run; its cut at the cutoff equals the flat topics. */
public class TopicClustererTreeTest {

    private static Set<Set<String>> sets(List<List<String>> groups) {
        Set<Set<String>> result = new HashSet<Set<String>>();
        for (List<String> group : groups) {
            result.add(new HashSet<String>(group));
        }
        return result;
    }

    private static List<List<String>> members(List<TopicProposal> proposals) {
        List<List<String>> result = new ArrayList<List<String>>();
        for (TopicProposal proposal : proposals) {
            result.add(proposal.getMemberPassageIds());
        }
        return result;
    }

    private void assertTreeParity(int n, int dimension, int centres, double spread, long seed) {
        List<Passage> passages = TopicClustererParityTest.passages(n);
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                TopicClustererParityTest.seededVectors(passages, dimension, centres, spread, seed);
        TopicClusterer clusterer = new TopicClusterer(IdSequence.counting());
        List<TopicProposal> flat = clusterer.cluster(passages, vectors);
        TopicClusterer.Tree tree = new TopicClusterer(IdSequence.counting()).clusterTree(passages, vectors,
                ClusteringMonitor.NONE);

        assertEquals("seed " + seed, sets(members(flat)), sets(members(tree.getTopics())));
        assertEquals("the cut at the cutoff is the flat result", sets(members(flat)),
                sets(tree.getDendrogram().cut(tree.getDendrogram().getTopicCutoff())));
        for (int i = 0; i < flat.size(); i++) {
            assertEquals(flat.get(i).getMemberPassageIds(), tree.getTopics().get(i).getMemberPassageIds());
            assertEquals(flat.get(i).getRepresentativePassageIds(),
                    tree.getTopics().get(i).getRepresentativePassageIds());
            assertEquals(flat.get(i).getSuggestedTitle(), tree.getTopics().get(i).getSuggestedTitle());
        }

        TopicDendrogram dendrogram = tree.getDendrogram();
        assertEquals("a tree up to one root", n - 1, dendrogram.getMerges().size());
        double previous = Double.NEGATIVE_INFINITY;
        for (TopicDendrogram.Merge merge : dendrogram.getMerges()) {
            assertTrue("merges by height", merge.getHeight() >= previous);
            previous = merge.getHeight();
        }
        assertEquals(n, dendrogram.cut(-1).size());
        assertEquals(1, dendrogram.cut(2).size());

        // Nesting: every group of a lower cut lies inside one group of a higher cut.
        Set<Set<String>> higher = sets(dendrogram.cut(0.55));
        for (List<String> lower : dendrogram.cut(0.40)) {
            boolean inside = false;
            for (Set<String> group : higher) {
                if (group.containsAll(lower)) {
                    inside = true;
                }
            }
            assertTrue("seed " + seed + ": " + lower + " nests", inside);
        }
    }

    @Test
    public void theTreeCutAtTheCutoffGivesTheFlatTopics() {
        assertTreeParity(120, 16, 5, 0.35, 11L);
        assertTreeParity(200, 24, 8, 0.45, 23L);
        assertTreeParity(90, 12, 3, 0.30, 37L);
    }

    @Test
    public void joinHeightsAreTheMergeThatFirstJoinsTwoGroups() {
        List<Passage> passages = TopicClustererParityTest.passages(60);
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                TopicClustererParityTest.seededVectors(passages, 12, 4, 0.3, 5L);
        TopicClusterer.Tree tree = new TopicClusterer(IdSequence.counting()).clusterTree(passages, vectors,
                ClusteringMonitor.NONE);
        List<List<String>> topics = members(tree.getTopics());
        Map<String, Double> heights = tree.getDendrogram().joinHeights(topics);
        int k = topics.size();
        assertEquals("every pair joins once", k * (k - 1) / 2, heights.size());
        for (Double height : heights.values()) {
            assertTrue("topics join above the cutoff", height > tree.getDendrogram().getTopicCutoff());
        }
    }

    /**
     * A split rule takes a group of the cut apart along the tree: here everything merges below the cutoff and the
     * rule splits every group larger than three passages, so the root falls into A (3) and B/C (6), and B/C into
     * B and C.
     */
    @Test
    public void aSplitRuleTakesGroupsOfTheCutApartAlongTheTree() {
        List<Passage> passages = TopicClustererParityTest.passages(9);
        float[][] points = {
                {1f, 0f, 0f}, {0.98f, 0.05f, 0f}, {0.97f, 0f, 0.05f},                          // A
                {0f, 1f, 0f}, {0.03f, 0.98f, 0f}, {0f, 0.97f, 0.04f},                          // B
                {0f, 0.5f, 0.87f}, {0.03f, 0.47f, 0.88f}, {0f, 0.53f, 0.85f}};                 // C, nearer B than A
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new java.util.LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (int i = 0; i < points.length; i++) {
            vectors.put(passages.get(i).getPassageId(), new EmbeddingPort.EmbeddingVector("m", "fp", points[i]));
        }
        double everything = 1.9;
        TopicClusterer.Tree plain = new TopicClusterer(IdSequence.counting(), everything, 2)
                .clusterTree(passages, vectors, ClusteringMonitor.NONE);
        assertEquals("without a rule the whole corpus is one topic", 1, plain.getTopics().size());

        final List<Double> seenHeights = new ArrayList<Double>();
        TopicDendrogram.SplitRule largerThanThree = new TopicDendrogram.SplitRule() {
            public boolean split(TopicDendrogram tree, List<String> members, double height) {
                seenHeights.add(height);
                return members.size() > 3;
            }
        };
        TopicClusterer.Tree split = new TopicClusterer(IdSequence.counting(), everything, 2)
                .clusterTree(passages, vectors, ClusteringMonitor.NONE, largerThanThree);
        Set<Set<String>> expected = new HashSet<Set<String>>();
        expected.add(new HashSet<String>(java.util.Arrays.asList("p00000", "p00001", "p00002")));
        expected.add(new HashSet<String>(java.util.Arrays.asList("p00003", "p00004", "p00005")));
        expected.add(new HashSet<String>(java.util.Arrays.asList("p00006", "p00007", "p00008")));
        assertEquals(expected, sets(members(split.getTopics())));
        assertEquals("the same cut from the tree", expected,
                sets(split.getDendrogram().cut(everything, largerThanThree)));
        assertTrue("the rule is asked with the join height", seenHeights.get(0) > 0.5);
        assertEquals("the NONE rule is the plain cut", sets(split.getDendrogram().cut(0.2)),
                sets(split.getDendrogram().cut(0.2, TopicDendrogram.SplitRule.NONE)));
    }

    @Test
    public void anEmptyCorpusGivesAnEmptyTree() {
        TopicClusterer.Tree tree = new TopicClusterer(IdSequence.counting()).clusterTree(
                new ArrayList<Passage>(), new java.util.HashMap<String, EmbeddingPort.EmbeddingVector>(), null);
        assertTrue(tree.getTopics().isEmpty());
        assertTrue(tree.getDendrogram().cut(0.5).isEmpty());
    }
}
