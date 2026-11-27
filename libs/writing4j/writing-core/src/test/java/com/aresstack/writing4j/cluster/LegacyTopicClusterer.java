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
 * PR 20 test oracle: the clusterer before the nearest-neighbour chain, verbatim. Hierarchical AGGLOMERATIVE clustering over passage vectors with cosine distance and average linkage:
 * no fixed topic count, small isolated topics stay visible, and the merge tree naturally maps to chapter
 * hierarchies later. Deterministic: ties resolve by insertion order. The result are {@link TopicProposal}s
 * — titles/summaries come from representative passages (AI may rephrase them later, membership never
 * depends on that phrasing).
 */
final class LegacyTopicClusterer {

    private final IdSequence ids;
    private final double mergeDistanceCutoff;
    private final int representativesPerTopic;

    LegacyTopicClusterer(IdSequence ids) {
        this(ids, 0.55, 2);
    }

    LegacyTopicClusterer(IdSequence ids, double mergeDistanceCutoff, int representativesPerTopic) {
        this.ids = ids;
        this.mergeDistanceCutoff = mergeDistanceCutoff;
        this.representativesPerTopic = representativesPerTopic;
    }

    public List<TopicProposal> cluster(List<Passage> passages,
                                       Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        List<List<Passage>> clusters = new ArrayList<List<Passage>>();
        for (Passage passage : passages) {
            if (!vectors.containsKey(passage.getPassageId())) {
                throw new IllegalArgumentException("no vector for passage " + passage.getPassageId());
            }
            List<Passage> singleton = new ArrayList<Passage>();
            singleton.add(passage);
            clusters.add(singleton);
        }
        // Agglomerate: always merge the currently closest pair until the cutoff distance is reached.
        while (clusters.size() > 1) {
            int bestA = -1;
            int bestB = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int a = 0; a < clusters.size(); a++) {
                for (int b = a + 1; b < clusters.size(); b++) {
                    double distance = averageLinkageDistance(clusters.get(a), clusters.get(b), vectors);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (bestDistance > mergeDistanceCutoff) {
                break;
            }
            clusters.get(bestA).addAll(clusters.remove(bestB));
        }

        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (List<Passage> cluster : clusters) {
            proposals.add(toProposal(cluster, vectors));
        }
        return proposals;
    }

    private double averageLinkageDistance(List<Passage> a, List<Passage> b,
                                          Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        double sum = 0;
        int pairs = 0;
        for (Passage pa : a) {
            for (Passage pb : b) {
                sum += 1.0 - VectorMath.cosine(vectors.get(pa.getPassageId()),
                        vectors.get(pb.getPassageId()));
                pairs++;
            }
        }
        return sum / pairs;
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
        String title = suggestedTitle(byCentroid.get(0));
        return new TopicProposal(ids.next("topic-proposal"), memberIds, representatives, title,
                byCentroid.get(0).getText(), confidence, Lifecycle.PROPOSED);
    }

    /** Deterministic evidence-bound title: the representative's first sentence, trimmed. */
    private static String suggestedTitle(Passage representative) {
        String text = representative.getText();
        int end = text.indexOf('.');
        String head = end > 0 ? text.substring(0, end) : text;
        return head.length() <= 80 ? head : head.substring(0, 80);
    }
}
