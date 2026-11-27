package com.aresstack.writing4j.cluster;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;
import com.aresstack.writing4j.memory.IdSequence;
import com.aresstack.writing4j.memory.Passage;
import com.aresstack.writing4j.memory.TopicProposal;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** PR 20: the nearest-neighbour chain makes the same clusters as the former all-pairs scan. */
public class TopicClustererParityTest {

    static List<Passage> passages(int n) {
        List<Passage> list = new ArrayList<Passage>();
        for (int i = 0; i < n; i++) {
            list.add(new Passage(String.format("p%05d", i), "cap-" + (i % 7),
                    Arrays.asList("s" + i), "", "Passage " + i + ". Body text.", "fp"));
        }
        return list;
    }

    /** Points around a few random centres, so the cutoff leaves several real clusters and some singletons. */
    static Map<String, EmbeddingPort.EmbeddingVector> seededVectors(List<Passage> passages, int dimension,
                                                                     int centres, double spread, long seed) {
        Random random = new Random(seed);
        float[][] centre = new float[centres][dimension];
        for (float[] c : centre) {
            for (int d = 0; d < dimension; d++) {
                c[d] = (float) random.nextGaussian();
            }
        }
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (Passage passage : passages) {
            float[] base = centre[random.nextInt(centres)];
            float[] v = new float[dimension];
            for (int d = 0; d < dimension; d++) {
                v[d] = (float) (base[d] + spread * random.nextGaussian());
            }
            vectors.put(passage.getPassageId(), new EmbeddingPort.EmbeddingVector("m", "fp", v));
        }
        return vectors;
    }

    private static Set<Set<String>> memberships(List<TopicProposal> proposals) {
        Set<Set<String>> result = new HashSet<Set<String>>();
        for (TopicProposal proposal : proposals) {
            result.add(new HashSet<String>(proposal.getMemberPassageIds()));
        }
        return result;
    }

    private static Map<Set<String>, TopicProposal> byMembers(List<TopicProposal> proposals) {
        Map<Set<String>, TopicProposal> map = new LinkedHashMap<Set<String>, TopicProposal>();
        for (TopicProposal proposal : proposals) {
            map.put(new HashSet<String>(proposal.getMemberPassageIds()), proposal);
        }
        return map;
    }

    private void assertParity(int n, int dimension, int centres, double spread, long seed) {
        List<Passage> passages = passages(n);
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                seededVectors(passages, dimension, centres, spread, seed);
        List<TopicProposal> legacy = new LegacyTopicClusterer(IdSequence.counting()).cluster(passages, vectors);
        List<TopicProposal> chain = new TopicClusterer(IdSequence.counting()).cluster(passages, vectors);
        assertEquals("seed " + seed, memberships(legacy), memberships(chain));
        assertTrue("seed " + seed + " must give more than one cluster", chain.size() > 1);
        assertTrue("seed " + seed + " must merge something", chain.size() < n);
        Map<Set<String>, TopicProposal> legacyByMembers = byMembers(legacy);
        for (TopicProposal proposal : chain) {
            TopicProposal old = legacyByMembers.get(new HashSet<String>(proposal.getMemberPassageIds()));
            assertEquals(new HashSet<String>(old.getRepresentativePassageIds()),
                    new HashSet<String>(proposal.getRepresentativePassageIds()));
            assertEquals(old.getConfidence(), proposal.getConfidence(), 1e-6); // member order changes the summation order only
            assertEquals(old.getSuggestedTitle(), proposal.getSuggestedTitle());
        }
    }

    @Test
    public void sameClustersAsTheAllPairsScanOnThreeSeededCorpora() {
        assertParity(120, 32, 6, 0.9, 11L);
        assertParity(200, 64, 10, 1.1, 23L);
        assertParity(300, 48, 15, 0.8, 37L);
    }

    @Test
    public void clustersAreOrderedByTheirFirstPassageAndMembersKeepInputOrder() {
        List<Passage> passages = passages(80);
        List<TopicProposal> chain = new TopicClusterer(IdSequence.counting())
                .cluster(passages, seededVectors(passages, 16, 5, 0.7, 5L));
        String previousFirst = "";
        for (TopicProposal proposal : chain) {
            List<String> members = proposal.getMemberPassageIds();
            List<String> sorted = new ArrayList<String>(members);
            java.util.Collections.sort(sorted);
            assertEquals(sorted, members);
            assertTrue(members.get(0).compareTo(previousFirst) > 0);
            previousFirst = members.get(0);
        }
    }

    @Test
    public void reportsProgressAndStopsOnCancel() {
        List<Passage> passages = passages(100);
        Map<String, EmbeddingPort.EmbeddingVector> vectors = seededVectors(passages, 16, 5, 0.7, 3L);
        final AtomicLong last = new AtomicLong();
        final long[] total = {0};
        new TopicClusterer(IdSequence.counting()).cluster(passages, vectors, new ClusteringMonitor() {
            public void progress(String stage, long done, long all) {
                assertTrue(done >= last.get());
                last.set(done);
                total[0] = all;
            }

            public boolean isCancelled() {
                return false;
            }
        });
        assertEquals(total[0], last.get());
        final int[] polls = {0};
        try {
            new TopicClusterer(IdSequence.counting()).cluster(passages, vectors, new ClusteringMonitor() {
                public void progress(String stage, long done, long all) {
                }

                public boolean isCancelled() {
                    return ++polls[0] > 10;
                }
            });
            fail("a cancelled run must not return a result");
        } catch (ClusteringMonitor.ClusteringCancelledException expected) {
            assertEquals(11, polls[0]);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesMixedEmbeddingWorlds() {
        List<Passage> passages = passages(2);
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        vectors.put("p00000", new EmbeddingPort.EmbeddingVector("m", "fp", new float[]{1, 0}));
        vectors.put("p00001", new EmbeddingPort.EmbeddingVector("m", "other", new float[]{1, 0}));
        new TopicClusterer(IdSequence.counting()).cluster(passages, vectors);
    }

    @Test
    public void emptyAndSingleCorpora() {
        assertTrue(new TopicClusterer(IdSequence.counting()).cluster(new ArrayList<Passage>(),
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>()).isEmpty());
        List<Passage> one = passages(1);
        assertEquals(1, new TopicClusterer(IdSequence.counting())
                .cluster(one, seededVectors(one, 4, 1, 0.1, 1L)).size());
    }
}
